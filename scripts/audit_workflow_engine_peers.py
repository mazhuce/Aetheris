"""Bounded loopback engine peers. They record dispatch; they do not execute Spark/Flink business code."""
from __future__ import annotations
from collections import Counter
from datetime import datetime,timezone
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import re
import threading
import time
from urllib.parse import urlsplit,parse_qs
import uuid

ARTIFACT=b'print("controlled engine peer; no Spark business execution")\n'
ARTIFACT_KEY='engine_fixture.py'
ARTIFACT_SHA256=hashlib.sha256(ARTIFACT).hexdigest()
TERMINAL={'succeeded','failed','cancelled','timed_out'}
def utc():return datetime.now(timezone.utc).isoformat()
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('DuplicatePeerRequestField')
        result[key]=value
    return result

class Peer:
    def __init__(self,kind,token=''):
        if kind not in ('spark','flink'):raise ValueError('PeerKind')
        self.kind=kind;self.token=token;self.lock=threading.RLock();self.trace=[];self.jobs={};self.handles={};self.operations={}
        self.drop_next=False;self.cancel_immediate=False;self._barrier=None;self.inflight=0;self.trace_overflow=False;owner=self
        class Handler(BaseHTTPRequestHandler):
            timeout=5
            def log_message(self,*args):pass
            def do_GET(self):self.handle_request()
            def do_POST(self):self.handle_request()
            def do_DELETE(self):self.handle_request()
            def do_PATCH(self):self.handle_request()
            def handle_request(self):
                path=urlsplit(self.path).path;status=500;raw=b'';key=None;authenticated=False;dropped=False
                with owner.lock:owner.inflight+=1
                try:
                    authenticated=owner.kind!='spark' or hmac.compare_digest(self.headers.get('X-Aetheris-Runner-Token',''),owner.token)
                    if not authenticated:status=401;self.reply(status,{});return
                    length=int(self.headers.get('Content-Length','0'))
                    if length<0 or length>1048576:status=413;self.reply(status,{});return
                    raw=self.rfile.read(length)
                    body=json.loads(raw,object_pairs_hook=strict_object) if raw else {}
                    if not isinstance(body,dict):raise ValueError('PeerRequestMustBeObject')
                    status,response,key,drop=owner.route(self.command,path,parse_qs(urlsplit(self.path).query),body)
                    if drop:dropped=True;self.close_connection=True;return
                    self.reply(status,response)
                except (ValueError,KeyError,TypeError):status=400;self.reply(status,{})
                except (BrokenPipeError,ConnectionError,TimeoutError):status=503
                except Exception:status=500;self.reply(status,{})
                finally:
                    self.close_connection=True
                    with owner.lock:
                        if len(owner.trace)>=4096:owner.trace_overflow=True
                        else:owner.trace.append({'atUtc':utc(),'method':self.command,'path':path,'status':None if dropped else status,'responseDropped':dropped,'authenticated':authenticated,
                            'bodyBytes':len(raw),'bodySha256':hashlib.sha256(raw).hexdigest(),'requestKey':key})
                        owner.inflight-=1
            def reply(self,status,body):
                raw=json.dumps(body,separators=(',',':')).encode('utf-8');self.send_response(status)
                self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler);self.server.daemon_threads=True
        self.thread=threading.Thread(target=self.server.serve_forever,name='owned-'+kind+'-peer',daemon=True);self.thread.start()
    @property
    def origin(self):return 'http://127.0.0.1:'+str(self.server.server_address[1])
    def public(self,job):
        return {'id':job['id'],'requestKey':job['key'],'artifactDigest':ARTIFACT_SHA256,'status':job['state'],
            'applicationId':None,'affectedRows':7 if job['state']=='succeeded' else None,'result':{'controlledPeer':True},
            'cancellationConfirmedAt':job.get('cancelledAt')}
    def route(self,method,path,query,body):
        if self.kind=='spark':
            with self.lock:
                if method=='GET' and path=='/health':return 200,{'status':'UP','version':'controlled-peer','activeJobs':sum(j['state'] not in TERMINAL for j in self.jobs.values()),'maxConcurrency':1},None,False
                if method=='GET' and path=='/v1/artifacts/'+ARTIFACT_KEY:return 200,{'key':ARTIFACT_KEY,'sha256':ARTIFACT_SHA256,'size':len(ARTIFACT)},None,False
                if method=='POST' and path=='/v1/jobs':
                    key=body['requestKey']
                    if not isinstance(key,str) or not re.fullmatch(r'attempt-[1-9][0-9]*-f[1-9][0-9]*',key) or body.get('artifactDigest')!=ARTIFACT_SHA256 or body.get('artifactKey')!=ARTIFACT_KEY or body.get('artifactType')!='pyspark':raise ValueError('PeerBinding')
                    if key not in self.jobs:
                        job={'id':uuid.uuid4().hex,'key':key,'state':'running','parameters':body['parameters'],'resources':body['resources']}
                        self.jobs[key]=job;self.handles[job['id']]=key
                    job=self.jobs[key];drop=self.drop_next;self.drop_next=False
                    return 202,self.public(job),key,drop
                if method=='GET' and path.startswith('/v1/jobs/by-request/'):
                    key=path.removeprefix('/v1/jobs/by-request/');job=self.jobs.get(key)
                    return (200,self.public(job),key,False) if job else (404,{},key,False)
                found=re.fullmatch(r'/v1/jobs/([a-f0-9]{32})(/logs)?',path)
                if found and found[1] in self.handles:
                    key=self.handles[found[1]];job=self.jobs[key]
                    if found[2] and method=='GET':return 200,{'id':job['id'],'requestKey':key,'artifactDigest':ARTIFACT_SHA256,'content':'','nextOffset':int(query.get('offset',['0'])[0]),'eof':job['state'] in TERMINAL},key,False
                    if method=='DELETE':
                        job['state']='cancelled' if self.cancel_immediate else 'cancel_requested'
                        if self.cancel_immediate:job['cancelledAt']=utc()
                    if method in ('GET','DELETE'):return 200,self.public(job),key,False
            return 404,{},None,False
        if method=='GET' and path=='/v1/info':return 200,{'version':'controlled-flink-peer'},None,False
        if method=='POST' and path=='/v1/sessions':
            with self.lock:barrier=self._barrier;self._barrier=None
            if barrier:
                barrier[0].set()
                if not barrier[1].wait(12):raise ValueError('PeerSessionBarrierTimeout')
            return 200,{'sessionHandle':uuid.uuid4().hex},None,False
        statement=re.fullmatch(r'/v1/sessions/([a-f0-9]{32})/statements',path)
        if statement and method=='POST':
            if not isinstance(body.get('statement'),str):raise ValueError('PeerStatement')
            with self.lock:
                operation=uuid.uuid4().hex;job=uuid.uuid4().hex;self.operations[(statement[1],operation)]=job;self.jobs[job]={'state':'RUNNING'}
            return 200,{'operationHandle':operation},None,False
        operation=re.fullmatch(r'/v1/sessions/([a-f0-9]{32})/operations/([a-f0-9]{32})/(status|result/0|close|cancel)',path)
        if operation:
            with self.lock:
                job=self.operations.get((operation[1],operation[2]))
                if not job:return 404,{},None,False
                if operation[3]=='result/0':return 200,{'jobID':job},None,False
                if operation[3]=='status':return 200,{'status':'FINISHED'},None,False
                if operation[3]=='cancel':self.jobs[job]['state']='CANCELED'
                return 200,{},None,False
        job=re.fullmatch(r'/jobs/([a-f0-9]{32})',path)
        if job:
            with self.lock:
                state=self.jobs.get(job[1])
                if state is None:return 404,{},None,False
                if method=='PATCH':state['state']='CANCELED'
                return 200,{'jid':job[1],'state':state['state']},None,False
        return 404,{},None,False
    def finish(self,key=None):
        with self.lock:
            selected=self.jobs.values() if key is None else [self.jobs[key]]
            for job in selected:
                if job['state'] in TERMINAL or job['state'] in ('FINISHED','FAILED','CANCELED'):continue
                job['state']='succeeded' if self.kind=='spark' else 'FINISHED'
    def confirm_cancel(self,key):
        with self.lock:self.jobs[key]['state']='cancelled';self.jobs[key]['cancelledAt']=utc()
    def session_barrier(self):
        with self.lock:self._barrier=(threading.Event(),threading.Event());return self._barrier
    def post_count(self,key=None):
        with self.lock:return sum(r['method']=='POST' and r['path']=='/v1/jobs' and r['authenticated'] and (key is None or r['requestKey']==key) for r in self.trace)
    def artifact_reads(self):
        with self.lock:return sum(r['method']=='GET' and r['path'].startswith('/v1/artifacts/') for r in self.trace)
    def snapshot(self):
        with self.lock:return {'kind':self.kind,'origin':self.origin,'requests':list(self.trace),'acceptedJobs':len(self.jobs),'inFlight':self.inflight,'traceOverflow':self.trace_overflow,
            'scope':'Controlled HTTP responses only; no Spark/Flink business execution.'}
    def close(self):
        with self.lock:
            if self._barrier:self._barrier[1].set()
        self.server.shutdown();self.thread.join(5);self.server.server_close();deadline=time.monotonic()+6
        while self.inflight and time.monotonic()<deadline:time.sleep(.01)
        return not self.thread.is_alive() and self.inflight==0

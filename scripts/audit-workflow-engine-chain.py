#!/usr/bin/env python3
"""Real Auth/Workflow plus controlled engine HTTP peers; old runtime outcomes are never reused."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timedelta
import hashlib
import importlib.util
import importlib.metadata
import json
from pathlib import Path
import shutil
import socket
import time
import sys
from zoneinfo import ZoneInfo,ZoneInfoNotFoundError

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-workflow-engine-chain'
LABEL='aetheris.workflow-engine-chain.audit'
def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/filename);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
child=load('engine_chain_quality_base','scripts/audit-workflow-quality-child.py')
peers=load('engine_chain_peers','scripts/audit_workflow_engine_peers.py')
bundle=load('engine_chain_artifacts','scripts/security/artifact_bundle.py')
sql=child.sql;fixed=child.fixed;identity=child.identity;base=child.base
CACHE_MODULES=dict(child.MODULES)
MODULES={name:CACHE_MODULES[name] for name in ('auth','system','metadata','workflow','gateway')}
DENIALS=('actor-grant','task-disabled','definition-changed','artifact-revoked','approver-disabled','same-name-recreated','auth-unavailable')
ENGINE_CASES=frozenset(('engine-registry-fixture','engine-create-actor','engine-actor-login','engine-create-approver','engine-approver-login',
    'engine-spark-approved-source','engine-actor-cannot-approve','engine-lost-response-one-post','engine-original-origin-after-runner-change',
    'engine-spark-terminal-bound','engine-cancel-holds-capacity','engine-cancel-confirmed-releases','engine-flink-original-source',
    'engine-flink-gateway-finished-is-not-job-completion','engine-flink-job-terminal-bound','engine-flink-revoked-between-session-and-insert',
    'engine-flink-streaming-unexpected-finish-is-failed','engine-streaming-pipeline-zero-sql-downstream',
    'engine-mixed-pipeline-source-and-business-write','engine-no-workload-auth-denied','engine-worker-no-token-denied',
    'engine-spark-cron-exact-source','engine-flink-cron-exact-source',
    'engine-default-origin-no-authenticated-submit','engine-real-auth-denials-persisted','engine-wire-traces-complete',
    *('engine-queued-'+name+'-zero-dispatch' for name in DENIALS)))
REQUIRED_CASES=sql.SQL_CASES|ENGINE_CASES
for module in (child,sql,fixed,identity):module.MODULES=MODULES;module.OUTPUT=OUTPUT;module.LABEL=LABEL;module.REQUIRED_CASES=REQUIRED_CASES
base_finalizer=identity.finalize_status
PEER_CLEANUP=frozenset(('enginePeersStopped','engineTraceRetained'))
def finalize_status(report):
    base_finalizer(report)
    if not all(report.get('enginePeerCleanup',{}).get(key) is True for key in PEER_CLEANUP):report['status']='failed'
identity.finalize_status=finalize_status
original_sources=child.source_hashes
def source_hashes():
    values=original_sources()
    for name in ('scripts/audit-workflow-engine-chain.py','scripts/audit_workflow_engine_peers.py','scripts/security/artifact_bundle.py','scripts/requirements-audit.txt'):
        values[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    return values
sql.source_hashes=source_hashes

def flink_result_bound(source,done,mode,expected):
    if (mode,expected) not in (('bounded','success'),('streaming','failed')):return False
    bindings=('instance','task','attempt','fence','kind','uid','av','approvalUid','revision','approvalRevision',
              'definitionHash','approvalDefinitionHash','origin','handle','receipt','jobId')
    return (all(source.get(key) is not None and done.get(key)==source[key] for key in bindings)
        and source.get('token') is True and done.get('token') is True
        and all(source.get(key) is True and done.get(key) is True for key in ('sourceHashValid','receiptHashValid'))
        and source.get('result') is None and done.get('result') is not None and done.get('resultHashValid') is True
        and source.get('revision')==source.get('approvalRevision') and source.get('definitionHash')==source.get('approvalDefinitionHash')
        and source.get('material',{}).get('mode')==mode and done.get('material',{}).get('mode')==mode
        and done.get('state')==expected and done.get('resultState')==('succeeded' if expected=='success' else 'failed')
        and done.get('resultMode')==mode and done.get('remoteJobState')=='FINISHED'
        and done.get('resultJobId')==source.get('jobId')
        and done.get('resultJobManagerOrigin')==source.get('material',{}).get('jobManagerOrigin')
        and done.get('resultErrorCode')==('FLINK_UNEXPECTED_FINISH' if mode=='streaming' else None))

class Audit(sql.Audit):
    def __init__(self,output,manifest=None,anchor=None):
        if (manifest is None)!=(anchor is None):raise ValueError('BothArtifactReuseArgumentsRequired')
        super().__init__(output);self.manifest=manifest;self.anchor=anchor;self.engine_peers=[];self.operations=[]
        self.report['engineScenarioOperations']=self.operations
        self.report.update(purpose='five-real-applications-trusted-engine-auth-chain',
            expectedServices=list(MODULES),resourceSegmentation='Five actual services; quality child remains in its separately retained six-service scope. Fresh current five-service build by default; explicit anchored artifact reuse verifies its exact sources. No goal requirement waived.',
            limits='Actual Gateway/Auth/System/Metadata/Workflow JARs, PG/Redis, original identity/approval and engine authorization. Spark/Flink HTTP responses and registry rows are controlled fixtures; only SQL uses real business JDBC. No quality child rerun, physical Spark/Flink, backend I/O sandbox, browser or formal release claim.')
    def jvm_memory_flags(self):return ('-Xms24m','-Xmx160m','-XX:MaxMetaspaceSize=144m','-XX:ReservedCodeCacheSize=32m')
    def setup_containers(self):
        self.checkpoint('audit-python-timezone-preflight')
        try:
            timezone=ZoneInfo('Asia/Shanghai');version=importlib.metadata.version('tzdata')
        except (ZoneInfoNotFoundError,importlib.metadata.PackageNotFoundError):
            raise base.AuditFailure('AuditTimezoneDependencyMissing: install scripts/requirements-audit.txt') from None
        self.report['auditPythonRuntime']={'version':sys.version,'executable':sys.executable,'executableSha256':bundle.digest(Path(sys.executable)),
            'tzdataVersion':version,'dependencies':{name:importlib.metadata.version(name) for name in ('tzdata','PyYAML','cryptography')},
            'businessTimezone':timezone.key,'leapDayUtcOffsetSeconds':datetime(2024,2,29,23,59,tzinfo=timezone).utcoffset().total_seconds()}
        return super().setup_containers()
    def build(self):
        if self.manifest is None:
            self.checkpoint('fresh-current-five-service-build')
            self.report['artifactBuild']={'mode':'fresh-current-five-service-build','selectedModules':list(MODULES),'historicalRuntimeChecksCopied':False}
            return super().build()
        self.checkpoint('verify-anchored-exact-application-jars')
        try:jars,provenance=bundle.verify(ROOT,self.manifest,self.anchor,CACHE_MODULES)
        except (ValueError,KeyError,OSError) as error:raise base.AuditFailure('CachedArtifactVerificationFailed:'+str(error)) from None
        self.report['artifactReuse']=provenance;self.report['artifacts']={};result={}
        provenance['selectedModules']=list(MODULES)
        for name in MODULES:
            source=jars[name]
            target=self.folder/(name+'.jar');shutil.copyfile(source,target);result[name]=target
            self.report['artifacts'][name]={'sha256':bundle.digest(target),'bytes':target.stat().st_size}
        return result
    def start_apps(self,jars,redis):
        self.runner_token=self.secret();self.default=peers.Peer('spark',self.runner_token);self.selected=peers.Peer('spark',self.runner_token);self.flink=peers.Peer('flink')
        self.engine_peers=[self.default,self.selected,self.flink]
        config={'defaultRunnerOrigin':self.default.origin,'selectedRunnerOrigin':self.selected.origin,'flinkGatewayOrigin':self.flink.origin,
            'flinkJobManagerOrigin':self.flink.origin,'runnerCredentialSha256':hashlib.sha256(self.runner_token.encode()).hexdigest(),
            'registryAndBusinessEngineResults':'controlled fixtures','peerOrigins':'owned loopback only'}
        self.report['engineRuntimeConfiguration']=config;self.report['engineRuntimeConfigSha256']=hashlib.sha256(json.dumps(config,sort_keys=True).encode()).hexdigest()
        return super().start_apps(jars,redis)
    def workflow_engine_environment(self):
        return {'AETHERIS_SPARK_RUNNER_ENABLED':'true','AETHERIS_SPARK_RUNNER_BASE_URL':self.default.origin,'AETHERIS_SPARK_RUNNER_TOKEN':self.runner_token,
            'AETHERIS_SPARK_RUNNER_ALLOWED_ORIGINS':self.selected.origin,'AETHERIS_FLINK_SQL_GATEWAY_BASE_URL':self.flink.origin,'AETHERIS_FLINK_JOBMANAGER_BASE_URL':self.flink.origin}
    def call(self,client,method,path,expected,body=None):
        status,result=client.request(method,path,body)
        self.operations.append({'method':method,'path':path,'status':status,'expected':sorted(expected)})
        if status not in expected:raise base.AuditFailure('EngineScenarioHttpStatus:'+method+' '+path+' '+str(status))
        return result
    def wait(self,query,predicate,seconds=35):
        deadline=time.monotonic()+seconds;value=None
        while time.monotonic()<deadline:
            value=self.sql(query,True)
            if predicate(value):return value
            time.sleep(.2)
        raise base.AuditFailure('EngineScenarioStateDeadline:'+str(value))
    def state(self,instance):
        return self.sql("""SELECT json_build_object('instance',i.id,'task',i.task_id,'state',i.status,'attempt',a.id,'fence',a.fencing_token,
            'kind',a.invocation_json->>'kind','uid',(a.invocation_json->>'uid')::bigint,'av',(a.invocation_json->>'accessVersion')::bigint,
            'approvalUid',(a.approval_json->>'uid')::bigint,'token',a.engine_dispatch_token IS NOT NULL,'origin',a.engine_runner_origin,
            'handle',a.remote_handle,'receipt',a.engine_receipt_sha256,'result',a.engine_result_sha256,'material',a.sealed_manifest->'execution'->'engineDefinition',
            'revision',a.execution_revision,'approvalRevision',(a.approval_json->>'revision')::bigint,
            'definitionHash',a.sealed_manifest->>'definitionSha256','approvalDefinitionHash',a.approval_json->>'definitionSha256',
            'jobId',a.flink_job_id,'receiptHashValid',a.engine_receipt_sha256=encode(sha256(convert_to(a.engine_receipt_json::text,'UTF8')),'hex'),
            'resultHashValid',a.engine_result_sha256=encode(sha256(convert_to(a.engine_result_json::text,'UTF8')),'hex'),
            'resultState',a.engine_result_json->>'state','resultErrorCode',a.engine_result_json->'remote'->>'errorCode',
            'remoteJobState',a.engine_result_json->'remote'->'result'->>'jobState','resultMode',a.engine_result_json->'remote'->'result'->>'flinkMode',
            'resultJobId',a.engine_result_json->'remote'->'result'->>'jobId','resultJobManagerOrigin',a.engine_result_json->'remote'->'result'->>'jobManagerOrigin',
            'sourceHashValid',a.manifest_sha256=encode(sha256(convert_to(a.sealed_manifest::text,'UTF8')),'hex'),
            'allocation',(SELECT status FROM workflow.spark_resource_allocation WHERE attempt_id=a.id))
            FROM workflow.task_instance i JOIN workflow.execution_attempt a ON a.id=i.active_attempt_id WHERE i.id="""+str(int(instance)),True)
    def terminal(self,instance,expected='success'):
        self.wait('SELECT to_json(status) FROM workflow.task_instance WHERE id='+str(int(instance)),lambda s:s==expected)
        return self.state(instance)
    def grant(self,uid,task):
        return self.call(self.admin,'POST','/api/auth/rbac/grants',{200,201},{'granteeType':'user','granteeId':uid,'resourceType':'workflow_task','resourceId':task,'actions':['view','execute'],'enabled':True})
    def task(self,kind='spark',approver=None,grant=True,cron=None,flink_mode='bounded'):
        if kind=='flink' and flink_mode not in ('bounded','streaming'):raise ValueError('UnsupportedPublicFlinkFixtureMode')
        body={'name':'engine-'+kind+'-'+str(len(self.operations)),'taskType':kind,'timeoutSeconds':60,'maxRetries':0,'status':'enabled'}
        if kind=='spark':body['jobDefinitionId']=self.job_id
        else:body.update(taskSql='INSERT INTO fixture_sink SELECT 1',flinkEndpoint=self.flink.origin,flinkMode=flink_mode)
        if cron:body['cronExpression']=cron
        created=self.call(self.admin,'POST','/api/workflow/tasks',{200},body);task=sql.task_view_id(created);prefix='/api/workflow/tasks/'+str(task)
        if kind=='flink' and created['task'].get('flinkMode')!=flink_mode:raise base.AuditFailure('PublicFlinkFixtureModeMismatch')
        state=self.call(self.admin,'GET',prefix+'/approval',{200})
        approved=self.call(approver or self.admin,'POST',prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        if kind=='flink':
            if approved.get('approved') is not True:raise base.AuditFailure('FlinkFixtureApprovalMissing')
            self.report.setdefault('flinkTaskApprovals',[]).append({'taskId':task,'mode':flink_mode,**approved})
        resource_grant=self.grant(self.actor_id,task) if grant else None
        return task,resource_grant
    def run_task(self,task,date='2024-03-06',client=None):
        result=self.call(client or self.user,'POST','/api/workflow/tasks/'+str(task)+'/run',{200},{'bizDate':date})
        return result,self.state(result['id'])
    def key(self,state):return 'attempt-'+str(state['attempt'])+'-f'+str(state['fence'])
    def insert_id(self,statement):
        value=self.sql('WITH inserted AS ('+statement+') SELECT id FROM inserted',True)
        if type(value) is not int or value<=0:raise base.AuditFailure('ExpectedOnePositiveFixtureId')
        return value
    def seed_registry(self):
        literal=fixed.literal
        env=self.insert_id("INSERT INTO workflow.compute_environment(environment_key,name,adapter_type,spark_version,java_version,python_version,master,max_cores,max_memory_mb,max_timeout_seconds,max_concurrency) VALUES('engine-auth-fixture','engine-auth-fixture','managed_local_submit','3.5.6','21','3.12','local[1]',2,2048,1800,1) RETURNING id")
        self.artifact_id=self.insert_id("INSERT INTO workflow.managed_artifact(name,version,artifact_key,sha256,status,created_by,approved_by,approved_at) VALUES('engine-auth-fixture','1',"+literal(peers.ARTIFACT_KEY)+','+literal(peers.ARTIFACT_SHA256)+",'approved','controlled-registry-owner',"+literal(self.admin_username)+",clock_timestamp()) RETURNING id")
        self.job_id=self.insert_id("INSERT INTO workflow.spark_job_definition(name,environment_id,artifact_id,output_uri,driver_cores,driver_memory_mb,timeout_seconds,status,definition_digest,owner,published_by,published_at) VALUES('engine-auth-fixture',"+str(env)+','+str(self.artifact_id)+",'hdfs://localhost:9000/user/aetheris/managed/engine_fixture',1,768,30,'published',"+literal('a'*64)+",'controlled-registry-owner',"+literal(self.admin_username)+",clock_timestamp()) RETURNING id")
        self.runner_id=self.insert_id("INSERT INTO workflow.compute_runner(environment_id,runner_key,name,base_url,max_cores,max_memory_mb,max_concurrency,created_by) VALUES("+str(env)+",'engine-auth-selected','engine-auth-selected',"+literal(self.selected.origin)+",2,2048,1,"+literal(self.admin_username)+") RETURNING id")
        self.check('engine-registry-fixture',self.job_id>0 and self.runner_id>0,scope='Owned registry rows are fixtures; task approval and actor permissions use real application APIs.')
    def scenarios(self,ports):
        super().scenarios(ports);self.checkpoint('real-auth-workflow-controlled-engines')
        self.admin=base.HttpClient('http://127.0.0.1:'+str(ports['gateway']),True,self.secrets)
        self.call(self.admin,'POST','/api/auth/login',{200},{'username':self.admin_username,'password':self.admin_password})
        password=self.secret();name='engine_actor_'+self.run_id[:8]
        actor=self.http('engine-create-actor',self.admin,'POST','/api/auth/users',{201},{'username':name,'password':password,'role':'viewer','status':'active'});self.actor_id=int(actor['id'])
        self.user=base.HttpClient(self.admin.base,True,self.secrets);self.http('engine-actor-login',self.user,'POST','/api/auth/login',{200},{'username':name,'password':password})
        password=self.secret();name='engine_approver_'+self.run_id[:8]
        person=self.http('engine-create-approver',self.admin,'POST','/api/auth/users',{201},{'username':name,'password':password,'role':'admin','status':'active'});self.approver_id=int(person['id'])
        self.approver=base.HttpClient(self.admin.base,True,self.secrets);self.http('engine-approver-login',self.approver,'POST','/api/auth/login',{200},{'username':name,'password':password})
        self.seed_registry();task,_=self.task();state=self.call(self.admin,'GET','/api/workflow/tasks/'+str(task)+'/approval',{200})
        self.call(self.admin,'POST','/api/auth/rbac/grants',{200,201},{'granteeType':'user','granteeId':self.actor_id,'resourceType':'workflow_task','resourceId':task,'actions':['update'],'enabled':True})
        self.http('engine-actor-cannot-approve',self.user,'POST','/api/workflow/tasks/'+str(task)+'/approve',{403},{'expectedRevision':state['executionRevision']})
        self.selected.drop_next=True;result,source=self.run_task(task)
        self.check('engine-spark-approved-source',source['uid']==self.actor_id and source['kind']=='manual' and source['approvalUid']!=self.actor_id and source['token'] is True and source['sourceHashValid'] is True and source['origin']==self.selected.origin and source['receipt'] is not None,source=source)
        key=self.key(source);self.check('engine-lost-response-one-post',result['status']=='running' and self.selected.post_count(key)==1 and any(r['path']=='/v1/jobs/by-request/'+key for r in self.selected.snapshot()['requests']))
        before=len(self.selected.snapshot()['requests']);self.sql('UPDATE workflow.compute_runner SET base_url='+fixed.literal(self.default.origin)+' WHERE id='+str(self.runner_id));self.selected.finish(key);done=self.terminal(result['id'])
        self.check('engine-original-origin-after-runner-change',len(self.selected.snapshot()['requests'])>before and done['origin']==self.selected.origin and self.default.post_count()==0)
        self.check('engine-spark-terminal-bound',done['state']=='success' and done['receipt'] is not None and done['result'] is not None and done['allocation']=='released')
        self.sql('UPDATE workflow.compute_runner SET base_url='+fixed.literal(self.selected.origin)+' WHERE id='+str(self.runner_id))
        task,_=self.task();cancelled,source=self.run_task(task);key=self.key(source)
        request=self.call(self.user,'POST','/api/workflow/tasks/instances/'+str(cancelled['id'])+'/cancel',{200})
        held=self.state(cancelled['id']);self.check('engine-cancel-holds-capacity',request['status']=='cancel_requested' and held['allocation']=='admitted' and held['result'] is None)
        self.selected.confirm_cancel(key);done=self.terminal(cancelled['id'],'cancelled');self.check('engine-cancel-confirmed-releases',done['allocation']=='released' and done['result'] is not None)
        self.flink_scenarios();self.flink_unexpected_finish();self.mixed_pipeline();self.streaming_pipeline()
        for kind in ('spark','flink'):self.cron_scenario(kind)
        for kind in DENIALS[:-1]:self.queued_denial(kind)
        direct=base.HttpClient('http://127.0.0.1:'+str(ports['auth']));self.http('engine-no-workload-auth-denied',direct,'POST','/internal/v2/permissions/engine-execution-authorize',{401},{})
        self.http('engine-worker-no-token-denied',base.HttpClient(self.selected.origin),'POST','/v1/jobs',{401},{})
        self.check('engine-default-origin-no-authenticated-submit',self.default.post_count()==0)
        denied=self.wait("SELECT count(*) FROM system.permission_event WHERE resource_type='engine_execution' AND decision='DENIED'",lambda count:count>=2)
        self.check('engine-real-auth-denials-persisted',denied>=2,actualPersistedDenials=denied)
        self.queued_denial('auth-unavailable')
        self.report['engineScenarioOperations']=self.operations
        self.check('engine-wire-traces-complete',all(p.inflight==0 and not p.trace_overflow for p in self.engine_peers),requestCounts=[len(p.snapshot()['requests']) for p in self.engine_peers])
    def flink_scenarios(self):
        task,_=self.task('flink',flink_mode='bounded');result,_=self.run_task(task);source=self.flink_job_source(result['id'])
        self.check('engine-flink-original-source',source['uid']==self.actor_id and source['kind']=='manual' and source['token'] is True and source['receipt'] is not None and source['material']['mode']=='bounded' and source['material']['jobManagerOrigin']==self.flink.origin,source=source)
        self.check('engine-flink-gateway-finished-is-not-job-completion',source['state']=='running' and source['result'] is None)
        self.flink.finish(source['jobId']);done=self.terminal(result['id']);self.check('engine-flink-job-terminal-bound',flink_result_bound(source,done,'bounded','success'),source=source,result=done)
        task,grant=self.task('flink',flink_mode='streaming');entered,release=self.flink.session_barrier();before=len(self.flink.operations)
        with ThreadPoolExecutor(max_workers=1) as workers:
            pending=workers.submit(self.run_task,task,'2024-03-07')
            try:
                if not entered.wait(12):raise base.AuditFailure('FlinkSessionBarrierNotReached')
                self.call(self.admin,'DELETE','/api/auth/rbac/grants/'+str(grant['id']),{204})
            finally:release.set()
            result,source=pending.result(timeout=35)
        self.check('engine-flink-revoked-between-session-and-insert',result['status']=='unknown' and source['token'] is True and source['receipt'] is None and len(self.flink.operations)==before,source=source)
    def flink_job_source(self,instance):
        job=self.wait("SELECT coalesce(to_json(a.flink_job_id),'null'::json) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.active_attempt_id=a.id WHERE i.id="+str(int(instance)),lambda value:isinstance(value,str) and len(value)==32)
        source=self.state(instance)
        matching=any(record.get('taskId')==source.get('task') and record.get('mode')==source.get('material',{}).get('mode')
            and record.get('approved') is True and record.get('executionRevision')==source.get('revision')
            and record.get('definitionSha256')==source.get('definitionHash') for record in self.report.get('flinkTaskApprovals',[]))
        if not matching or source.get('jobId')!=job:raise base.AuditFailure('FlinkPublicApprovalSealMismatch')
        return source
    def flink_unexpected_finish(self):
        task,_=self.task('flink',flink_mode='streaming');result,_=self.run_task(task,'2024-03-10');source=self.flink_job_source(result['id'])
        self.flink.finish(source['jobId']);done=self.terminal(result['id'],'failed')
        self.check('engine-flink-streaming-unexpected-finish-is-failed',source['kind']=='manual' and source['uid']==self.actor_id and flink_result_bound(source,done,'streaming','failed'),source=source,result=done,engineResults='controlled HTTP peer')
    def mixed_pipeline(self):
        first,_=self.task();middle,_=self.task('flink',flink_mode='bounded')
        target_ds=self.sql("SELECT id FROM metadata.data_source WHERE name='workflow-target-writer'",True)
        last=sql.task_view_id(self.call(self.admin,'POST','/api/workflow/tasks',{200},{'name':'mixed-engine-sql','taskType':'sql','datasourceId':target_ds,'taskSql':"INSERT INTO business.sql_output(id,label,biz_date) VALUES(50,'mixed-engine-current','${bizDate}')",'timeoutSeconds':60,'maxRetries':0,'status':'enabled'}))
        self.grant(self.actor_id,last)
        for downstream,upstream in ((middle,first),(last,middle)):
            self.call(self.admin,'POST','/api/workflow/tasks/'+str(downstream)+'/dependencies?upstreamTaskId='+str(upstream),{200})
            state=self.call(self.admin,'GET','/api/workflow/tasks/'+str(downstream)+'/approval',{200});self.call(self.admin,'POST','/api/workflow/tasks/'+str(downstream)+'/approve',{200},{'expectedRevision':state['executionRevision']})
        run=self.call(self.user,'POST','/api/workflow/pipelines',{200},{'targetTaskId':last,'bizDate':'2024-03-08'})
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            self.selected.finish();self.flink.finish();state=self.sql('SELECT to_json(status) FROM workflow.pipeline_run WHERE id='+str(run['id']),True)
            if state not in ('queued','running'):break
            time.sleep(.3)
        proof=self.sql("SELECT json_build_object('nodes',count(*),'bound',count(*) FILTER(WHERE n.status='succeeded' AND a.invocation_json->>'kind'='pipeline' AND (a.invocation_json->>'uid')::bigint="+str(self.actor_id)+" AND a.pipeline_run_id=n.pipeline_run_id AND a.pipeline_node_id=n.id AND a.pipeline_fence=p.execution_fence)) FROM workflow.pipeline_run p JOIN workflow.pipeline_node_run n ON n.pipeline_run_id=p.id JOIN workflow.execution_attempt a ON a.id=n.attempt_id WHERE p.id="+str(run['id']),True)
        self.report['mixedPipelineObservation']=self.sql("SELECT json_build_object('status',p.status,'errorSha256',encode(sha256(convert_to(p.error_message,'UTF8')),'hex'),'nodes',(SELECT json_agg(json_build_object('task',n.task_id,'type',t.task_type,'state',n.status,'attempt',n.attempt_id,'instance',n.instance_id,'fence',n.fencing_token) ORDER BY n.sequence_no) FROM workflow.pipeline_node_run n JOIN workflow.task t ON t.id=n.task_id WHERE n.pipeline_run_id=p.id)) FROM workflow.pipeline_run p WHERE p.id="+str(run['id']),True)
        row=self.sql("SELECT coalesce((SELECT json_build_array(label,biz_date::text,writer) FROM business.sql_output WHERE id=50),'null'::json)",True)
        self.check('engine-mixed-pipeline-source-and-business-write',state=='succeeded' and proof=={'nodes':3,'bound':3} and row==['mixed-engine-current','2024-03-08','audit_target_writer'],runId=run['id'],proof=proof,businessSqlRow=row,engineResults='controlled HTTP peers')
    def streaming_pipeline(self):
        first,_=self.task('flink',flink_mode='streaming')
        target_ds=self.sql("SELECT id FROM metadata.data_source WHERE name='workflow-target-writer'",True)
        last=sql.task_view_id(self.call(self.admin,'POST','/api/workflow/tasks',{200},{'name':'unexpected-streaming-no-sql','taskType':'sql','datasourceId':target_ds,'taskSql':"INSERT INTO business.sql_output(id,label,biz_date) VALUES(51,'unexpected-streaming-must-not-write','${bizDate}')",'timeoutSeconds':60,'maxRetries':0,'status':'enabled'}))
        self.grant(self.actor_id,last)
        self.call(self.admin,'POST','/api/workflow/tasks/'+str(last)+'/dependencies?upstreamTaskId='+str(first),{200})
        approval=self.call(self.admin,'GET','/api/workflow/tasks/'+str(last)+'/approval',{200})
        self.call(self.admin,'POST','/api/workflow/tasks/'+str(last)+'/approve',{200},{'expectedRevision':approval['executionRevision']})
        run=self.call(self.user,'POST','/api/workflow/pipelines',{200},{'targetTaskId':last,'bizDate':'2024-03-11'})
        instance=self.wait("SELECT coalesce(to_json(instance_id),'null'::json) FROM workflow.pipeline_node_run WHERE pipeline_run_id="+str(run['id'])+' AND task_id='+str(first),lambda value:type(value) is int and value>0)
        source=self.flink_job_source(instance);self.flink.finish(source['jobId']);done=self.terminal(instance,'failed')
        self.wait('SELECT to_json(status) FROM workflow.pipeline_run WHERE id='+str(run['id']),lambda value:value not in ('queued','running'))
        proof=self.sql("""SELECT json_build_object('state',p.status,'nodes',(SELECT count(*) FROM workflow.pipeline_node_run n WHERE n.pipeline_run_id=p.id),
            'flinkState',f.status,'sqlState',s.status,'sqlInstance',s.instance_id,'sqlAttempt',s.attempt_id,
            'sqlAttempts',(SELECT count(*) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id WHERE a.pipeline_run_id=p.id AND i.task_id=s.task_id),
            'sqlInstances',(SELECT count(*) FROM workflow.task_instance i WHERE i.task_id=s.task_id),
            'businessRows',(SELECT count(*) FROM business.sql_output WHERE id=51),'flinkSuccessEvidence',workflow.pipeline_success_evidence(f.id))
            FROM workflow.pipeline_run p JOIN workflow.pipeline_node_run f ON f.pipeline_run_id=p.id AND f.task_id="""+str(first)+
            ' JOIN workflow.pipeline_node_run s ON s.pipeline_run_id=p.id AND s.task_id='+str(last)+' WHERE p.id='+str(run['id']),True)
        expected={'state':'failed','nodes':2,'flinkState':'failed','sqlState':'skipped','sqlInstance':None,'sqlAttempt':None,
                  'sqlAttempts':0,'sqlInstances':0,'businessRows':0,'flinkSuccessEvidence':False}
        self.report['streamingPipelineObservation']={'runId':run['id'],'proof':proof,'source':source,'result':done}
        self.check('engine-streaming-pipeline-zero-sql-downstream',proof==expected and source['kind']=='pipeline' and source['uid']==self.actor_id and flink_result_bound(source,done,'streaming','failed'),runId=run['id'],proof=proof,source=source,result=done,engineResults='controlled HTTP peer; real SQL target must remain untouched')
    def cron_scenario(self,kind):
        fire=datetime.now(ZoneInfo('Asia/Shanghai'))+timedelta(seconds=20)
        cron=f'{fire.second} {fire.minute} {fire.hour} {fire.day} {fire.month} ? {fire.year}'
        task,_=self.task(kind,cron=cron,flink_mode='bounded');prefix='/api/workflow/tasks/'+str(task);state=self.call(self.user,'GET',prefix+'/approval',{200})
        self.call(self.user,'POST',prefix+'/cron-binding',{200},{'expectedRevision':state['executionRevision'],'expectedGeneration':state['scheduleGeneration']})
        deadline=time.monotonic()+45;instances=[]
        while time.monotonic()<deadline:
            self.selected.finish();self.flink.finish()
            instances=self.sql("SELECT coalesce(json_agg(json_build_object('id',id,'state',status,'date',biz_date)),'[]'::json) FROM workflow.task_instance WHERE task_id="+str(task),True)
            if instances and instances[0]['state'] not in ('queued','running','submitting'):break
            time.sleep(.3)
        if len(instances)!=1:raise base.AuditFailure('ExpectedOneEngineCronInstance')
        source=self.state(instances[0]['id']);generation=self.sql("SELECT to_json(a.source_schedule_generation=t.schedule_version AND a.source_binding_generation=t.cron_binding_generation) FROM workflow.task t JOIN workflow.task_instance i ON i.task_id=t.id JOIN workflow.execution_attempt a ON a.id=i.active_attempt_id WHERE t.id="+str(task),True)
        self.check('engine-'+kind+'-cron-exact-source',instances[0]['state']=='success' and instances[0]['date']==(fire.date()-timedelta(days=1)).isoformat() and source['kind']=='cron' and source['uid']==self.actor_id and source['token'] is True and source['receipt'] is not None and source['result'] is not None and generation is True,source=source)
    def queued_denial(self,kind):
        holder,_=self.task();holding,held=self.run_task(holder,'2024-03-09');approval=self.approver if kind=='approver-disabled' else None
        target,grant=self.task(approver=approval);client=self.user;old_uid=None;replacement_uid=None
        if kind=='same-name-recreated':
            name='replacement_'+self.run_id[:8];password=self.secret();person=self.call(self.admin,'POST','/api/auth/users',{201},{'username':name,'password':password,'role':'viewer','status':'active'});old_uid=int(person['id']);self.grant(old_uid,target)
            client=base.HttpClient(self.admin.base,True,self.secrets);self.call(client,'POST','/api/auth/login',{200},{'username':name,'password':password})
        result,source=self.run_task(target,'2024-03-09',client);key=self.key(source)
        if result['status']!='queued' or source['token']:raise base.AuditFailure('ExpectedUndispatchedQueuedEngineSource')
        reads=self.selected.artifact_reads()
        if kind=='actor-grant':self.call(self.admin,'DELETE','/api/auth/rbac/grants/'+str(grant['id']),{204})
        elif kind=='task-disabled':self.call(self.admin,'PUT','/api/workflow/tasks/'+str(target),{200},{'name':'disabled-engine-task-'+str(target),'taskType':'spark','jobDefinitionId':self.job_id,'timeoutSeconds':60,'maxRetries':0,'status':'disabled'})
        elif kind=='definition-changed':self.sql('UPDATE workflow.task SET timeout_seconds=61 WHERE id='+str(target))
        elif kind=='artifact-revoked':self.sql("UPDATE workflow.managed_artifact SET status='revoked' WHERE id="+str(self.artifact_id))
        elif kind=='approver-disabled':self.call(self.admin,'PUT','/api/auth/users/'+str(self.approver_id),{200},{'status':'disabled'})
        elif kind=='same-name-recreated':
            self.call(self.admin,'DELETE','/api/auth/users/'+str(old_uid),{204});password=self.secret();person=self.call(self.admin,'POST','/api/auth/users',{201},{'username':name,'password':password,'role':'viewer','status':'active'});replacement_uid=int(person['id']);self.grant(replacement_uid,target)
        if kind=='auth-unavailable':
            for service,process,_,_ in self.processes:
                if service=='auth':
                    process.terminate();process.wait(timeout=10)
                    for record in self.report['processes']:
                        if record['service']=='auth':record['health']='intentionally-stopped-for-engine-auth-fault'
                    self.report['engineAuthFault']={'ownedPid':process.pid,'observedExitCode':process.returncode};break
        self.selected.finish(self.key(held));self.terminal(holding['id'])
        done=self.terminal(result['id'],'failed')
        self.check('engine-queued-'+kind+'-zero-dispatch',done['token'] is False and done['receipt'] is None and done['allocation']=='released' and self.selected.post_count(key)==0 and self.selected.artifact_reads()==reads and (kind!='same-name-recreated' or old_uid!=replacement_uid and done['uid']==old_uid),source=done,oldUid=old_uid,replacementUid=replacement_uid)
        if kind=='artifact-revoked':self.sql("UPDATE workflow.managed_artifact SET status='approved' WHERE id="+str(self.artifact_id))
    def cleanup(self):
        stopped=True;traces=[]
        for peer in self.engine_peers:
            try:
                closed=peer.close();stopped=stopped and closed
                with socket.socket() as probe:
                    probe.settimeout(.3);stopped=stopped and probe.connect_ex(('127.0.0.1',peer.server.server_address[1]))!=0
                traces.append(peer.snapshot())
            except Exception:stopped=False
        retained=False
        try:
            path=self.folder/'engine-http-traces.json';path.write_text(json.dumps({'scope':'Actual HTTP trace; controlled engine responses, no raw token or SQL bodies','peers':traces},indent=2)+'\n',encoding='utf-8')
            self.report['engineHttpTrace']={'path':path.relative_to(ROOT).as_posix(),'sha256':bundle.digest(path)};retained=True
        except OSError:retained=False
        self.report['enginePeerCleanup']={'enginePeersStopped':stopped,'engineTraceRetained':retained}
        if not stopped or not retained:self.report['status']='failed'
        super().cleanup()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True);parser.add_argument('--artifacts-manifest');parser.add_argument('--expected-manifest-sha256')
    args=parser.parse_args()
    if (args.artifacts_manifest is None)!=(args.expected_manifest_sha256 is None):parser.error('BothArtifactReuseArgumentsRequired: provide both artifact reuse arguments or neither for a fresh build')
    report=Audit(args.output,args.artifacts_manifest,args.expected_manifest_sha256).run()
    print('Workflow engine chain '+report['status']+'; checks='+str(len(report['checks']))+'; cleanup='+str(all(report['cleanup'].values())))
    return 0 if report['status']=='passed' else 1
if __name__=='__main__':raise SystemExit(main())

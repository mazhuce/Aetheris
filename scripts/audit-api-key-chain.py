#!/usr/bin/env python3
"""Real, disposable gateway -> serving -> metadata/auth API-key acceptance."""
from __future__ import annotations
import argparse
import base64
from decimal import Decimal
import hashlib
import hmac
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime,timezone

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts/database'))
import database_roles as database
import contract as database_contract

key_spec=importlib.util.spec_from_file_location('api_chain_keys',ROOT/'scripts/security/installation.py')
keys_module=importlib.util.module_from_spec(key_spec);key_spec.loader.exec_module(keys_module)

MODULES={'gateway':'apps/gateway','auth':'services/auth','metadata':'services/metadata','serving':'services/serving'}
POSTGRES_IMAGE='postgres:16-alpine'
REDIS_IMAGE='docker.1panel.live/library/redis:7.4-alpine'
FLAGS=['-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.redirects.retrylimit=1']
REQUIRED_CLEANUP=('processes','containers','credentialsRetired','privateKeysRemoved','volumesAbsent')


class AuditFailure(RuntimeError):pass


def utc():return datetime.now(timezone.utc).isoformat()


def require_status(status,expected,label):
    if status not in expected:raise AuditFailure(f'{label}: expected HTTP {sorted(expected)}, received {status}')


def pure_key_headers(secret,extra=None):
    headers={'X-API-Key':secret}
    for key,value in (extra or {}).items():
        if key.lower() in ('authorization','cookie'):raise ValueError('Pure API-key clients cannot attach JWT/cookies')
        headers[key]=value
    return headers


def require_business_rows(body):
    try:
        if body.get('error') or body['rowCount']!=3 or body['columns']!=['id','name','amount']:raise ValueError()
        got=sorted((int(row[0]),str(row[1]),Decimal(str(row[2]))) for row in body['rows'])
        if got!=[(1,'A',Decimal(10)),(2,'B',Decimal(20)),(3,'C',Decimal(30))]:raise ValueError()
    except (KeyError,TypeError,ValueError,ArithmeticError):raise AuditFailure('Actual business rows differ from the isolated fixture') from None


def redact(text,values):
    for value in sorted(set(values),key=len,reverse=True):
        if value:text=text.replace(value,'[REDACTED]')
    text=re.sub(r'\bak_[A-Za-z0-9_-]{12,}\b','[REDACTED_API_KEY]',text)
    return re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b','[REDACTED_JWT]',text)


def validate_output(path):
    path=Path(path).resolve()
    allowed=(ROOT/'output/audit-api-key-chain').resolve()
    if ROOT.resolve() not in allowed.parents or path==allowed or allowed not in path.parents:raise ValueError('Report must stay inside output/audit-api-key-chain')
    return path


def finalize_status(report):
    cleanup=report.get('cleanup',{})
    if (not report.get('sourceHashesMatchAtEnd') or not report.get('checks')
            or any(not item.get('passed') for item in report['checks'])
            or any(cleanup.get(name) is not True for name in REQUIRED_CLEANUP)):
        report['status']='failed'


class HttpClient:
    def __init__(self,base,cookies=False,secret_registry=None):
        parsed=urllib.parse.urlparse(base)
        if parsed.scheme!='http' or parsed.hostname!='127.0.0.1' or not parsed.port or parsed.path not in ('','/') or parsed.username:raise ValueError('Only this audit loopback origin is permitted')
        self.base=base.rstrip('/');self.jar=http.cookiejar.CookieJar() if cookies else None;self.secrets=secret_registry if secret_registry is not None else []
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        handlers=[urllib.request.ProxyHandler({}),NoRedirect()]
        if self.jar is not None:handlers.append(urllib.request.HTTPCookieProcessor(self.jar))
        self.opener=urllib.request.build_opener(*handlers)

    def request(self,method,path,body=None,headers=None):
        if not path.startswith('/') or path.startswith('//'):raise ValueError('Relative path required')
        actual={'Accept':'application/json',**(headers or {})}
        data=None if body is None else json.dumps(body).encode('utf-8')
        if data is not None:actual['Content-Type']='application/json'
        request=urllib.request.Request(self.base+path,data=data,headers=actual,method=method)
        try:response=self.opener.open(request,timeout=35)
        except urllib.error.HTTPError as exc:response=exc
        with response:
            raw=response.read(2*1024*1024);status=response.status
        if self.jar is not None:
            self.secrets.extend(cookie.value for cookie in self.jar)
        try:result=json.loads(raw) if raw else None
        except json.JSONDecodeError:result={'nonJsonResponse':True}
        return status,result


def source_hashes():
    paths=[ROOT/'pom.xml',ROOT/'configs/database/services.json',Path(__file__).resolve(),
           ROOT/'scripts/security/GenerateKeys.java',ROOT/'scripts/security/installation.py']
    for module in [*MODULES.values(),'libs/security-protocol','libs/security-servlet']:
        paths.extend([ROOT/module/'pom.xml',*(ROOT/module/'src/main').rglob('*')])
    paths.extend(ROOT.glob('services/*/src/main/resources/db/migration/*.sql'))
    for name in ['contract.py','database_roles.py','runner/pom.xml','runner/src/main/java/com/aetheris/database/DatabaseRunner.java']:
        paths.append(ROOT/'scripts/database'/name)
    return {p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths)) if p.is_file()}


def clean_environment():
    prefixes=('SPRING_','DB_','JWT_','AETHERIS_','REDIS_','INITIAL_ADMIN_','AUTH_SERVICE_','METADATA_SERVICE_','SERVING_SERVICE_','SYSTEM_SERVICE_','WORKFLOW_SERVICE_','QUALITY_SERVICE_','INGESTION_SERVICE_','GOVERNANCE_SERVICE_','AI_AGENT_','SERVER_')
    return {k:v for k,v in os.environ.items() if not k.upper().startswith(prefixes) and k.upper() not in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')}


def reserve_port():
    # Keep delayed Java listeners outside the default client ephemeral ranges.
    # Port 0 can be immediately reused by an outbound connection after releasing the reservation.
    for _ in range(100):
        sock=socket.socket()
        try:
            if hasattr(socket,'SO_EXCLUSIVEADDRUSE'):sock.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
            sock.bind(('127.0.0.1',20000+secrets.randbelow(10000)))
            return sock,sock.getsockname()[1]
        except OSError:sock.close()
    raise AuditFailure('NoOwnedLoopbackServerPortAvailable')


def resolve_java21():
    home=os.environ.get('JAVA_HOME')
    if home:candidate=Path(home)/'bin'/('java.exe' if os.name=='nt' else 'java')
    elif os.name=='nt':candidate=Path('C:/Program Files/Java/jdk-21/bin/java.exe')
    else:candidate=Path(shutil.which('java') or '/missing-java').resolve()
    if not candidate.is_file():raise AuditFailure('Configure JAVA_HOME with an actual JDK 21 installation')
    result=subprocess.run([str(candidate),'-XshowSettings:properties','-version'],capture_output=True,text=True,encoding='utf-8',errors='replace',env=clean_environment(),timeout=15,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    settings=result.stdout+'\n'+result.stderr
    version=re.search(r'^\s*java\.specification\.version\s*=\s*(\S+)\s*$',settings,re.M)
    actual_home=re.search(r'^\s*java\.home\s*=\s*(.+?)\s*$',settings,re.M)
    if result.returncode or not version or version.group(1)!='21' or not actual_home:raise AuditFailure('Real JDK 21 properties could not be verified')
    actual=(Path(actual_home.group(1))/'bin'/('java.exe' if os.name=='nt' else 'java')).resolve()
    if not actual.is_file():raise AuditFailure('Verified JDK 21 executable missing')
    return str(actual)


class Audit:
    def __init__(self,output):
        self.output=validate_output(output);self.run_id=uuid.uuid4().hex
        if self.output.exists():
            previous=json.loads(self.output.read_text(encoding='utf-8'))
            old_id=previous.get('runId','')
            if previous.get('purpose')!='isolated-four-service-api-key-chain' or not re.fullmatch('[0-9a-f]{32}',old_id):raise ValueError('Refusing to replace an unrelated existing report')
            if previous.get('status') in ('passed','failed'):
                old_file=self.output.parent/old_id/'report.json'
                if not old_file.exists():old_file.parent.mkdir(parents=True,exist_ok=True);old_file.write_bytes(self.output.read_bytes())
        self.folder=self.output.parent/self.run_id;self.folder.mkdir(parents=True,exist_ok=False)
        self.containers=[];self.processes=[];self.secrets=[];self.reservations=[];self.base=None
        self.private_root=None;self.private_keys=None;self.key_manifest=None
        self.report={'schemaVersion':1,'purpose':'isolated-four-service-api-key-chain','runId':self.run_id,'startedAtUtc':utc(),'status':'failed','checks':[],'cleanup':dict.fromkeys(REQUIRED_CLEANUP,False)}

    def checkpoint(self,stage):
        self.report['stage']=stage
        if self.report['status']=='failed':self.report['status']='running'
        raw=json.dumps(self.report,ensure_ascii=False,indent=2)+'\n'
        (self.folder/'progress.json').write_text(raw,encoding='utf-8');self.output.write_text(raw,encoding='utf-8')

    def secret(self):
        value=secrets.token_urlsafe(36);self.secrets.append(value);return value

    def command(self,args,*,stdin=None,env=None,timeout=180):
        p=subprocess.run(args,input=stdin,capture_output=True,text=True,encoding='utf-8',errors='replace',env=env,timeout=timeout,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        if p.returncode:raise AuditFailure(Path(str(args[0])).name+' operation failed (raw potentially sensitive output withheld)')
        return p.stdout

    def docker(self,*args,**kwargs):return self.command(['docker',*args],**kwargs)

    def sql(self,sql,json_result=False,database_name='aetheris_api_key_audit'):
        if not self.containers:raise AuditFailure('No audit PostgreSQL container')
        result=self.docker('exec','-i',self.containers[0],'psql','-X','-U','audit_admin','-d',database_name,'-v','ON_ERROR_STOP=1','-At',stdin=sql)
        if json_result:return json.loads(result.strip())
        return result.strip()

    def setup_containers(self):
        self.checkpoint('cached-images')
        self.report['images']={image:self.docker('image','inspect',image,'--format','{{.Id}}').strip() for image in (POSTGRES_IMAGE,REDIS_IMAGE)}
        postgres_password=self.secret();redis_password=self.secret()
        env=clean_environment();env.update(POSTGRES_USER='audit_admin',POSTGRES_DB='aetheris_api_key_audit',POSTGRES_PASSWORD=postgres_password)
        common=['--label','aetheris.api-key-chain.audit='+self.run_id,'--pids-limit','128','--security-opt','no-new-privileges:true']
        cid=self.docker('run','-d','--rm','--name','aetheris-key-pg-'+self.run_id,*common,'--memory','512m','--cpus','1','--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','-p','127.0.0.1::5432','--env','POSTGRES_USER','--env','POSTGRES_DB','--env','POSTGRES_PASSWORD',POSTGRES_IMAGE,env=env).strip();self.containers.append(cid)
        pg_port=self.docker('port',cid,'5432/tcp').strip()
        if not re.fullmatch(r'127\.0\.0\.1:[0-9]+',pg_port):raise AuditFailure('Unexpected PostgreSQL published address')
        env=clean_environment();env['AUDIT_REDIS_PASSWORD']=redis_password
        redis_command='exec redis-server --bind 0.0.0.0 --port 6379 --save "" --appendonly no --requirepass "$AUDIT_REDIS_PASSWORD" --maxmemory 32mb --maxmemory-policy noeviction'
        rid=self.docker('run','-d','--rm','--name','aetheris-key-redis-'+self.run_id,*common,'--memory','96m','--cpus','0.5','--read-only','--cap-drop','ALL','--user','10001:10001','--tmpfs','/data:rw,nosuid,noexec,size=16m,mode=1777','-p','127.0.0.1::6379','--env','AUDIT_REDIS_PASSWORD','--entrypoint','sh',REDIS_IMAGE,'-c',redis_command,env=env).strip();self.containers.append(rid)
        redis_port=self.docker('port',rid,'6379/tcp').strip()
        if not re.fullmatch(r'127\.0\.0\.1:[0-9]+',redis_port):raise AuditFailure('Unexpected Redis published address')
        deadline=time.monotonic()+60
        while True:
            pg=subprocess.run(['docker','exec',cid,'pg_isready','-U','audit_admin','-d','aetheris_api_key_audit'],capture_output=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            if pg.returncode==0:break
            if time.monotonic()>deadline:raise AuditFailure('PostgreSQL readiness deadline')
            time.sleep(.4)
        credentials={s:{'runtime':self.secret(),'migration':self.secret()} for s in database_contract.services().values()}
        self.base={'admin':{'url':'jdbc:postgresql://'+pg_port+'/aetheris_api_key_audit','user':'audit_admin','password':postgres_password},'credentials':credentials}
        self.report['ports']={'postgres':int(pg_port.rsplit(':',1)[1]),'redis':int(redis_port.rsplit(':',1)[1])}
        return redis_password

    def setup_database(self):
        self.checkpoint('source-migrations-runtime-roles')
        database.prepare();database.runner({**self.base,'command':'provision-empty','sql':database.bootstrap()})
        self.report['migrations']=[]
        for service,schema in database_contract.services().items():
            self.report['migrations'].append(database.runner({**self.base,'command':'migrate','schema':schema,'directory':str(ROOT/'services'/service/'src/main/resources/db/migration')}))
        snapshot=database.runner({**self.base,'command':'inventory'});database.check_owned_inventory(snapshot)
        database.runner({**self.base,'command':'sql','sql':'BEGIN;\n'+database_contract.grants_sql(snapshot)+'\nCOMMIT;'})
        database.runner({**self.base,'command':'verify','directories':database.directories()})
        reader_password=self.secret()
        self.sql("CREATE SCHEMA business; CREATE TABLE business.orders(id BIGINT PRIMARY KEY,name TEXT NOT NULL,amount NUMERIC(12,2) NOT NULL); INSERT INTO business.orders VALUES(1,'A',10),(2,'B',20),(3,'C',30); CREATE ROLE audit_business_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+reader_password+"'; GRANT USAGE ON SCHEMA business TO audit_business_reader; GRANT SELECT ON business.orders TO audit_business_reader;")
        rights=self.sql("SELECT json_build_object('platformSchemaPrivileges',"+' OR '.join("has_schema_privilege('audit_business_reader','"+s+"','USAGE')" for s in database_contract.services().values())+",'writePrivileges',has_table_privilege('audit_business_reader','business.orders','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))",json_result=True)
        if rights['platformSchemaPrivileges'] or rights['writePrivileges']:raise AuditFailure('Business reader has unexpected platform/write rights')
        self.report['businessReader']={'schema':'business','table':'orders','role':'audit_business_reader',**rights}
        return reader_password

    def build(self):
        self.checkpoint('offline-four-module-build')
        mvn=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn')
        if not mvn:raise AuditFailure('Maven unavailable')
        self.command([mvn,'-q','-o','-pl',','.join(MODULES.values()),'-am','-DskipTests','clean','package'],env=clean_environment(),timeout=600)
        result={};self.report['artifacts']={}
        for name,module in MODULES.items():
            jars=[p for p in (ROOT/module/'target').glob('*.jar') if not p.name.startswith('original-')]
            if len(jars)!=1:raise AuditFailure('Ambiguous fresh executable jar for '+name)
            path=self.folder/(name+'.jar');shutil.copyfile(jars[0],path);result[name]=path
            self.report['artifacts'][name]={'sourceModule':module,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size}
        return result

    def mapper_it(self):
        self.checkpoint('real-auth-jsonb-mapper-integration')
        env=clean_environment();env.update(AETHERIS_TEST_POSTGRES_URL=self.base['admin']['url'],AETHERIS_TEST_POSTGRES_USER='aetheris_auth',AETHERIS_TEST_POSTGRES_PASSWORD=self.base['credentials']['auth']['runtime'])
        mvn=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn')
        result=subprocess.run([mvn,'-q','-o','-f',str(ROOT/'services/auth/pom.xml'),'-Dtest=ResourceGrantMapperPostgresIT','test'],env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        log=self.folder/'auth-mapper-it.log';log.write_text(redact(result.stdout+'\n'+result.stderr,self.secrets),encoding='utf-8')
        self.report['authMapperIntegration']={'exitCode':result.returncode,'log':str(log.relative_to(ROOT))}
        suite=ROOT/'services/auth/target/surefire-reports/TEST-com.aetheris.auth.mapper.ResourceGrantMapperPostgresIT.xml'
        if suite.is_file():
            tree=ET.parse(suite).getroot()
            self.report['authMapperIntegration']['summary']={key:tree.get(key) for key in ('tests','failures','errors','skipped','time')}
            self.report['authMapperIntegration']['testSourceSha256']=hashlib.sha256((ROOT/'services/auth/src/test/java/com/aetheris/auth/mapper/ResourceGrantMapperPostgresIT.java').read_bytes()).hexdigest()
        if result.returncode:raise AuditFailure('Real PostgreSQL auth grant mapper integration failed')

    def generate_keys(self):
        self.checkpoint('fresh-ephemeral-v2-keys')
        parent=keys_module.PRIVATE.resolve()
        if ROOT.resolve() not in parent.parents:raise AuditFailure('Unsafe private fixture parent')
        parent.mkdir(parents=True,exist_ok=True);owner=parent/('audit-api-key-'+self.run_id)
        owner.mkdir(exist_ok=False);self.private_root=owner;self.private_keys=owner/'keys'
        self.command([self.java,'--source','21',str(ROOT/'scripts/security/GenerateKeys.java'),str(self.private_keys)],env=clean_environment(),timeout=45)
        manifest=json.loads((self.private_keys/'installation.json').read_text(encoding='utf-8'))
        if manifest.get('status')!='generated-not-installed' or len(manifest.get('services',{}))!=10:
            raise AuditFailure('Incomplete fresh key fixture')
        registry=self.private_keys/'public-registry.json';actual=hashlib.sha256(registry.read_bytes()).hexdigest()
        if actual!=manifest.get('registrySha256'):raise AuditFailure('Fresh registry digest mismatch')
        self.key_manifest=manifest;self.report['publicRegistrySha256']=actual
        self.report['keyPreparation']='fresh ephemeral test material; not installed or rotated'

    def start_apps(self,jars,redis_password):
        self.checkpoint('four-real-java-applications')
        slots={name:reserve_port() for name in [*MODULES,'unused']}
        self.reservations.extend(sock for sock,_ in slots.values())
        ports={name:port for name,(_,port) in slots.items()};self.report['ports'].update(ports)
        common=clean_environment();internal=self.secret();jwt=self.secret();master=base64.b64encode(secrets.token_bytes(32)).decode();self.secrets.append(master)
        common.update(SERVER_ADDRESS='127.0.0.1',JWT_SECRET=jwt,AETHERIS_INTERNAL_TOKEN=internal,AETHERIS_DATASOURCE_MASTER_KEY=master,
            DB_HOST='127.0.0.1',DB_PORT=str(self.report['ports']['postgres']),DB_NAME='aetheris_api_key_audit',
            REDIS_HOST='127.0.0.1',REDIS_PORT=str(self.report['ports']['redis']),REDIS_PASSWORD=redis_password,
            AUTH_SERVICE_HOST='127.0.0.1',AUTH_SERVICE_PORT=str(ports['auth']),METADATA_SERVICE_HOST='127.0.0.1',METADATA_SERVICE_PORT=str(ports['metadata']),SERVING_SERVICE_HOST='127.0.0.1',SERVING_SERVICE_PORT=str(ports['serving']),
            SPRING_FLYWAY_ENABLED='false',SPRING_DATASOURCE_HIKARI_MINIMUMIDLE='1',SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE='4',
            SERVER_TOMCAT_THREADS_MAX='20',SERVER_TOMCAT_THREADS_MINSPARE='2',AETHERIS_RATE_LIMIT_CAPACITY='1000',AETHERIS_SERVING_RATE_LIMIT_CAPACITY='1000',AETHERIS_OUTBOUND_ALLOWED_CIDRS='127.0.0.1/32',
            AETHERIS_SECURITY_REGISTRY_FILE=str(self.private_keys/'public-registry.json'))
        for service in ['SYSTEM_SERVICE','WORKFLOW_SERVICE','QUALITY_SERVICE','INGESTION_SERVICE','GOVERNANCE_SERVICE','AI_AGENT']:
            common[service+'_HOST']='127.0.0.1';common[service+'_PORT']=str(ports['unused'])
        self.admin_username='chain_admin_'+self.run_id[:8];self.admin_password=self.secret();common.update(INITIAL_ADMIN_USERNAME=self.admin_username,INITIAL_ADMIN_PASSWORD=self.admin_password)
        self.internal=internal
        for name in ['auth','metadata','serving','gateway']:
            env=common.copy();env['SERVER_PORT']=str(ports[name]);key=self.key_manifest['services'][name]
            env.update(AETHERIS_SECURITY_WORKLOAD_KID=key['workloadKid'],AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE=str(self.private_keys/key['workloadPrivateFile']))
            if name=='gateway':env.update(AETHERIS_SECURITY_USER_KID=key['userKid'],AETHERIS_SECURITY_USER_PRIVATE_FILE=str(self.private_keys/key['userPrivateFile']))
            if name!='gateway':env.update(DB_USER=database_contract.role(name),DB_PASSWORD=self.base['credentials'][name]['runtime'])
            else:env.pop('DB_HOST',None);env.pop('DB_PORT',None);env.pop('DB_NAME',None)
            if name!='metadata':env.pop('AETHERIS_DATASOURCE_MASTER_KEY',None)
            if name!='auth':
                for field in ('INITIAL_ADMIN_USERNAME','INITIAL_ADMIN_PASSWORD','INITIAL_ADMIN_NICKNAME'):env.pop(field,None)
            if name not in ('auth','gateway'):env.pop('JWT_SECRET',None)
            if name!='gateway':
                for field in ('REDIS_HOST','REDIS_PORT','REDIS_PASSWORD'):env.pop(field,None)
            slots[name][0].close()
            log_path=self.folder/(name+'.log');log=log_path.open('wb')
            try:
                process=subprocess.Popen([self.java,*FLAGS,'-Xms32m','-Xmx'+('384m' if name=='metadata' else '256m'),'-XX:MaxMetaspaceSize=192m','-XX:ReservedCodeCacheSize=64m','-XX:ActiveProcessorCount=2','-XX:+ExitOnOutOfMemoryError','-jar',str(jars[name])],cwd=self.folder,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            except BaseException:log.close();raise
            self.processes.append((name,process,log,log_path))
            record={'service':name,'pid':process.pid,'health':'starting','log':str(log_path.relative_to(ROOT)),'springPidMatched':False}
            self.report.setdefault('processes',[]).append(record)
            client=HttpClient('http://127.0.0.1:'+str(ports[name]));deadline=time.monotonic()+100
            while True:
                if process.poll() is not None:raise AuditFailure(name+' exited before health readiness')
                try:
                    status,body=client.request('GET','/actuator/health')
                    if status==200 and isinstance(body,dict) and body.get('status')=='UP':break
                except (OSError,urllib.error.URLError,TimeoutError):pass
                if time.monotonic()>deadline:raise AuditFailure(name+' health readiness deadline')
                time.sleep(.5)
            observed=re.search(r'\bwith PID (\d+)\b',log_path.read_text(encoding='utf-8',errors='replace'))
            if not observed or int(observed.group(1))!=process.pid:raise AuditFailure('Spring PID differs from the directly launched JVM: '+name)
            record.update(health='UP',springPidMatched=True)
        return ports

    def setup_http(self,ports,reader_password):
        self.checkpoint('real-http-fixture')
        base='http://127.0.0.1:'+str(ports['gateway'])
        admin=HttpClient(base,cookies=True,secret_registry=self.secrets)
        status,body=admin.request('POST','/api/auth/login',{'username':self.admin_username,'password':self.admin_password});require_status(status,{200},'bootstrap-admin-login')
        if not admin.jar or not list(admin.jar) or 'token' in body:raise AuditFailure('Expected real HttpOnly cookie login without token body')
        owner='key_owner_'+self.run_id[:8];owner_password=self.secret()
        status,body=admin.request('POST','/api/auth/users',{'username':owner,'password':owner_password,'nickname':'Isolated API key owner','role':'viewer','status':'active'});require_status(status,{201},'create-viewer-owner');owner_id=body['id']
        owner_session=HttpClient(base,cookies=True,secret_registry=self.secrets)
        status,_=owner_session.request('POST','/api/auth/login',{'username':owner,'password':owner_password});require_status(status,{200},'owner-viewer-login')
        keys={}
        for name,quota in [('main',100),('revoked',100),('expired',100),('quota',1)]:
            status,application=owner_session.request('POST','/api/serving/api-key-applications',{'name':'chain-'+name,'justification':'Isolated pure API key acceptance','quotaPerMinute':quota});require_status(status,{200},'request-owner-key-'+name)
            status,issued=admin.request('POST','/api/serving/api-key-applications/'+str(application['id'])+'/approve');require_status(status,{200},'approve-owner-key-'+name)
            keys[name]=issued;self.secrets.append(issued['secret'])
        status,source=admin.request('POST','/api/metadata/datasources',{'name':'chain-business-reader','type':'postgresql','host':'127.0.0.1','port':self.report['ports']['postgres'],'databaseName':'aetheris_api_key_audit','username':'audit_business_reader','password':reader_password,'status':'active'});require_status(status,{200},'register-business-reader-datasource');source_id=source['id']
        status,sync=admin.request('POST','/api/metadata/tables/sync',{'datasourceId':source_id,'schemaName':'business'});require_status(status,{200},'sync-real-business-table')
        if isinstance(sync,dict) and sync.get('failures'):raise AuditFailure('Business table sync returned partial failure')
        status,tables=admin.request('GET','/api/metadata/tables/search?keyword=orders&limit=50');require_status(status,{200},'search-synced-table')
        selected=[t for t in tables if t.get('datasourceId')==source_id and t.get('schemaName')=='business' and t.get('tableName')=='orders']
        if len(selected)!=1:raise AuditFailure('Synced business table not uniquely found')
        table_id=selected[0]['id'];assets={}
        for label in ['allowed','other','draft']:
            code='CHAIN_'+label.upper()+'_'+self.run_id[:8]
            status,asset=admin.request('POST','/api/metadata/api-assets',{'assetCode':code,'assetName':'Isolated chain '+label,'apiPath':'/api/serving/v1/apis/'+code+'/query','httpMethod':'POST','version':'v1','targetType':'table','targetRef':str(table_id)})
            require_status(status,{200},'create-api-'+label);assets[label]=asset
            if label!='draft':status,_=admin.request('POST','/api/metadata/api-assets/'+str(asset['id'])+'/publish');require_status(status,{200},'publish-api-'+label)
        grants={}
        for kind,resource_id,action in [('data_service',assets['allowed']['id'],'execute'),('table',table_id,'query')]:
            status,grant=admin.request('POST','/api/auth/rbac/grants',{'granteeType':'user','granteeId':owner_id,'resourceType':kind,'resourceId':resource_id,'actions':[action],'enabled':True})
            require_status(status,{200,201},'grant-only-'+kind+'-'+action);grants[kind]=grant['id']
        self.report['fixture']={'ownerUsername':owner,'ownerRole':'viewer','ownerId':owner_id,'datasourceId':source_id,'tableId':table_id,'apiAssets':{k:{'id':v['id'],'assetCode':v['assetCode']} for k,v in assets.items()},'explicitGrants':[{'resourceType':'data_service','resourceId':assets['allowed']['id'],'actions':['execute']},{'resourceType':'table','resourceId':table_id,'actions':['query']}],'datasourceQueryGrant':False}
        stored=self.sql("SELECT coalesce(json_agg(json_build_object('resourceType',resource_type,'resourceId',resource_id,'actions',actions,'dataScope',data_scope) ORDER BY id),'[]'::json) FROM auth.resource_grant WHERE grantee_type='user' AND grantee_id="+str(owner_id)+" AND enabled",json_result=True)
        legacy_attempts=[];auth_client=HttpClient('http://127.0.0.1:'+str(ports['auth']))
        for resource,rid,action in [('data_service',assets['allowed']['id'],'execute'),('table',table_id,'query')]:
            status,_=auth_client.request('POST','/internal/permissions/check',{'username':owner,'resourceType':resource,'resourceId':rid,'action':action,'ctx':{}},headers={'X-Internal-Token':self.internal})
            legacy_attempts.append({'resourceType':resource,'httpStatus':status,'rejected':status in (400,401,403)})
        self.report['fixture']['storedGrants']=stored;self.report['fixture']['legacyInternalTokenAttempts']=legacy_attempts
        self.report['checks'].append({'case':'explicit-narrow-grants-stored-and-legacy-direct-auth-denied',
                                      'passed':len(stored)==2 and all(item['rejected'] for item in legacy_attempts)})
        # Every key call uses this separate opener with no cookie processor.
        return admin,HttpClient(base),owner,owner_id,keys,assets,grants,table_id

    def case(self,label,client,method,path,expected,*,body=None,key=None,extra=None,rows=False):
        status,result=client.request(method,path,body,headers=pure_key_headers(key,extra) if key else extra)
        item={'case':label,'httpStatus':status,'passed':True,'pureApiKey':key is not None}
        if status>=400 and isinstance(result,dict) and isinstance(result.get('message'),str):item['errorMessage']=redact(result['message'][:240],self.secrets)
        try:
            require_status(status,expected,label)
            if rows:require_business_rows(result);item['actualBusinessRows']=3
        except AuditFailure as exc:item.update(passed=False,failure=str(exc))
        self.report['checks'].append(item);return status,result

    def scenarios(self,ports,fixture):
        admin,key_client,owner,owner_id,keys,assets,grants,table_id=fixture
        self.checkpoint('pure-api-key-cases')
        path=lambda label:'/api/serving/v1/apis/'+assets[label]['assetCode']+'/query'
        body={'columns':['id','name','amount'],'limit':10};key=keys['main']['secret']
        self.case('pure-key-real-rows-owner-viewer',key_client,'POST',path('allowed'),{200},body=body,key=key,rows=True)
        payload=base64.urlsafe_b64encode((str(owner_id)+'|'+self.admin_username+'|'+str(int(time.time()*1000))).encode()).decode().rstrip('=')
        spoof=payload+'.'+hmac.new(self.internal.encode(),payload.encode(),hashlib.sha256).hexdigest();self.secrets.append(spoof)
        self.case('forged-admin-service-identity-cannot-replace-key-owner',key_client,'POST',path('allowed'),{200},body=body,key=key,extra={'X-Aetheris-Identity':spoof,'X-User-Name':self.admin_username,'X-User-Role':'admin','X-User-Id':str(owner_id),'X-Internal-Token':self.internal},rows=True)
        self.case('missing-key-no-session',key_client,'POST',path('allowed'),{401},body=body)
        self.case('bad-key-no-session',key_client,'POST',path('allowed'),{401},body=body,key='ak_invalid_fixture_never_issued')
        self.case('cross-api-without-execute-grant',key_client,'POST',path('other'),{403},body=body,key=key)
        self.case('unpublished-api-rejected',key_client,'POST',path('draft'),{404},body=body,key=key)
        expired_id=int(keys['expired']['summary']['id']);self.sql('UPDATE serving.service_api_key SET expires_at=now()-interval \'1 second\' WHERE id='+str(expired_id))
        self.case('expired-key-rejected',key_client,'POST',path('allowed'),{401},body=body,key=keys['expired']['secret'])
        status,_=admin.request('POST','/api/serving/api-keys/'+str(keys['revoked']['summary']['id'])+'/revoke');require_status(status,{200},'revoke-key-http')
        self.case('revoked-key-rejected',key_client,'POST',path('allowed'),{401},body=body,key=keys['revoked']['secret'])
        self.case('quota-first-call-real-rows',key_client,'POST',path('allowed'),{200},body=body,key=keys['quota']['secret'],rows=True)
        self.case('quota-second-call-rejected',key_client,'POST',path('allowed'),{429},body=body,key=keys['quota']['secret'])
        for label,method,route in [('key-management-adjacent','GET','/api/serving/api-keys'),('arbitrary-v1-query','POST','/api/serving/v1/query'),('key-route-get','GET',path('allowed')),('key-route-put','PUT',path('allowed')),('key-route-delete','DELETE',path('allowed')),('key-route-trailing-slash','POST',path('allowed')+'/'),('key-route-adjacent-path','POST',path('allowed')+'/admin')]:
            self.case(label,key_client,method,route,{400,401,403,404,405},body=body if method in ('POST','PUT') else None,key=key)
        for resource in ['table','data_service']:
            status,_=admin.request('DELETE','/api/auth/rbac/grants/'+str(grants[resource]));require_status(status,{204},'revoke-'+resource+'-grant')
            self.case('revoke-'+resource+'-permission-immediately-denies',key_client,'POST',path('allowed'),{403},body=body,key=key)
            resource_id=table_id if resource=='table' else assets['allowed']['id'];action='query' if resource=='table' else 'execute'
            status,grant=admin.request('POST','/api/auth/rbac/grants',{'granteeType':'user','granteeId':owner_id,'resourceType':resource,'resourceId':resource_id,'actions':[action],'enabled':True});require_status(status,{200,201},'restore-specific-'+resource+'-grant');grants[resource]=grant['id']
            self.case('restore-'+resource+'-only-recovers',key_client,'POST',path('allowed'),{200},body=body,key=key,rows=True)
        status,_=admin.request('PUT','/api/auth/users/'+str(owner_id),{'role':'viewer','status':'disabled'});require_status(status,{200},'disable-key-owner-http')
        self.case('disabled-owner-key-rejected',key_client,'POST',path('allowed'),{403},body=body,key=key)
        audit=self.sql("SELECT json_build_object('ownerSuccess',count(*) FILTER(WHERE username='"+owner+"' AND status='success' AND row_count=3),'unexpectedSubject',count(*) FILTER(WHERE username<>'"+owner+"')) FROM metadata.semantic_query_audit WHERE query_kind='published_api' AND asset_id="+str(assets['allowed']['id']),json_result=True)
        expected=sum(1 for item in self.report['checks'] if item.get('actualBusinessRows')==3 and item['passed'])
        audit_ok=audit['ownerSuccess']==expected and audit['unexpectedSubject']==0 and expected>=3
        self.report['checks'].append({'case':'metadata-query-audit-bound-to-key-owner','passed':audit_ok,'expectedSuccess':expected,**audit})
        owner_state=self.sql("SELECT json_build_object('role',role,'status',status,'explicitGrantCount',(SELECT count(*) FROM auth.resource_grant WHERE grantee_type='user' AND grantee_id="+str(owner_id)+" AND enabled)) FROM auth.auth_user WHERE id="+str(owner_id),json_result=True)
        self.report['checks'].append({'case':'owner-never-received-grant-all','passed':owner_state['role']=='viewer' and owner_state['explicitGrantCount']==2,**owner_state})
        self.report['status']='passed'

    def cleanup(self):
        process_ok=True;details=[]
        for name,process,log,path in reversed(self.processes):
            try:
                if process.poll() is None:process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
                if process.poll() is None:process_ok=False
            except Exception:process_ok=False
            finally:
                log.close()
                try:path.write_text(redact(path.read_text(encoding='utf-8',errors='replace'),self.secrets),encoding='utf-8')
                except Exception:process_ok=False
            port=self.report.get('ports',{}).get(name)
            closed=True
            if port:
                with socket.socket() as probe:probe.settimeout(.3);closed=probe.connect_ex(('127.0.0.1',port))!=0
            if not closed:process_ok=False
            details.append({'service':name,'pid':process.pid,'exited':process.poll() is not None,'listenerClosed':closed})
        for reservation in self.reservations:reservation.close()
        retired=self.base is None
        if self.base:
            try:database.runner({**self.base,'command':'retire'});retired=True
            except Exception:pass
        container_ok=True;volumes_absent=True
        for cid in reversed(self.containers):
            try:
                label=self.docker('inspect',cid,'--format','{{index .Config.Labels "aetheris.api-key-chain.audit"}}').strip()
                if label!=self.run_id:raise AuditFailure('Container ownership label mismatch')
                details=json.loads(self.docker('inspect',cid))[0]
                if any(item.get('Type')=='volume' for item in details.get('Mounts',[])):volumes_absent=False
                self.docker('rm','-f',cid)
            except Exception:container_ok=False
        if self.containers:
            try:
                if self.docker('ps','-a','--filter','label=aetheris.api-key-chain.audit='+self.run_id,'--format','{{.ID}}').strip():container_ok=False
            except Exception:container_ok=False
        for jar in self.folder.glob('*.jar'):
            deadline=time.monotonic()+5
            while True:
                try:jar.unlink();break
                except OSError:
                    if time.monotonic()>deadline:process_ok=False;break
                    time.sleep(.2)
        removed=self.private_root is None
        if self.private_root is not None:
            try:
                owner=self.private_root.resolve();parent=keys_module.PRIVATE.resolve()
                if owner.parent!=parent or owner.name!='audit-api-key-'+self.run_id or self.private_root.is_symlink():raise ValueError('Private fixture ownership mismatch')
                directory=owner/'keys'
                expected={name+'.workload.pem' for name in ['gateway','auth','system','metadata','serving','quality','ingestion','governance','workflow','ai-agent']}
                expected.update(('gateway.user.pem','public-registry.json','installation.json'))
                if directory.exists():
                    if directory.is_symlink():raise ValueError('Unsafe private fixture directory')
                    actual={item.name for item in directory.iterdir()}
                    if not actual.issubset(expected):raise ValueError('Unexpected private fixture file')
                    for name in actual:
                        item=directory/name
                        if item.is_symlink() or item.resolve().parent!=directory:raise ValueError('Unsafe private fixture path')
                        item.unlink()
                    directory.rmdir()
                owner.rmdir();removed=True
            except Exception:removed=False
        self.report['cleanup']={'processes':process_ok,'containers':container_ok,'credentialsRetired':retired,
                                'privateKeysRemoved':removed,'volumesAbsent':volumes_absent}
        self.report['processCleanup']=details
        self.report['completedAtUtc']=utc();finalize_status(self.report)
        raw=json.dumps(self.report,ensure_ascii=False,indent=2)+'\n';(self.folder/'report.json').write_text(raw,encoding='utf-8');self.output.write_text(raw,encoding='utf-8')

    def run(self):
        self.report['sourceHashes']=source_hashes();self.report['migrationSourceHashes']=database_contract.source_manifest()
        try:
            self.java=resolve_java21();self.report['jdk']={'executable':self.java,'specificationVersion':'21'}
            redis_password=self.setup_containers();reader_password=self.setup_database();self.mapper_it();self.generate_keys();jars=self.build();ports=self.start_apps(jars,redis_password)
            self.scenarios(ports,self.setup_http(ports,reader_password))
            if source_hashes()!=self.report['sourceHashes']:raise AuditFailure('Application/source changed during the audit')
            self.report['sourceHashesMatchAtEnd']=True
        except (Exception,KeyboardInterrupt) as exc:
            self.report['status']='failed';self.report['failure']={'type':type(exc).__name__,'message':redact(str(exc),self.secrets) if isinstance(exc,AuditFailure) else 'Operation failed; inspect sanitized per-service logs'}
        finally:self.cleanup()
        return self.report


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(ROOT/'output/audit-api-key-chain/report.json'));args=parser.parse_args()
    result=Audit(args.output).run()
    print('API key chain audit '+result['status']+': '+str(len(result['checks']))+' real HTTP/DB checks; cleanup='+str(all(result['cleanup'].values())))
    return 0 if result['status']=='passed' else 1


if __name__=='__main__':sys.exit(main())

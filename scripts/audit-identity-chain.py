#!/usr/bin/env python3
"""Disposable real gateway/auth/system v2 segment; no production installation or full S08 claim."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import uuid
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
MODULES={'gateway':'apps/gateway','auth':'services/auth','system':'services/system'}
OUTPUT=ROOT/'output/audit-identity-chain'
FLAGS=['-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.redirects.retrylimit=1']
LABEL='aetheris.identity-chain.audit'
REQUIRED_CLEANUP=frozenset(('processes','containers','credentialsRetired','privateKeysRemoved','volumesAbsent'))
REQUIRED_CASES=frozenset(('admin-cookie-login','http-only-cookie-login-no-token-body','admin-system-configs-through-v2',
    'admin-auth-users-through-v2','admin-create-viewer','viewer-cookie-login','viewer-profile-through-v2','profile-subject-is-viewer',
    'viewer-system-management-denied','viewer-auth-management-denied','auth-direct-forged-identity-denied','system-direct-forged-identity-denied',
    'auth-old-internal-token-denied','system-old-internal-token-denied','gateway-replaces-client-proofs','viewer-forged-proofs-cannot-elevate',
    'viewer-forged-proofs-profile','forged-profile-is-viewer','auth-direct-no-proof-denied','system-direct-no-proof-denied',
    'auth-v2-no-workload-denied','system-v2-no-workload-denied','valid-direct-user-still-needs-management-grant','valid-user-wrong-audience-denied',
    'valid-direct-auth-user-before-disable','disable-viewer','old-cookie-disabled-session-denied','valid-signed-user-disabled-account-denied',
    'enable-viewer-new-access-version','old-proof-access-version-still-denied','new-version-user-proof-recovers',
    'long-request-id-denied','long-request-id-audit-persisted','durable-real-caller-nonces-and-both-audit-streams'))
spec=importlib.util.spec_from_file_location('identity_base',ROOT/'scripts/audit-api-key-chain.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
spec=importlib.util.spec_from_file_location('identity_keys',ROOT/'scripts/security/installation.py')
keys_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(keys_module)


def finalize_status(report):
    checks=report.get('checks',[]);names=[item.get('case') for item in checks]
    cleanup=report.get('cleanup',{})
    if (report.get('status')!='passed' or not report.get('sourceHashesMatchAtEnd')
            or set(names)!=REQUIRED_CASES or len(names)!=len(REQUIRED_CASES)
            or any(item.get('passed') is not True for item in checks)
            or not REQUIRED_CLEANUP.issubset(cleanup) or any(cleanup[name] is not True for name in REQUIRED_CLEANUP)):
        report['status']='failed'


def validate_output(path):
    path=Path(path).resolve()
    if OUTPUT.resolve() not in path.parents or path.suffix!='.json' or path.exists():
        raise ValueError('Use a fresh JSON report inside output/audit-identity-chain')
    return path


def clean_environment():
    return {k:v for k,v in base.clean_environment().items()
            if k.upper() not in ('JAVA_OPTS','MAVEN_OPTS','MAVEN_ARGS','CLASSPATH')}


def source_hashes():
    paths=[Path(__file__).resolve(),ROOT/'pom.xml',ROOT/'scripts/audit-api-key-chain.py',
           ROOT/'scripts/security/GenerateKeys.java',ROOT/'scripts/security/installation.py',ROOT/'scripts/security/SignIsolatedUser.java',
           ROOT/'configs/database/services.json',ROOT/'scripts/database/runner/pom.xml']
    for module in [*MODULES.values(),'libs/security-protocol','libs/security-servlet']:
        paths.extend([ROOT/module/'pom.xml',*(ROOT/module/'src/main').rglob('*')])
    paths.extend(ROOT.glob('services/*/src/main/resources/db/migration/*.sql'))
    paths.extend((ROOT/'scripts/database').rglob('*.py'))
    paths.extend((ROOT/'scripts/database/runner/src/main').rglob('*'))
    return {p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths)) if p.is_file()}


def archive_evidence(source,secrets):
    """Older IT fixtures have already expired, but their JVM property values still stay private."""
    raw=base.redact(source.read_text(encoding='utf-8',errors='replace'),secrets)
    if source.suffix=='.xml':
        root=ET.fromstring(raw)
        for prop in root.findall('properties/property'):
            if re.search(r'password|secret|token|private[-_.]?key|master[-_.]?key',prop.get('name',''),re.I):
                prop.set('value','[REDACTED_HISTORICAL_FIXTURE]')
        raw=ET.tostring(root,encoding='unicode')
    return raw


class Audit(base.Audit):
    def __init__(self,output):
        self.output=validate_output(output);self.run_id=uuid.uuid4().hex
        self.folder=self.output.parent/self.run_id;self.folder.mkdir(parents=True,exist_ok=False)
        self.containers=[];self.processes=[];self.secrets=[];self.reservations=[];self.base=None;self.private_keys=None;self.private_root=None
        self.local_docker_verified=False
        self.report={'schemaVersion':1,'purpose':'isolated-three-service-v2-identity','runId':self.run_id,
                     'startedAtUtc':base.utc(),'status':'failed','checks':[],
                     'cleanup':{name:False for name in REQUIRED_CLEANUP},
                     'limits':'Gateway/auth/system segment only; no browser, metadata, fixed execution or API-key v2 acceptance'}

    def command(self,args,*,stdin=None,env=None,timeout=180):
        args=[str(arg) for arg in args]
        if Path(args[0]).name.lower() in ('mvn','mvn.cmd','mvn.bat'):
            home=Path(args[0]).resolve().parent.parent
            launcher=list((home/'boot').glob('plexus-classworlds-*.jar'))
            if len(launcher)!=1 or not (home/'bin/m2.conf').is_file():raise base.AuditFailure('ReviewedMavenBootstrapMissing')
            args=[self.java,'-classpath',str(launcher[0]),'-Dclassworlds.conf='+str(home/'bin/m2.conf'),
                  '-Dmaven.home='+str(home),'-Dmaven.multiModuleProjectDirectory='+str(ROOT),
                  'org.codehaus.plexus.classworlds.launcher.Launcher',*args[1:]]
        options={'cwd':ROOT,'env':clean_environment() if env is None else env,'stdin':subprocess.PIPE,
                 'stdout':subprocess.PIPE,'stderr':subprocess.PIPE,'text':True,'encoding':'utf-8','errors':'replace'}
        if os.name=='nt':options['creationflags']=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP
        else:options['start_new_session']=True
        process=subprocess.Popen(args,**options)
        try:stdout,stderr=process.communicate(input=stdin,timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name=='nt':subprocess.run(['taskkill.exe','/PID',str(process.pid),'/T','/F'],capture_output=True,timeout=15)
            else:
                import signal
                os.killpg(process.pid,signal.SIGKILL)
            process.communicate(timeout=15);raise base.AuditFailure('OwnedProcessDeadline') from None
        if process.returncode:raise base.AuditFailure(Path(args[0]).name+' operation failed; potentially sensitive output withheld')
        return stdout

    def setup_containers(self):
        self.checkpoint('cached-pinned-owned-fixtures')
        if any(os.environ.get(name) for name in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')):
            raise base.AuditFailure('ExplicitDockerEndpointOverridesNotAllowed')
        context=json.loads(self.docker('context','inspect'))
        if len(context)!=1:raise base.AuditFailure('AmbiguousDockerContext')
        endpoint=context[0].get('Endpoints',{}).get('docker',{}).get('Host','')
        if not (re.fullmatch(r'npipe:/{2,}\./pipe/[A-Za-z0-9_.-]+',endpoint) or re.fullmatch(r'unix:///[^\s]+',endpoint)):
            raise base.AuditFailure('LocalDockerEndpointRequired')
        self.local_docker_verified=True
        self.report['dockerEndpoint']=endpoint
        images={image:json.loads(self.docker('image','inspect',image))[0] for image in (base.POSTGRES_IMAGE,base.REDIS_IMAGE)}
        self.report['images']={name:value['Id'] for name,value in images.items()}
        password=self.secret();redis_password=self.secret()
        env=clean_environment();env.update(POSTGRES_USER='audit_admin',POSTGRES_DB='aetheris_api_key_audit',POSTGRES_PASSWORD=password)
        common=['--pull=never','--label',LABEL+'='+self.run_id,'--pids-limit','128','--security-opt','no-new-privileges:true']
        cid=self.docker('run','-d','--rm','--name','aetheris-identity-pg-'+self.run_id,*common,'--memory','512m','--cpus','1',
            '--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','-p','127.0.0.1::5432','--env','POSTGRES_USER',
            '--env','POSTGRES_DB','--env','POSTGRES_PASSWORD',images[base.POSTGRES_IMAGE]['Id'],env=env).strip();self.containers.append(cid)
        pg_port=self.docker('port',cid,'5432/tcp').strip()
        env=clean_environment();env['AUDIT_REDIS_PASSWORD']=redis_password
        redis_command='exec redis-server --bind 0.0.0.0 --port 6379 --save "" --appendonly no --requirepass "$AUDIT_REDIS_PASSWORD" --maxmemory 32mb --maxmemory-policy noeviction'
        rid=self.docker('run','-d','--rm','--name','aetheris-identity-redis-'+self.run_id,*common,'--memory','96m','--cpus','0.5',
            '--read-only','--cap-drop','ALL','--user','10001:10001','--tmpfs','/data:rw,nosuid,noexec,size=16m,mode=1777',
            '-p','127.0.0.1::6379','--env','AUDIT_REDIS_PASSWORD','--entrypoint','sh',images[base.REDIS_IMAGE]['Id'],'-c',redis_command,env=env).strip();self.containers.append(rid)
        redis_port=self.docker('port',rid,'6379/tcp').strip()
        if not all(re.fullmatch(r'127\.0\.0\.1:[0-9]+',value) for value in (pg_port,redis_port)):raise base.AuditFailure('NonLoopbackFixture')
        for container in [cid,rid]:
            meta=json.loads(self.docker('inspect',container))[0]
            if any(m['Type']=='volume' for m in meta.get('Mounts',[])):raise base.AuditFailure('UnexpectedFixtureVolume')
        deadline=time.monotonic()+60
        while True:
            try:self.docker('exec',cid,'pg_isready','-U','audit_admin','-d','aetheris_api_key_audit',timeout=5);break
            except base.AuditFailure:
                if time.monotonic()>deadline:raise base.AuditFailure('PostgresReadinessDeadline')
                time.sleep(.4)
        self.base={'admin':{'url':'jdbc:postgresql://'+pg_port+'/aetheris_api_key_audit','user':'audit_admin','password':password},
                   'credentials':{s:{'runtime':self.secret(),'migration':self.secret()} for s in base.database_contract.services().values()}}
        self.report['ports']={'postgres':int(pg_port.rsplit(':',1)[1]),'redis':int(redis_port.rsplit(':',1)[1])}
        return redis_password

    def prepare_database(self):
        mvn=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn')
        if not mvn:raise base.AuditFailure('MavenMissing')
        self.command([mvn,'-q','-o','-f',str(base.database.RUNNER/'pom.xml'),'clean','compile',
                      'dependency:build-classpath','-Dmdep.outputFile=target/classpath.txt'],env=clean_environment(),timeout=180)

    def runner(self,request):
        if self.base is None or request.get('admin')!=self.base.get('admin'):
            raise base.AuditFailure('RunnerMustUseThisOwnedFixture')
        if request.get('command') not in {'provision-empty','migrate','inventory','sql','verify','retire'}:
            raise base.AuditFailure('UnexpectedAuditRunnerCommand')
        if request['command']=='inventory':request={**request,'directories':base.database.directories(),
                                                   'sourceHashes':base.database_contract.source_manifest()}
        cp=str(base.database.RUNNER/'target/classes')+os.pathsep+(base.database.RUNNER/'target/classpath.txt').read_text(encoding='utf-8').strip()
        output=self.command([self.java,'-cp',cp,'com.aetheris.database.DatabaseRunner'],stdin=json.dumps(request),
                            env=clean_environment(),timeout=180)
        results=[line[len('AETHERIS_RESULT='):] for line in output.splitlines() if line.startswith('AETHERIS_RESULT=')]
        if len(results)!=1 or any(line.startswith('AETHERIS_ERROR=') for line in output.splitlines()):
            raise base.AuditFailure('ExpectedOneStructuredRunnerResult')
        value=json.loads(results[0])
        if not isinstance(value,dict):raise base.AuditFailure('InvalidRunnerResult')
        return value

    def setup_database(self):
        self.checkpoint('all-116-migrations-and-runtime-roles')
        self.prepare_database();self.runner({**self.base,'command':'provision-empty','sql':base.database.bootstrap()})
        migrations=[]
        for service,schema in base.database_contract.services().items():
            migrations.append(self.runner({**self.base,'command':'migrate','schema':schema,
                'directory':str(ROOT/'services'/service/'src/main/resources/db/migration')}))
        if sum(m['migrationsExecuted'] for m in migrations)!=116:raise base.AuditFailure('Expected116Migrations')
        self.report['migrations']=migrations
        snapshot=self.runner({**self.base,'command':'inventory'});base.database.check_owned_inventory(snapshot)
        self.runner({**self.base,'command':'sql','sql':'BEGIN;\n'+base.database_contract.grants_sql(snapshot)+'\nCOMMIT;'})
        self.runner({**self.base,'command':'verify','directories':base.database.directories()})

    def build(self):
        self.checkpoint('fresh-offline-three-application-build')
        mvn=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn')
        if not mvn:raise base.AuditFailure('MavenMissing')
        archived={}
        for module in [*MODULES.values(),'libs/security-protocol','libs/security-servlet']:
            target=ROOT/module/'target'
            evidence=[*target.glob('*.log'),*target.glob('*.json'),*(target/'surefire-reports').glob('*.xml'),*(target/'surefire-reports').glob('*.txt')]
            for source in evidence:
                destination=self.folder/'unit-evidence'/module/source.relative_to(target)
                destination.parent.mkdir(parents=True,exist_ok=True)
                destination.write_text(archive_evidence(source,self.secrets),encoding='utf-8')
                archived[destination.relative_to(self.folder).as_posix()]=hashlib.sha256(destination.read_bytes()).hexdigest()
        self.report['archivedUnitEvidence']=archived
        self.command([mvn,'-q','-o','-f',str(ROOT/'pom.xml'),'-pl',','.join(MODULES.values()),'-am','-DskipTests','clean','package'],env=clean_environment(),timeout=600)
        result={};self.report['artifacts']={}
        for service,module in MODULES.items():
            jars=list((ROOT/module/'target').glob('*.jar'))
            if len(jars)!=1:raise base.AuditFailure('AmbiguousCurrentJar')
            target=self.folder/(service+'.jar');shutil.copyfile(jars[0],target);result[service]=target
            self.report['artifacts'][service]={'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size}
        return result

    def generate_keys(self):
        parent=keys_module.PRIVATE.resolve()
        if ROOT.resolve() not in parent.parents:raise base.AuditFailure('UnsafePrivateFixtureParent')
        parent.mkdir(parents=True,exist_ok=True);owner=parent/('audit-identity-'+self.run_id)
        owner.mkdir(exist_ok=False)
        self.private_root=owner;self.private_keys=owner/'keys'
        self.command([self.java,'--source','21',str(ROOT/'scripts/security/GenerateKeys.java'),str(self.private_keys)],env=clean_environment(),timeout=45)
        manifest=json.loads((self.private_keys/'installation.json').read_text(encoding='utf-8'))
        if manifest.get('status')!='generated-not-installed' or len(manifest.get('services',{}))!=10:
            raise base.AuditFailure('IncompleteFreshKeyFixture')
        actual=hashlib.sha256((self.private_keys/'public-registry.json').read_bytes()).hexdigest()
        if actual!=manifest.get('registrySha256'):raise base.AuditFailure('FreshRegistryDigestMismatch')
        self.key_manifest=manifest
        self.report['publicRegistrySha256']=manifest['registrySha256']
        self.report['keyPreparation']='fresh ephemeral test material; not installed or rotated'

    def start_apps(self,jars,redis_password):
        self.checkpoint('start-real-auth-system-gateway')
        slots={name:base.reserve_port() for name in [*MODULES,'unused']};self.reservations.extend(sock for sock,_ in slots.values())
        ports={name:port for name,(_,port) in slots.items()};self.report['ports'].update(ports)
        self.admin_username='identity_admin_'+self.run_id[:8];self.admin_password=self.secret()
        common=clean_environment();common.update(SERVER_ADDRESS='127.0.0.1',DB_HOST='127.0.0.1',DB_PORT=str(self.report['ports']['postgres']),
            DB_NAME='aetheris_api_key_audit',JWT_SECRET=self.secret(),REDIS_HOST='127.0.0.1',REDIS_PORT=str(self.report['ports']['redis']),REDIS_PASSWORD=redis_password,
            AUTH_SERVICE_HOST='127.0.0.1',AUTH_SERVICE_PORT=str(ports['auth']),SYSTEM_SERVICE_HOST='127.0.0.1',SYSTEM_SERVICE_PORT=str(ports['system']),
            SPRING_FLYWAY_ENABLED='false',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',
            SERVER_TOMCAT_THREADS_MAX='20',SERVER_TOMCAT_THREADS_MIN_SPARE='2',AETHERIS_RATE_LIMIT_CAPACITY='1000',
            AETHERIS_SECURITY_REGISTRY_FILE=str(self.private_keys/'public-registry.json'))
        for service in ['METADATA_SERVICE','SERVING_SERVICE','WORKFLOW_SERVICE','QUALITY_SERVICE','INGESTION_SERVICE','GOVERNANCE_SERVICE','AI_AGENT']:
            common[service+'_HOST']='127.0.0.1';common[service+'_PORT']=str(ports['unused'])
        for name in ['auth','system','gateway']:
            env=common.copy();env['SERVER_PORT']=str(ports[name]);key=self.key_manifest['services'][name]
            env.update(AETHERIS_SECURITY_WORKLOAD_KID=key['workloadKid'],AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE=str(self.private_keys/key['workloadPrivateFile']))
            if name=='gateway':env.update(AETHERIS_SECURITY_USER_KID=key['userKid'],AETHERIS_SECURITY_USER_PRIVATE_FILE=str(self.private_keys/key['userPrivateFile']))
            else:
                env.update(DB_USER=base.database_contract.role(name),DB_PASSWORD=self.base['credentials'][name]['runtime'])
                for field in ('REDIS_HOST','REDIS_PORT','REDIS_PASSWORD'):env.pop(field,None)
            if name=='auth':env.update(INITIAL_ADMIN_USERNAME=self.admin_username,INITIAL_ADMIN_PASSWORD=self.admin_password)
            if name=='system':env.pop('JWT_SECRET',None)
            slots[name][0].close();log_path=self.folder/(name+'.log');log=log_path.open('wb')
            try:process=subprocess.Popen([self.java,*FLAGS,'-Xms32m','-Xmx256m','-XX:MaxMetaspaceSize=192m','-XX:ReservedCodeCacheSize=64m',
                    '-XX:ActiveProcessorCount=2','-XX:+ExitOnOutOfMemoryError','-jar',str(jars[name])],cwd=self.folder,env=env,stdout=log,stderr=subprocess.STDOUT,
                    creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            except BaseException:log.close();raise
            self.processes.append((name,process,log,log_path));record={'service':name,'pid':process.pid,'health':'starting','springPidMatched':False}
            self.report.setdefault('processes',[]).append(record);client=base.HttpClient('http://127.0.0.1:'+str(ports[name]));deadline=time.monotonic()+100
            while True:
                if process.poll() is not None:raise base.AuditFailure(name+' exited before health readiness')
                try:
                    status,body=client.request('GET','/actuator/health')
                    if status==200 and isinstance(body,dict) and body.get('status')=='UP':break
                except (OSError,urllib.error.URLError,TimeoutError):pass
                if time.monotonic()>deadline:raise base.AuditFailure(name+' health readiness deadline')
                time.sleep(.5)
            observed=re.search(r'\bwith PID (\d+)\b',log_path.read_text(encoding='utf-8',errors='replace'))
            if not observed or int(observed.group(1))!=process.pid:raise base.AuditFailure('DirectJavaPidMismatch')
            record.update(health='UP',springPidMatched=True)
        return ports

    def user_proof(self,uid,username,av,audience):
        dependencies=[]
        with zipfile.ZipFile(self.folder/'gateway.jar') as jar:
            for artifact in ['security-protocol','jackson-databind','jackson-annotations','jackson-core']:
                names=[name for name in jar.namelist() if name.startswith('BOOT-INF/lib/'+artifact+'-') and name.endswith('.jar')]
                if len(names)!=1:raise base.AuditFailure('AmbiguousFreshProbeDependency')
                target=self.folder/('probe-'+artifact+'.jar');target.write_bytes(jar.read(names[0]));dependencies.append(str(target))
        cp=os.pathsep.join(dependencies)
        key=self.key_manifest['services']['gateway'];body={'registry':str(self.private_keys/'public-registry.json'),'workloadKid':key['workloadKid'],
            'workloadFile':str(self.private_keys/key['workloadPrivateFile']),'userKid':key['userKid'],'userFile':str(self.private_keys/key['userPrivateFile']),
            'uid':uid,'username':username,'accessVersion':av,'audience':audience}
        result=self.command([self.java,'--class-path',cp,'--source','21',str(ROOT/'scripts/security/SignIsolatedUser.java')],stdin=json.dumps(body),env=clean_environment(),timeout=30).strip()
        if not re.fullmatch('[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',result):raise base.AuditFailure('InvalidFreshUserFixture')
        self.secrets.append(result);return result

    def check(self,label,passed,**details):
        self.report['checks'].append({'case':label,'passed':bool(passed),**details})
        if not passed:raise base.AuditFailure(label)

    def http(self,label,client,method,path,expected,body=None,headers=None):
        status,result=client.request(method,path,body,headers);self.check(label,status in expected,httpStatus=status);return result

    def scenarios(self,ports):
        self.checkpoint('v2-real-http-and-postgres-boundaries')
        gateway='http://127.0.0.1:'+str(ports['gateway']);admin=base.HttpClient(gateway,True,self.secrets)
        login=self.http('admin-cookie-login',admin,'POST','/api/auth/login',{200},{'username':self.admin_username,'password':self.admin_password})
        self.check('http-only-cookie-login-no-token-body',bool(list(admin.jar)) and all(c.has_nonstandard_attr('HttpOnly') for c in admin.jar)
                   and isinstance(login,dict) and not any(key in login for key in ('token','accessToken','refreshToken')))
        self.http('admin-system-configs-through-v2',admin,'GET','/api/system/configs',{200})
        self.http('admin-auth-users-through-v2',admin,'GET','/api/auth/users',{200})
        viewer='identity_viewer_'+self.run_id[:8];password=self.secret()
        record=self.http('admin-create-viewer',admin,'POST','/api/auth/users',{201},{'username':viewer,'password':password,'role':'viewer','status':'active','nickname':'Isolated identity viewer'})
        uid=int(record['id']);session=base.HttpClient(gateway,True,self.secrets)
        self.http('viewer-cookie-login',session,'POST','/api/auth/login',{200},{'username':viewer,'password':password})
        me=self.http('viewer-profile-through-v2',session,'GET','/api/auth/me',{200});self.check('profile-subject-is-viewer',me.get('username')==viewer and 'passwordHash' not in me)
        self.http('viewer-system-management-denied',session,'GET','/api/system/configs',{403})
        self.http('viewer-auth-management-denied',session,'GET','/api/auth/users',{403})
        request_id=('trace.'+self.run_id+'.').ljust(128,'x')
        self.http('long-request-id-denied',session,'GET','/api/system/configs',{403},headers={'X-Request-Id':request_id})
        deadline=time.monotonic()+8;recorded=0
        while time.monotonic()<deadline:
            recorded=int(self.sql("SELECT count(*) FROM system.permission_event WHERE username='"+viewer+"' AND request_id='"+request_id+"' AND decision='DENIED'"))
            if recorded:break
            time.sleep(.2)
        self.check('long-request-id-audit-persisted',recorded>0,requestIdLength=128,rows=recorded)
        forged={'X-Aetheris-Identity':'legacy.invalid','X-User-Name':self.admin_username,'X-User-Id':'1','X-User-Role':'admin',
                'X-Internal-Token':'unused-legacy-token','X-Aetheris-User-Proof':'forged','X-Aetheris-Workload-Proof':'forged','X-Aetheris-Response-Proof':'forged'}
        direct={name:base.HttpClient('http://127.0.0.1:'+str(ports[name])) for name in ['auth','system']}
        for name,path in [('auth','/api/auth/users'),('system','/api/system/configs')]:
            self.http(name+'-direct-forged-identity-denied',direct[name],'GET',path,{401,403},headers=forged)
            self.http(name+'-direct-no-proof-denied',direct[name],'GET',path,{401})
        for name,path in [('auth','/internal/permissions/version'),('system','/internal/audit/logs')]:
            self.http(name+'-old-internal-token-denied',direct[name],'POST',path,{400,401,403,404},{},forged)
        self.http('gateway-replaces-client-proofs',admin,'GET','/api/system/configs',{200},headers=forged)
        self.http('viewer-forged-proofs-cannot-elevate',session,'GET','/api/system/configs',{403},headers=forged)
        profile=self.http('viewer-forged-proofs-profile',session,'GET','/api/auth/me',{200},headers=forged)
        self.check('forged-profile-is-viewer',profile.get('username')==viewer)
        self.http('auth-v2-no-workload-denied',direct['auth'],'POST','/internal/v2/sessions/check',{401},
                  {'uid':uid,'username':viewer,'accessVersion':0})
        self.http('system-v2-no-workload-denied',direct['system'],'POST','/internal/v2/audit/logs',{401},{})
        av=int(self.sql('SELECT access_version FROM auth.auth_user WHERE id='+str(uid)))
        user=self.user_proof(uid,viewer,av,'system');headers={'X-Aetheris-User-Proof':user}
        self.http('valid-direct-user-still-needs-management-grant',direct['system'],'GET','/api/system/configs',{403},headers=headers)
        wrong=self.user_proof(uid,viewer,av,'metadata')
        self.http('valid-user-wrong-audience-denied',direct['system'],'GET','/api/system/configs',{401,403},headers={'X-Aetheris-User-Proof':wrong})
        auth_user=self.user_proof(uid,viewer,av,'auth');auth_headers={'X-Aetheris-User-Proof':auth_user}
        self.http('valid-direct-auth-user-before-disable',direct['auth'],'GET','/api/auth/me',{200},headers=auth_headers)
        self.http('disable-viewer',admin,'PUT','/api/auth/users/'+str(uid),{200},{'status':'disabled','role':'viewer'})
        self.http('old-cookie-disabled-session-denied',session,'GET','/api/auth/me',{401})
        self.http('valid-signed-user-disabled-account-denied',direct['auth'],'GET','/api/auth/me',{403},headers=auth_headers)
        self.http('enable-viewer-new-access-version',admin,'PUT','/api/auth/users/'+str(uid),{200},{'status':'active','role':'viewer'})
        self.http('old-proof-access-version-still-denied',direct['auth'],'GET','/api/auth/me',{403},headers=auth_headers)
        new_av=int(self.sql('SELECT access_version FROM auth.auth_user WHERE id='+str(uid)))
        if new_av<=av:raise base.AuditFailure('AccessVersionDidNotAdvance')
        self.http('new-version-user-proof-recovers',direct['auth'],'GET','/api/auth/me',{200},
                  headers={'X-Aetheris-User-Proof':self.user_proof(uid,viewer,new_av,'auth')})
        deadline=time.monotonic()+8
        while True:
            counts=self.sql("SELECT json_build_object('authGatewayNonce',(SELECT count(*) FROM auth.internal_request_nonce WHERE caller='gateway'),'authSystemNonce',(SELECT count(*) FROM auth.internal_request_nonce WHERE caller='system'),'systemGatewayNonce',(SELECT count(*) FROM system.internal_request_nonce WHERE caller='gateway'),'systemAuthNonce',(SELECT count(*) FROM system.internal_request_nonce WHERE caller='auth'),'auditRows',(SELECT count(*) FROM system.audit_log),'permissionEvents',(SELECT count(*) FROM system.permission_event))",True)
            if all(int(v)>0 for v in counts.values()) or time.monotonic()>deadline:break
            time.sleep(.2)
        expected={'authGatewayNonce','authSystemNonce','systemGatewayNonce','systemAuthNonce','auditRows','permissionEvents'}
        self.check('durable-real-caller-nonces-and-both-audit-streams',set(counts)==expected and all(int(v)>0 for v in counts.values()),counts=counts)

    def cleanup(self):
        process_ok=True;details=[]
        for name,process,log,log_path in reversed(self.processes):
            try:
                if process.poll() is None:process.terminate();process.wait(timeout=8)
            except Exception:
                try:process.kill();process.wait(timeout=5)
                except Exception:process_ok=False
            log.close()
            try:log_path.write_text(base.redact(log_path.read_text(encoding='utf-8',errors='replace'),self.secrets),encoding='utf-8')
            except Exception:process_ok=False
            closed=True;port=self.report.get('ports',{}).get(name)
            if port:
                import socket
                with socket.socket() as probe:probe.settimeout(.3);closed=probe.connect_ex(('127.0.0.1',port))!=0
            exited=process.poll() is not None;process_ok=process_ok and exited and closed
            details.append({'service':name,'pid':process.pid,'exited':exited,'listenerClosed':closed})
        for reservation in self.reservations:reservation.close()
        retired=self.base is None
        if self.base:
            try:self.runner({**self.base,'command':'retire'});retired=True
            except Exception:retired=False
        container_ok=True;volumes_absent=True
        try:
            if not getattr(self,'local_docker_verified',False):
                if self.containers:raise base.AuditFailure('UnverifiedFixtureState')
                discovered=[]
            else:discovered=self.docker('ps','-a','--filter','label='+LABEL+'='+self.run_id,'--format','{{.ID}}').split()
            for cid in discovered:
                info=json.loads(self.docker('inspect',cid))[0]
                if info.get('Config',{}).get('Labels',{}).get(LABEL)!=self.run_id:raise base.AuditFailure('ContainerOwnershipMismatch')
                if any(m.get('Type')=='volume' for m in info.get('Mounts',[])):volumes_absent=False
                self.docker('rm','-f',cid)
            if getattr(self,'local_docker_verified',False):
                container_ok=not self.docker('ps','-a','--filter','label='+LABEL+'='+self.run_id,'--format','{{.ID}}').strip()
        except Exception:container_ok=False
        owned_root=getattr(self,'private_root',None)
        removed=owned_root is None
        if owned_root is not None:
            try:
                owner=owned_root.resolve();parent=keys_module.PRIVATE.resolve()
                if owner.parent!=parent or owner.name!='audit-identity-'+self.run_id or owned_root.is_symlink():raise ValueError('Private fixture ownership mismatch')
                directory=owner/'keys'
                names={s+'.workload.pem' for s in ['gateway','auth','system','metadata','serving','quality','ingestion','governance','workflow','ai-agent']}
                names.update(['gateway.user.pem','public-registry.json','installation.json'])
                if directory.exists():
                    if directory.is_symlink():raise ValueError('Unsafe private fixture directory')
                    for name in names:
                        path=directory/name
                        if path.exists():
                            if path.is_symlink() or path.resolve().parent!=directory:raise ValueError('Unsafe private fixture path')
                            path.unlink()
                    directory.rmdir()
                owner.rmdir();removed=True
            except Exception:removed=False
        for jar in self.folder.glob('*.jar'):
            try:jar.unlink()
            except OSError:process_ok=False
        self.report['cleanup']={'processes':process_ok,'containers':container_ok,'credentialsRetired':retired,
                                'privateKeysRemoved':removed,'volumesAbsent':volumes_absent}
        self.report['processCleanup']=details;self.report['completedAtUtc']=base.utc();finalize_status(self.report)
        raw=json.dumps(self.report,ensure_ascii=False,indent=2)+'\n';(self.folder/'report.json').write_text(raw,encoding='utf-8');self.output.write_text(raw,encoding='utf-8')

    def run(self):
        self.report['sourceHashes']=source_hashes();self.report['migrationSourceHashes']=base.database_contract.source_manifest()
        try:
            self.java=base.resolve_java21();self.report['jdk']={'executable':self.java,'specificationVersion':'21'}
            redis=self.setup_containers();self.setup_database();self.generate_keys();jars=self.build();ports=self.start_apps(jars,redis)
            self.scenarios(ports)
            if source_hashes()!=self.report['sourceHashes']:raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd']=True;self.report['status']='passed'
        except (Exception,KeyboardInterrupt) as exc:
            self.report['status']='failed';self.report['failure']={'type':type(exc).__name__,
                'message':base.redact(str(exc),self.secrets) if isinstance(exc,base.AuditFailure) else 'Operation failed; inspect sanitized logs'}
        finally:self.cleanup()
        return self.report


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(OUTPUT/uuid.uuid4().hex/'report.json'))
    result=Audit(parser.parse_args().output).run();print('Identity segment '+result['status']+'; checks='+str(len(result['checks']))+'; cleanup='+str(all(result['cleanup'].values())))
    return 0 if result['status']=='passed' else 1


if __name__=='__main__':sys.exit(main())

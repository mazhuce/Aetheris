#!/usr/bin/env python3
"""Disposable PG proof of query audit storage, scope, privacy and failure metrics; no installed service changes."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, re, secrets, shutil, sys, time, uuid
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-query-observability'
LABEL='aetheris.query-observability.audit'
DB='aetheris_query_audit'
TEST='com.aetheris.metadata.service.QueryAuditPostgresIT'
spec=importlib.util.spec_from_file_location('query_observability_process',ROOT/'scripts/audit-database-deploy-gate.py')
process=importlib.util.module_from_spec(spec);spec.loader.exec_module(process)
CLEANUP=frozenset(('ownedJavaExited','ownedContainersAbsent','volumesAbsent','listenerClosed'))

def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def source_hashes():
    paths=[Path(__file__).resolve(),ROOT/'scripts/audit-database-deploy-gate.py',ROOT/'services/metadata/pom.xml',
           ROOT/'services/metadata/src/test/java/com/aetheris/metadata/service/QueryAuditPostgresIT.java',
           ROOT/'services/metadata/src/test/java/com/aetheris/metadata/security/MetadataProofFixture.java']
    for module in ('services/metadata','libs/security-protocol','libs/security-servlet'):
        paths.extend((ROOT/module/'src/main').rglob('*'))
    return {p.relative_to(ROOT).as_posix():digest(p) for p in sorted(set(paths)) if p.is_file()}

def output_path(value):
    path=Path(value).resolve()
    if OUTPUT.resolve() not in path.parents or path.suffix!='.json' or path.exists():
        raise ValueError('Use a fresh JSON report inside output/audit-query-observability')
    return path

def clean_environment(source):
    return {k:v for k,v in process.clean_environment(source).items()
            if not k.upper().startswith(('OPENAI_','ANTHROPIC_','AZURE_OPENAI_'))
            and k.upper() not in ('MAVEN_ARGS','CLASSPATH')}

def finalize(report):
    return 'passed' if (report.get('testExitCode')==0 and report.get('sourceHashesMatchAtEnd') is True
        and report.get('freshTestXml') is True and report.get('tests')=={'tests':4,'failures':0,'errors':0,'skipped':0}
        and set(report.get('cleanup',{}))==CLEANUP and all(v is True for v in report['cleanup'].values())
        and report.get('compiledClassHashes') and re.fullmatch('sha256:[a-f0-9]{64}',report.get('imageId',''))
        and report.get('testReportSha256')) else 'failed'

def owned_java(run_id,env):
    if not re.fullmatch('[a-f0-9]{32}',run_id):raise ValueError('Invalid run ID')
    marker='-Daetheris.query.observability.audit='+run_id
    script='Get-CimInstance Win32_Process -Filter "Name = \'java.exe\'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains(\''+marker+'\') } | ForEach-Object { $_.ProcessId }'
    result=process.run_command(['powershell.exe','-NoProfile','-NonInteractive','-Command',script],env=env,timeout=20)
    return [int(value) for value in result.stdout.split()]

def audit(destination):
    destination=output_path(destination);destination.parent.mkdir(parents=True,exist_ok=True)
    run_id=uuid.uuid4().hex;folder=destination.parent/run_id;folder.mkdir(exist_ok=False)
    report={'schemaVersion':1,'runId':run_id,'purpose':'isolated-query-audit-observability',
            'startedAtUtc':process.utc(),'status':'failed','cleanup':{key:False for key in sorted(CLEANUP)},
            'limits':'Only metadata 32 actual Flyway migrations and four PG JDBC/MyBatis tests, including real V31-to-V32 upgrade, stable UID and bounded personal history deletion. Auth global decisions are separate MVC/signature fixtures; no actual auth lifecycle, retention scheduler, alert delivery, multi-application or release claim.'}
    env=None;verified=False;cid=None;port=None;secret=secrets.token_urlsafe(32)
    try:
        if os.name!='nt':raise ValueError('Reviewed Windows JDK/process cleanup required')
        if any(os.environ.get(k) for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')):
            raise ValueError('Docker endpoint overrides are not permitted')
        env=process.java_environment(clean_environment(os.environ));env.update(POSTGRES_USER='audit_admin',POSTGRES_PASSWORD=secret,POSTGRES_DB=DB)
        report['sourceHashes']=source_hashes()
        context=json.loads(process.run_command(['docker','context','inspect'],env=env,timeout=15).stdout)
        if len(context)!=1:raise ValueError('Ambiguous Docker context')
        endpoint=context[0].get('Endpoints',{}).get('docker',{}).get('Host','')
        if not (re.fullmatch(r'npipe:/{2,}\./pipe/[A-Za-z0-9_.-]+',endpoint) or re.fullmatch(r'unix:///[^\s]+',endpoint)):
            raise ValueError('Local Docker context required')
        verified=True;report['dockerEndpoint']=endpoint
        image=json.loads(process.run_command(['docker','image','inspect','postgres:16-alpine'],env=env,timeout=15).stdout)[0]
        report['imageId']=image['Id']
        cid=process.run_command(['docker','run','-d','--rm','--pull=never','--label',LABEL+'='+run_id,
            '--name','aetheris-query-audit-'+run_id,'--memory','128m','--cpus','1','--pids-limit','64',
            '--security-opt','no-new-privileges:true','--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=192m',
            '-p','127.0.0.1::5432','--env','POSTGRES_USER','--env','POSTGRES_PASSWORD','--env','POSTGRES_DB',
            image['Id'],'-c','shared_buffers=16MB','-c','max_connections=20'],env=env,timeout=30).stdout.strip()
        if not re.fullmatch('[a-f0-9]{64}',cid):raise ValueError('Invalid container ID')
        info=json.loads(process.run_command(['docker','inspect',cid],env=env,timeout=15).stdout)[0]
        if info.get('Config',{}).get('Labels',{}).get(LABEL)!=run_id or any(m.get('Type')!='tmpfs' for m in info.get('Mounts',[])):
            raise ValueError('Owned tmpfs-only fixture required')
        port=process.run_command(['docker','port',cid,'5432/tcp'],env=env,timeout=15).stdout.strip()
        if not re.fullmatch(r'127\.0\.0\.1:[0-9]+',port):raise ValueError('Loopback-only port required')
        report['postgresPort']=int(port.rsplit(':',1)[1]);deadline=time.monotonic()+45
        while process.run_command(['docker','exec',cid,'pg_isready','-U','audit_admin','-d',DB],env=env,timeout=10,allow_failure=True).returncode:
            if time.monotonic()>deadline:raise ValueError('PG readiness deadline')
            time.sleep(.25)
        env.update(AETHERIS_QUERY_AUDIT_URL='jdbc:postgresql://'+port+'/'+DB,AETHERIS_QUERY_AUDIT_PASSWORD=secret)
        java=Path(env['JAVA_HOME'])/'bin/java.exe';report['javaExecutableSha256']=digest(java)
        maven=Path(shutil.which('mvn.cmd',path=env['PATH']) or 'C:/Program Files/apache-maven-3.9.10/bin/mvn.cmd').resolve().parent.parent
        boot=list((maven/'boot').glob('plexus-classworlds-*.jar'))
        if len(boot)!=1:raise ValueError('Reviewed Maven bootstrap required')
        marker='-Daetheris.query.observability.audit='+run_id
        flags='-Xmx256m -XX:MaxMetaspaceSize=160m '+marker+' -Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1'
        report['jvmLimits']={'mavenHeapMiB':192,'testHeapMiB':256,'metaspaceMiB':160,'postgresMemoryMiB':128}
        suite=ROOT/'services/metadata/target/surefire-reports'/('TEST-'+TEST+'.xml')
        before=digest(suite) if suite.exists() else None;started=time.time_ns()
        result=process.run_command([java,'-Xmx192m','-XX:MaxMetaspaceSize=160m',marker,
            '-Dmaven.home='+str(maven),'-Dmaven.conf='+str(maven/'conf'),'-Dclassworlds.conf='+str(maven/'bin/m2.conf'),
            '-Dmaven.multiModuleProjectDirectory='+str(ROOT),'-cp',boot[0],'org.codehaus.plexus.classworlds.launcher.Launcher',
            '-B','-o','-f',ROOT/'services/metadata/pom.xml','-Dtest=QueryAuditPostgresIT','-DargLine='+flags,'test'],env=env,timeout=240,allow_failure=True)
        (folder/'postgres-it.log').write_text((result.stdout+'\n'+result.stderr).replace(secret,'[REDACTED]'),encoding='utf-8')
        report['testExitCode']=result.returncode
        if not suite.is_file() or suite.stat().st_mtime_ns<started or digest(suite)==before:raise ValueError('Fresh test XML required')
        raw=suite.read_text(encoding='utf-8');xml=ET.fromstring(raw)
        if xml.attrib.get('name')!=TEST:raise ValueError('Unexpected test suite')
        report['freshTestXml']=True;report['tests']={key:int(xml.attrib[key]) for key in ('tests','failures','errors','skipped')}
        (folder/'test-report.xml').write_text(raw.replace(secret,'[REDACTED]'),encoding='utf-8');report['testReportSha256']=digest(folder/'test-report.xml')
        classes=[ROOT/'services/metadata/target/classes/com/aetheris/metadata'/p for p in
                 ('service/QueryAuditService.class','controller/QueryAuditController.class','security/MetadataSecurity.class','security/MetadataAccessExceptionHandler.class',
                  'service/QueryHistoryService.class','controller/QueryHistoryController.class','dto/QueryHistoryResponse.class','security/QueryRequestContext.class')]
        report['compiledClassHashes']={p.relative_to(ROOT).as_posix():digest(p) for p in classes}
        report['sourceHashesMatchAtEnd']=source_hashes()==report['sourceHashes']
    except Exception as failure:
        report['failure']={'type':type(failure).__name__,'message':'Isolated audit failed; inspect sanitized log'}
    finally:
        if env is not None:
            try:
                pids=owned_java(run_id,env)
                for pid in pids:process.run_command(['taskkill.exe','/PID',str(pid),'/T','/F'],env=env,timeout=15)
                report['cleanupAftercarePids']=pids;report['cleanup']['ownedJavaExited']=not owned_java(run_id,env)
            except Exception:pass
        if verified:
            try:
                found=process.run_command(['docker','ps','-aq','--no-trunc','--filter','label='+LABEL+'='+run_id],env=env,timeout=15).stdout.split()
                volumes_absent=True
                for identity in found:
                    info=json.loads(process.run_command(['docker','inspect',identity],env=env,timeout=15).stdout)[0]
                    if info.get('Config',{}).get('Labels',{}).get(LABEL)!=run_id:raise ValueError('Ownership mismatch')
                    if any(m.get('Type')=='volume' for m in info.get('Mounts',[])):volumes_absent=False
                    process.run_command(['docker','rm','-f',identity],env=env,timeout=20)
                report['cleanup']['ownedContainersAbsent']=not process.run_command(['docker','ps','-aq','--filter','label='+LABEL+'='+run_id],env=env,timeout=15).stdout.strip()
                report['cleanup']['volumesAbsent']=volumes_absent
                if port:
                    import socket
                    with socket.socket() as probe:
                        probe.settimeout(1);report['cleanup']['listenerClosed']=probe.connect_ex(('127.0.0.1',report['postgresPort']))!=0
            except Exception:pass
        report['completedAtUtc']=process.utc();report['status']=finalize(report)
        raw=json.dumps(report,ensure_ascii=False,indent=2)+'\n';(folder/'report.json').write_text(raw,encoding='utf-8');destination.write_text(raw,encoding='utf-8')
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(OUTPUT/uuid.uuid4().hex/'report.json'))
    report=audit(parser.parse_args().output)
    print('Query observability '+report['status']+'; tests='+str(report.get('tests',{}))+'; cleanup='+str(all(report['cleanup'].values())))
    sys.exit(0 if report['status']=='passed' else 1)

#!/usr/bin/env python3
"""Real disposable PostgreSQL definition/seal/receipt constraints; never touches installed databases."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, re, secrets, shutil, subprocess, sys, time, uuid
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-fixed-execution-storage'
LABEL='aetheris.fixed-execution-storage.audit'
DB='aetheris_fixed_storage_audit'
TEST='com.aetheris.metadata.service.FixedExecutionStoragePostgresIT'
spec=importlib.util.spec_from_file_location('fixed_storage_process',ROOT/'scripts/audit-database-deploy-gate.py')
processes=importlib.util.module_from_spec(spec);spec.loader.exec_module(processes)


def source_hashes():
    paths=[Path(__file__),ROOT/'scripts/audit-database-deploy-gate.py',ROOT/'services/metadata/pom.xml',
           ROOT/'services/metadata/src/test/java/com/aetheris/metadata/service/FixedExecutionStoragePostgresIT.java']
    paths.extend(ROOT.glob('services/*/src/main/resources/db/migration/*.sql'))
    paths.extend((ROOT/'scripts/database').rglob('*.py'))
    paths.extend((ROOT/'services/metadata/src/main').rglob('*'))
    paths.append(ROOT/'configs/database/services.json')
    paths.extend((ROOT/'libs/security-protocol/src/main').rglob('*.java'))
    paths.extend((ROOT/'libs/security-servlet/src/main').rglob('*.java'))
    return {p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths)) if p.is_file()}


def finalize(report):
    tests=report.get('tests',{})
    cleanup=report.get('cleanup',{})
    report['status']='passed' if (report.get('testExitCode')==0 and report.get('sourceHashesMatchAtEnd') is True
        and tests=={'tests':4,'failures':0,'errors':0,'skipped':0}
        and cleanup=={'ownedJavaExited':True,'containersRemoved':True,'volumesAbsent':True}) else 'failed'


def output_path(value):
    path=Path(value).resolve()
    if OUTPUT.resolve() not in path.parents or path.suffix!='.json' or path.exists():raise ValueError('Use a fresh JSON inside output/audit-fixed-execution-storage')
    return path


def owned_java(run_id,env):
    if os.name!='nt':raise ValueError('This reviewed process cleanup requires Windows')
    if not re.fullmatch('[a-f0-9]{32}',run_id):raise ValueError('Invalid run ID')
    marker='-Daetheris.fixed.storage.audit='+run_id
    script="Get-CimInstance Win32_Process -Filter \"Name = 'java.exe'\" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('"+marker+"') } | ForEach-Object { $_.ProcessId }"
    result=processes.run_command(['powershell.exe','-NoProfile','-NonInteractive','-Command',script],env=env,timeout=20)
    return [int(value) for value in result.stdout.splitlines() if value.strip()]


def audit(destination):
    destination=output_path(destination);destination.parent.mkdir(parents=True,exist_ok=True)
    run_id=uuid.uuid4().hex;folder=destination.parent/run_id;folder.mkdir(exist_ok=False)
    report={'schemaVersion':1,'purpose':'isolated-fixed-execution-storage','runId':run_id,'startedAtUtc':processes.utc(),
            'status':'failed','cleanup':{'ownedJavaExited':False,'containersRemoved':False,'volumesAbsent':False},
            'limits':'Storage constraints only; no full application execution, source lookup, auth or JDBC side effect claim'}
    cid=None;verified=False;env=None;secret=secrets.token_urlsafe(32)
    try:
        env=processes.java_environment();env.update(POSTGRES_USER='audit_admin',POSTGRES_DB=DB,POSTGRES_PASSWORD=secret)
        report['sourceHashes']=source_hashes()
        if any(os.environ.get(key) for key in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')):raise ValueError('Docker endpoint overrides not permitted')
        context=json.loads(processes.run_command(['docker','context','inspect'],env=env,timeout=15).stdout)
        if len(context)!=1:raise ValueError('Ambiguous Docker context')
        endpoint=context[0].get('Endpoints',{}).get('docker',{}).get('Host','')
        if not (re.fullmatch(r'npipe:/{2,}\./pipe/[A-Za-z0-9_.-]+',endpoint) or re.fullmatch(r'unix:///[^\s]+',endpoint)):raise ValueError('Local Docker required')
        verified=True;report['dockerEndpoint']=endpoint
        image=json.loads(processes.run_command(['docker','image','inspect','postgres:16-alpine'],env=env,timeout=15).stdout)[0]
        report['imageId']=image['Id']
        result=processes.run_command(['docker','run','-d','--rm','--pull=never','--name','aetheris-fixed-storage-'+run_id,
            '--label',LABEL+'='+run_id,'--memory','512m','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true',
            '--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','-p','127.0.0.1::5432',
            '--env','POSTGRES_USER','--env','POSTGRES_DB','--env','POSTGRES_PASSWORD',image['Id']],env=env,timeout=30)
        cid=result.stdout.strip()
        if not re.fullmatch('[a-f0-9]{64}',cid):raise ValueError('Invalid container ID')
        port=processes.run_command(['docker','port',cid,'5432/tcp'],env=env,timeout=15).stdout.strip()
        if not re.fullmatch(r'127\.0\.0\.1:[0-9]+',port):raise ValueError('Loopback fixture required')
        info=json.loads(processes.run_command(['docker','inspect',cid],env=env,timeout=15).stdout)[0]
        if any(m.get('Type')!='tmpfs' for m in info.get('Mounts',[])):raise ValueError('Only tmpfs fixture mounts permitted')
        report['postgresPort']=int(port.rsplit(':',1)[1])
        deadline=time.monotonic()+45
        while processes.run_command(['docker','exec',cid,'pg_isready','-U','audit_admin','-d',DB],env=env,timeout=10,allow_failure=True).returncode:
            if time.monotonic()>deadline:raise ValueError('PostgreSQL readiness deadline')
            time.sleep(.25)
        env.update(AETHERIS_FIXED_STORAGE_URL='jdbc:postgresql://'+port+'/'+DB,AETHERIS_FIXED_STORAGE_PASSWORD=secret)
        java=Path(env['JAVA_HOME'])/'bin/java.exe';maven=Path(shutil.which('mvn.cmd') or 'C:/Program Files/apache-maven-3.9.10/bin/mvn.cmd').resolve().parent.parent
        boot=list((maven/'boot').glob('plexus-classworlds-*.jar'))
        if len(boot)!=1:raise ValueError('Reviewed Maven bootstrap missing')
        marker='-Daetheris.fixed.storage.audit='+run_id
        command=[java,marker,'-Dmaven.home='+str(maven),'-Dmaven.conf='+str(maven/'conf'),'-Dclassworlds.conf='+str(maven/'bin/m2.conf'),
            '-Dmaven.multiModuleProjectDirectory='+str(ROOT),'-cp',boot[0],'org.codehaus.plexus.classworlds.launcher.Launcher',
            '-B','-o','-f',ROOT/'services/metadata/pom.xml','-Dtest=FixedExecutionStoragePostgresIT',
            '-DargLine='+marker+' -Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1','test']
        suite=ROOT/'services/metadata/target/surefire-reports'/('TEST-'+TEST+'.xml')
        before=hashlib.sha256(suite.read_bytes()).hexdigest() if suite.exists() else None
        result=processes.run_command(command,env=env,timeout=180,allow_failure=True)
        (folder/'postgres-it.log').write_text((result.stdout+'\n'+result.stderr).replace(secret,'[REDACTED]'),encoding='utf-8')
        report['testExitCode']=result.returncode
        if suite.exists():
            raw=suite.read_bytes();digest=hashlib.sha256(raw).hexdigest()
            if before==digest:raise ValueError('Stale test XML cannot pass')
            xml=ET.fromstring(raw)
            if xml.attrib.get('name')!=TEST:raise ValueError('Unexpected test suite')
            report['tests']={key:int(xml.attrib[key]) for key in ('tests','failures','errors','skipped')}
            # Surefire XML contains environment credentials: store a redacted copy, never raw XML.
            (folder/'test-report.xml').write_text(raw.decode('utf-8').replace(secret,'[REDACTED]'),encoding='utf-8')
            report['testReportSha256']=hashlib.sha256((folder/'test-report.xml').read_bytes()).hexdigest()
        report['sourceHashesMatchAtEnd']=source_hashes()==report['sourceHashes']
    except Exception as error:
        report['failure']={'type':type(error).__name__,'message':'Isolated audit failed; inspect sanitized logs'}
    finally:
        if env is not None:
            try:
                pids=owned_java(run_id,env)
                for pid in pids:processes.run_command(['taskkill.exe','/PID',str(pid),'/T','/F'],env=env,timeout=15)
                report['cleanup']['ownedJavaExited']=not owned_java(run_id,env);report['cleanupAftercarePids']=pids
            except Exception:pass
        if verified:
            try:
                discovered=processes.run_command(['docker','ps','-a','--no-trunc','--filter','label='+LABEL+'='+run_id,'--format','{{.ID}}'],env=env,timeout=15).stdout.split()
                volume_free=True
                for identity in discovered:
                    meta=json.loads(processes.run_command(['docker','inspect',identity],env=env,timeout=15).stdout)[0]
                    if meta.get('Config',{}).get('Labels',{}).get(LABEL)!=run_id:raise ValueError('Container ownership mismatch')
                    if any(m.get('Type')=='volume' for m in meta.get('Mounts',[])):volume_free=False
                    processes.run_command(['docker','rm','-f',identity],env=env,timeout=20)
                report['cleanup']['containersRemoved']=not processes.run_command(['docker','ps','-a','--filter','label='+LABEL+'='+run_id,'--format','{{.ID}}'],env=env,timeout=15).stdout.strip()
                report['cleanup']['volumesAbsent']=volume_free
            except Exception:pass
        report['completedAtUtc']=processes.utc();finalize(report)
        raw=json.dumps(report,ensure_ascii=False,indent=2)+'\n';(folder/'report.json').write_text(raw,encoding='utf-8');destination.write_text(raw,encoding='utf-8')
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(OUTPUT/uuid.uuid4().hex/'report.json'))
    report=audit(parser.parse_args().output)
    print('Fixed execution storage '+report['status']+'; tests='+str(report.get('tests',{}))+'; cleanup='+str(all(report['cleanup'].values())))
    sys.exit(0 if report['status']=='passed' else 1)

#!/usr/bin/env python3
"""Disposable PostgreSQL business READ/WRITE credential acceptance; no existing DB access."""
from __future__ import annotations
import argparse,base64,hashlib,json,os,re,secrets,shutil,subprocess,sys,time,uuid
from pathlib import Path
from datetime import datetime,timezone
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
IMAGE='postgres:16-alpine'
LABEL='aetheris.datasource-credentials.audit'
DB='aetheris_ds_credentials_audit'

def utc():return datetime.now(timezone.utc).isoformat()
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def redact(raw,values):
    for value in sorted(values,key=len,reverse=True):raw=raw.replace(value,'[REDACTED]')
    return raw
def output_path(value):
    path=Path(value).resolve();allowed=(ROOT/'output/audit-datasource-credentials').resolve()
    if allowed not in path.parents:raise ValueError('Output must stay in the audit output directory')
    return path
def source_hashes():
    files=[Path(__file__),ROOT/'services/metadata/pom.xml',*ROOT.glob('services/metadata/src/main/**/*'),*ROOT.glob('services/metadata/src/test/**/*.java')]
    return {p.relative_to(ROOT).as_posix():digest(p) for p in sorted(set(files)) if p.is_file()}
def valid_port(raw):
    if not re.fullmatch(r'127\.0\.0\.1:[0-9]+',raw):raise ValueError('Only random loopback publishing is permitted')
    return int(raw.rsplit(':',1)[1])
def finalize(report):
    report['status']='passed' if report.get('testExitCode')==0 and report.get('tests',{}).get('tests')==1 and not any(report['tests'].get(k,0) for k in ('failures','errors','skipped')) and report.get('sourceHashesMatchAtEnd') and report.get('cleanup',{}).get('containerRemoved') and report.get('cleanup',{}).get('ownedJavaExited') else 'failed'
def run(args,env=None,timeout=60):
    result=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',env=env,timeout=timeout,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    if result.returncode:raise RuntimeError('Audit command failed; potentially sensitive output withheld')
    return result.stdout.strip()

def owned_java(run_id):
    if os.name!='nt':raise RuntimeError('This audit currently requires the reviewed Windows process cleanup implementation')
    marker='-Daetheris.ds.credentials.audit='+run_id
    script="Get-CimInstance Win32_Process -Filter \"Name = 'java.exe'\" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('"+marker+"') } | ForEach-Object { $_.ProcessId }"
    raw=run(['powershell.exe','-NoProfile','-NonInteractive','-Command',script])
    return [int(value) for value in raw.splitlines() if value.strip()]

def audit(destination):
    destination=output_path(destination);run_id=uuid.uuid4().hex;folder=destination.parent/run_id;folder.mkdir(parents=True,exist_ok=False)
    report={'schemaVersion':1,'purpose':'isolated-business-datasource-credentials','runId':run_id,'startedAtUtc':utc(),'status':'running','cleanup':{'containerRemoved':False,'ownedJavaExited':False}}
    cid=None;values=[];process=None
    try:
        report['sourceHashes']=source_hashes()
        java_home=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21'))
        java=java_home/'bin/java.exe' if os.name=='nt' else java_home/'bin/java'
        properties=subprocess.run([str(java),'-XshowSettings:properties','-version'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        settings=properties.stdout+properties.stderr
        if properties.returncode or not re.search(r'java\.specification\.version\s*=\s*21\s',settings):raise RuntimeError('Actual JDK21 is required')
        actual=re.search(r'java\.home\s*=\s*(.+)',settings)
        if not actual:raise RuntimeError('Actual java.home unavailable')
        java_home=Path(actual.group(1).strip());report['jdk']={'home':str(java_home),'specificationVersion':'21'}
        report['imageId']=run(['docker','image','inspect',IMAGE,'--format','{{.Id}}'])
        admin,reader,writer=[secrets.token_urlsafe(32) for _ in range(3)];master=base64.b64encode(secrets.token_bytes(32)).decode();values=[admin,reader,writer,master]
        env={k:v for k,v in os.environ.items() if not k.upper().startswith(('AETHERIS_','SPRING_','DB_')) and k.upper() not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
        env.update(JAVA_HOME=str(java_home),POSTGRES_USER='audit_admin',POSTGRES_PASSWORD=admin,POSTGRES_DB=DB)
        cid=run(['docker','run','-d','--rm','--name','aetheris-ds-credentials-'+run_id,'--label',LABEL+'='+run_id,'--memory','512m','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true','--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','-p','127.0.0.1::5432','--env','POSTGRES_USER','--env','POSTGRES_PASSWORD','--env','POSTGRES_DB',IMAGE],env=env)
        port=valid_port(run(['docker','port',cid,'5432/tcp']));report['postgresPort']=port
        deadline=time.monotonic()+45
        while True:
            ready=subprocess.run(['docker','exec',cid,'pg_isready','-U','audit_admin','-d',DB],capture_output=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            if ready.returncode==0:break
            if time.monotonic()>deadline:raise RuntimeError('PostgreSQL readiness deadline')
            time.sleep(.3)
        env.update(AETHERIS_DS_AUDIT_URL=f'jdbc:postgresql://127.0.0.1:{port}/{DB}',AETHERIS_DS_AUDIT_ADMIN_PASSWORD=admin,AETHERIS_DS_AUDIT_READ_PASSWORD=reader,AETHERIS_DS_AUDIT_WRITE_PASSWORD=writer,AETHERIS_DS_AUDIT_MASTER_KEY=master)
        destination.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        maven=shutil.which('mvn.cmd') or shutil.which('mvn') or 'C:/Program Files/apache-maven-3.9.10/bin/mvn.cmd'
        maven_home=Path(maven).resolve().parent.parent;boot=list((maven_home/'boot').glob('plexus-classworlds-*.jar'))
        if len(boot)!=1:raise RuntimeError('Maven Java bootstrap classpath unavailable')
        marker='-Daetheris.ds.credentials.audit='+run_id
        command=[str(java_home/'bin/java.exe'),marker,'-Dmaven.home='+str(maven_home),'-Dmaven.conf='+str(maven_home/'conf'),'-Dclassworlds.conf='+str(maven_home/'bin/m2.conf'),'-Dmaven.multiModuleProjectDirectory='+str(ROOT),'-cp',str(boot[0]),'org.codehaus.plexus.classworlds.launcher.Launcher','-B','-o','-f',str(ROOT/'services/metadata/pom.xml'),'-Dtest=DataSourceCredentialsPostgresIT','-DargLine='+marker,'test']
        process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',errors='replace',env=env,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        report['mavenJavaPid']=process.pid
        stdout,stderr=process.communicate(timeout=300)
        (folder/'postgres-it.log').write_text(redact(stdout+'\n'+stderr,values),encoding='utf-8');report['testExitCode']=process.returncode
        suite=ROOT/'services/metadata/target/surefire-reports/TEST-com.aetheris.metadata.service.DataSourceCredentialsPostgresIT.xml'
        if suite.exists():
            xml=ET.parse(suite).getroot();report['tests']={k:int(xml.attrib[k]) for k in ('tests','failures','errors','skipped')}
            report['testReportSha256']=digest(suite)
        report['sourceHashesMatchAtEnd']=source_hashes()==report['sourceHashes']
    except Exception as error:report['failure']={'type':type(error).__name__,'message':redact(str(error),values)}
    finally:
        try:
            if process and process.poll() is None:
                run(['taskkill','/PID',str(process.pid),'/T','/F']);process.wait(timeout=15)
            residual=owned_java(run_id)
            for pid in residual:run(['taskkill','/PID',str(pid),'/T','/F'])
            report['cleanup']['ownedJavaExited']=not owned_java(run_id)
            report['cleanupAftercarePids']=residual
        except Exception:pass
        try:
            owned=set(run(['docker','ps','-a','--no-trunc','--filter','label='+LABEL+'='+run_id,'--format','{{.ID}}']).splitlines())
            if cid:owned.add(cid)
            for identity in owned:
                if run(['docker','inspect',identity,'--format','{{index .Config.Labels "'+LABEL+'"}}'])!=run_id:raise RuntimeError('Container ownership mismatch')
                run(['docker','rm','-f',identity])
            report['cleanup']['containerRemoved']=not run(['docker','ps','-a','--filter','label='+LABEL+'='+run_id,'--format','{{.ID}}'])
        except Exception:pass
        report['completedAtUtc']=utc();finalize(report)
        raw=json.dumps(report,ensure_ascii=False,indent=2)+'\n';(folder/'report.json').write_text(raw,encoding='utf-8');destination.write_text(raw,encoding='utf-8')
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(ROOT/'output/audit-datasource-credentials/report.json'));options=parser.parse_args()
    result=audit(options.output);print('Datasource credentials audit '+result['status']+'; owned container cleanup='+str(result['cleanup']['containerRemoved']));sys.exit(0 if result['status']=='passed' else 1)

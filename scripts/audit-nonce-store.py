#!/usr/bin/env python3
"""Disposable PG nonce acceptance; --with-servlet-wire explicitly adds two real Tomcat receivers."""
from __future__ import annotations
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
MODULE=ROOT/'libs/security-servlet'
OUTPUT=ROOT/'output/audit-nonce-store'
IMAGE='postgres:16-alpine'
LABEL='aetheris.nonce.store.audit'
DATABASE='aetheris_nonce_audit'
SUITE='com.aetheris.security.servlet.PostgresNonceStoreIT'
METHOD='actualDatabaseClaimsAreAtomicDurableAndBoundedAcrossTwoReceivers'
WIRE_SUITE='com.aetheris.security.servlet.TomcatProofWireIT'
WIRE_METHOD='actualTomcatWireProofsSharedNonceDirectIdentityAndHttpBoundaries'


class AuditFailure(RuntimeError):pass


def utc():return datetime.now(timezone.utc).isoformat()


def clean_environment(source=None):
    source=os.environ if source is None else source
    prefixes=('SPRING_','DB_','JWT_','AETHERIS_','POSTGRES_','COMPOSE_','DEEPSEEK_','REDIS_','INITIAL_ADMIN_')
    forbidden={'JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','JAVA_OPTS','CLASSPATH'}
    return {k:v for k,v in source.items() if not k.upper().startswith(prefixes) and k.upper() not in forbidden}


def call(args,env,timeout=30,allow_failure=False):
    options={'cwd':ROOT,'env':env,'stdout':subprocess.PIPE,'stderr':subprocess.PIPE,'text':True,'encoding':'utf-8','errors':'replace'}
    if os.name=='nt':options['creationflags']=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP
    else:options['start_new_session']=True
    process=subprocess.Popen([str(x) for x in args],**options)
    try:stdout,stderr=process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        if os.name=='nt':subprocess.run(['taskkill.exe','/PID',str(process.pid),'/T','/F'],capture_output=True,timeout=15)
        else:os.killpg(process.pid,signal.SIGKILL)
        process.communicate();raise AuditFailure('OwnedProcessTimeout') from None
    result=subprocess.CompletedProcess(args,process.returncode,stdout,stderr)
    if result.returncode and not allow_failure:raise AuditFailure('CommandFailed')
    return result


def environment21():
    home=Path(os.environ.get('JAVA_HOME',''))
    if not home.is_absolute() or not home.is_dir():raise AuditFailure('AbsoluteJavaHomeRequired')
    home=home.resolve();java=home/'bin'/('java.exe' if os.name=='nt' else 'java')
    if not java.is_file() or not java.with_name('javac.exe' if os.name=='nt' else 'javac').is_file():raise AuditFailure('Jdk21Required')
    env=clean_environment();path=env.get('PATH',env.get('Path',''))
    for key in list(env):
        if key.upper()=='PATH':del env[key]
    env.update(JAVA_HOME=str(home),PATH=str(home/'bin')+os.pathsep+os.pathsep.join(p for p in path.split(os.pathsep) if 'javapath' not in p.lower()),PYTHONDONTWRITEBYTECODE='1')
    version=call([java,'-XshowSettings:properties','-version'],env,15)
    if not re.search(r'java\.specification\.version\s*=\s*21\s',version.stderr):raise AuditFailure('Jdk21Required')
    runtime=re.search(r'java\.runtime\.version\s*=\s*([^\r\n]+)',version.stderr)
    mvn=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn',path=env['PATH'])
    if not mvn:raise AuditFailure('MavenRequired')
    return env,mvn,runtime.group(1).strip() if runtime else '21'


def valid_output(path):
    path=Path(path).resolve()
    if path.exists() or OUTPUT.resolve() not in path.parents:raise ValueError('New output directory inside audit-nonce-store required')
    return path


def fixture_url(port):
    if type(port) is not int or not 1<=port<=65535:raise ValueError('Invalid owned PostgreSQL port')
    return 'jdbc:postgresql://127.0.0.1:'+str(port)+'/'+DATABASE


def hashes(paths):
    value={}
    for path in sorted(paths):
        if not path.is_file() or path.is_symlink():raise AuditFailure('UnexpectedSourceOrArtifact')
        value[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    return value


def source_hashes():
    paths=[MODULE/'pom.xml',*MODULE.glob('src/**/*.java'),*MODULE.glob('src/main/resources/**/*'),
           ROOT/'libs/security-protocol/pom.xml',*ROOT.glob('libs/security-protocol/src/main/**/*.java'),
           Path(__file__).resolve(),ROOT/'scripts/tests/test_nonce_store_audit.py']
    return hashes([p for p in paths if p.is_file()])


def artifact_hashes():
    paths=[*(MODULE/'target/classes').rglob('*'),*(MODULE/'target/test-classes').rglob('*')]
    paths=[p for p in paths if p.is_file()]
    if not paths or not (MODULE/'target/test-classes/com/aetheris/security/servlet/PostgresNonceStoreIT.class').is_file():raise AuditFailure('FrozenCompiledItRequired')
    return hashes(paths)


def verify_xml(path,started_ns,suite_name=SUITE,method=METHOD):
    if not path.is_file() or path.stat().st_mtime_ns<started_ns:raise AuditFailure('FreshSurefireXmlRequired')
    suite=ET.parse(path).getroot();cases=suite.findall('testcase')
    if suite.get('name')!=suite_name or int(suite.get('tests','0'))!=1 or any(int(suite.get(k,'0')) for k in ('failures','errors','skipped')) or len(cases)!=1:
        raise AuditFailure('ExactlyOneSuccessfulItRequired')
    test=cases[0]
    if test.get('classname')!=suite_name or test.get('name')!=method or any(test.find(name) is not None for name in ('failure','error','skipped')):
        raise AuditFailure('ExpectedItMethodRequired')
    return {'suite':suite_name,'method':method,'tests':1,'failures':0,'errors':0,'skipped':0,'seconds':float(suite.get('time','0'))}


def expected_suites(with_servlet_wire=False):
    return [(SUITE,METHOD)]+([(WIRE_SUITE,WIRE_METHOD)] if with_servlet_wire else [])


def verify_wire_evidence(xml):
    outputs='\n'.join(node.text or '' for node in ET.parse(xml).getroot().iter('system-out'))
    markers=re.findall(r'^AETHERIS_SERVLET_WIRE_RESULT=(\{[^\r\n]*\})$',outputs,re.M)
    if len(markers)!=1:raise AuditFailure('ExactlyOneRealServletEvidenceRequired')
    value=json.loads(markers[0])
    expected={'tomcatVersion':'10.1.42.0','servers':2,'duplicateRequests':24,'duplicateWinners':1,
              'duplicateSignedConflicts':23,'nonceRows':5,'portsClosed':True,'threadsStopped':True,
              'ownedTempRemoved':True,'sessionValidator':'local-test-state'}
    if value!=expected:raise AuditFailure('UnexpectedRealServletEvidence')
    deadlines=re.findall(r'^AETHERIS_SERVLET_WIRE_DEADLINE=(header|body):(closed|400|408)$',outputs,re.M)
    if len(deadlines)!=2 or [kind for kind,_ in deadlines]!=['header','body']:raise AuditFailure('ExactlyTwoInputDeadlineObservationsRequired')
    value['inputDeadlineOutcomes']={kind:outcome for kind,outcome in deadlines}
    return value


def observed_database(value,with_servlet_wire=False):
    if value.get('database')!=DATABASE or value.get('rows') not in (6,7,8) or value.get('schemas')!=(['auth','metadata'] if with_servlet_wire else ['metadata']):
        raise AuditFailure('UnexpectedFixtureDatabaseState')
    expiry=value.get('expiryGroups',[])
    if len(expiry)!=3 or expiry[0]['count'] not in (2,3,4) or [x['count'] for x in expiry[1:]]!=[1,3] \
            or expiry[1]['expiresAt']-expiry[0]['expiresAt']!=82801 or expiry[2]['expiresAt']-expiry[1]['expiresAt']!=3630 \
            or sum(x['count'] for x in expiry)!=value['rows']:
        raise AuditFailure('NonceSqlTimeOrRetentionMismatch')
    if value.get('runtimeInsert') is not True or value.get('runtimeUpdate') is not False or value.get('readerSelect') is not True or value.get('readerInsert') is not False or value.get('readerDelete') is not False:
        raise AuditFailure('UnexpectedRuntimeOrReaderPrivileges')
    if with_servlet_wire and (value.get('wireRows')!=5 or value.get('wireRuntimeUpdate') is not False or value.get('wireRuntimeInsert') is not True):
        raise AuditFailure('UnexpectedRealServletDatabaseState')
    return value


def run(output=None,with_servlet_wire=False):
    run_id=uuid.uuid4().hex;directory=valid_output(output or OUTPUT/run_id);directory.mkdir(parents=True)
    name='aetheris-nonce-audit-'+run_id;network='aetheris-nonce-net-'+run_id
    report={'schemaVersion':1,'runId':run_id,'purpose':'real-pg-nonce-store-and-mock-servlet-acceptance',
            'status':'failed','startedAtUtc':utc(),'cleanup':{},'servletEnvironment':'Spring mock servlet; not Tomcat'}
    if with_servlet_wire:report.update(purpose='real-pg-and-embedded-tomcat-library-acceptance',servletEnvironment='Two embedded Tomcat 10.1.42 root contexts; local SessionValidator double')
    report['withServletWire']=with_servlet_wire
    env=clean_environment();cid=None;network_id=None;failure=None;stage='preflight'
    try:
        env,mvn,jdk=environment21();report['jdkRuntimeVersion']=jdk;report['jdkSpecificationVersion']='21'
        report['sourceHashes']=source_hashes();report['artifactHashes']=artifact_hashes()
        image=json.loads(call(['docker','image','inspect',IMAGE],env).stdout)[0]
        report['imageRequested']=IMAGE;report['imageId']=image['Id'];report['repoDigests']=image.get('RepoDigests',[])
        network_id=call(['docker','network','create','--driver','bridge','--label',LABEL+'='+run_id,network],env).stdout.strip()
        password=secrets.token_urlsafe(36)
        container_env=dict(env,POSTGRES_USER='nonce_admin',POSTGRES_DB=DATABASE,POSTGRES_PASSWORD=password)
        stage='start-owned-postgres'
        cid=call(['docker','run','-d','--rm','--name',name,'--label',LABEL+'='+run_id,'--network',network,
                  '--pull','never','--memory','512m','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true',
                  '--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','-p','127.0.0.1::5432',
                  '--env','POSTGRES_USER','--env','POSTGRES_DB','--env','POSTGRES_PASSWORD',image['Id']],container_env,60).stdout.strip()
        info=json.loads(call(['docker','inspect',cid],env).stdout)[0]
        if info['Config']['Labels'].get(LABEL)!=run_id or set(info['NetworkSettings']['Networks'])!={network} or any(m.get('Type')!='tmpfs' for m in info.get('Mounts',[])):
            raise AuditFailure('ContainerIsolationMismatch')
        ports=info['NetworkSettings']['Ports'].get('5432/tcp')
        if not ports or len(ports)!=1 or ports[0]['HostIp']!='127.0.0.1':raise AuditFailure('LoopbackPublishRequired')
        url=fixture_url(int(ports[0]['HostPort']));report['containerId']=cid;report['networkId']=network_id
        deadline=time.monotonic()+60
        while True:
            probe=call(['docker','exec',cid,'pg_isready','-U','nonce_admin','-d',DATABASE],env,10,True)
            if probe.returncode==0:break
            if time.monotonic()>deadline:raise AuditFailure('PostgresReadinessTimeout')
            time.sleep(.5)
        stage='explicit-real-postgres-it'
        test_env=dict(env,AETHERIS_TEST_NONCE_POSTGRES_URL=url,AETHERIS_TEST_NONCE_POSTGRES_PASSWORD=password)
        started_ns=time.time_ns()
        suites=expected_suites(with_servlet_wire)
        selected=','.join(name.rsplit('.',1)[1] for name,_ in suites)
        result=call([mvn,'-q','-o','-f',MODULE/'pom.xml','-Dtest='+selected,'test'],test_env,180,True)
        # Sanitize the generated fixture secret even on a failed SQL assertion. Never show raw output in console.
        log=(result.stdout+'\n'+result.stderr).replace(password,'[REDACTED_FIXTURE_SECRET]')
        (directory/'maven.log').write_text(log,encoding='utf-8');report['mavenExitCode']=result.returncode
        xml=MODULE/'target/surefire-reports'/('TEST-'+SUITE+'.xml')
        if xml.is_file() and xml.stat().st_mtime_ns>=started_ns:
            raw_suite=ET.parse(xml).getroot()
            report['observedItCounters']={key:int(raw_suite.get(key,'0')) for key in ('tests','failures','errors','skipped')}
            report['surefireXmlSha256']=hashlib.sha256(xml.read_bytes()).hexdigest()
            # Preserve failed evidence too, without allowing a target rebuild to replace it.
            xml_text=xml.read_text(encoding='utf-8');report['surefireEvidenceRedacted']=password in xml_text
            (directory/'surefire.xml').write_text(xml_text.replace(password,'[REDACTED_FIXTURE_SECRET]'),encoding='utf-8')
            report['savedSurefireXmlSha256']=hashlib.sha256((directory/'surefire.xml').read_bytes()).hexdigest()
        report['surefireSuites']=[]
        for suite_name,_ in suites:
            evidence=MODULE/'target/surefire-reports'/('TEST-'+suite_name+'.xml')
            if evidence.is_file() and evidence.stat().st_mtime_ns>=started_ns:
                content=evidence.read_text(encoding='utf-8').replace(password,'[REDACTED_FIXTURE_SECRET]')
                saved=directory/(suite_name.rsplit('.',1)[1]+'.xml');saved.write_text(content,encoding='utf-8')
                parsed=ET.fromstring(content)
                report['surefireSuites'].append({'suite':suite_name,'counters':{key:int(parsed.get(key,'0')) for key in ('tests','failures','errors','skipped')},'savedXmlSha256':hashlib.sha256(saved.read_bytes()).hexdigest()})
        report['sourceHashesMatchAtEnd']=source_hashes()==report['sourceHashes']
        report['artifactHashesMatchAtEnd']=artifact_hashes()==report['artifactHashes']
        if result.returncode!=0:raise AuditFailure('MavenItFailed')
        report['tests']=verify_xml(xml,started_ns)
        report['verifiedSuites']=[verify_xml(MODULE/'target/surefire-reports'/('TEST-'+name+'.xml'),started_ns,name,method) for name,method in suites]
        if with_servlet_wire:report['servletWire']=verify_wire_evidence(MODULE/'target/surefire-reports'/('TEST-'+WIRE_SUITE+'.xml'))
        dependencies=[]
        props={p.get('name'):p.get('value') for p in ET.parse(xml).getroot().findall('properties/property')}
        for text in (props.get('java.class.path') or '').split(os.pathsep):
            p=Path(text)
            if p.suffix=='.jar' and p.is_file():dependencies.append({'name':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
        report['dependencyJars']=dependencies
        stage='observe-real-database'
        query="""SELECT json_build_object('database',current_database(),'databaseOid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
          'systemIdentifier',(SELECT system_identifier::text FROM pg_control_system()),
          'schemas',(SELECT json_agg(nspname ORDER BY nspname) FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND nspname NOT LIKE 'pg_%'),
          'rows',(SELECT count(*) FROM metadata.internal_request_nonce),
          'expiryGroups',(SELECT json_agg(x ORDER BY x.\"expiresAt\") FROM (SELECT expires_at AS \"expiresAt\",count(*) AS count FROM metadata.internal_request_nonce GROUP BY expires_at) x),
          'runtimeInsert',has_table_privilege('nonce_runtime','metadata.internal_request_nonce','INSERT'),
          'runtimeUpdate',has_table_privilege('nonce_runtime','metadata.internal_request_nonce','UPDATE'),
          'readerSelect',has_table_privilege('nonce_reader','metadata.internal_request_nonce','SELECT'),
          'readerInsert',has_table_privilege('nonce_reader','metadata.internal_request_nonce','INSERT'),
          'readerDelete',has_table_privilege('nonce_reader','metadata.internal_request_nonce','DELETE'));"""
        if with_servlet_wire:
            query=query.removesuffix(';')[:-1]+", 'wireRows',(SELECT count(*) FROM auth.internal_request_nonce), 'wireRuntimeInsert',has_table_privilege('nonce_wire_runtime','auth.internal_request_nonce','INSERT'), 'wireRuntimeUpdate',has_table_privilege('nonce_wire_runtime','auth.internal_request_nonce','UPDATE'));"
        read=call(['docker','exec',cid,'psql','-v','ON_ERROR_STOP=1','-U','nonce_admin','-d',DATABASE,'-Atqc',query],env)
        report['databaseObservation']=observed_database(json.loads(read.stdout),with_servlet_wire)
        report['sourceHashesMatchAtEnd']=source_hashes()==report['sourceHashes']
        report['artifactHashesMatchAtEnd']=artifact_hashes()==report['artifactHashes']
        if not report['sourceHashesMatchAtEnd'] or not report['artifactHashesMatchAtEnd']:raise AuditFailure('SourceOrCompiledArtifactChanged')
        report['status']='passed'
    except Exception as error:
        failure=error;report.update(failureType=type(error).__name__,failedStage=stage)
        if isinstance(error,AuditFailure):report['failureCode']=str(error)
    finally:
        try:
            owned=call(['docker','ps','-aq','--filter','label='+LABEL+'='+run_id],env).stdout.split()
            for item in owned:
                info=json.loads(call(['docker','inspect',item],env).stdout)[0]
                if info['Config']['Labels'].get(LABEL)!=run_id or info['Name']!='/'+name:raise AuditFailure('CleanupContainerOwnershipMismatch')
                call(['docker','rm','-f',item],env)
            report['cleanup']['containerRemoved']=not call(['docker','ps','-aq','--filter','label='+LABEL+'='+run_id],env).stdout.strip()
        except Exception:report['cleanup']['containerRemoved']=False
        try:
            owned=call(['docker','network','ls','-q','--filter','label='+LABEL+'='+run_id],env).stdout.split()
            for item in owned:
                info=json.loads(call(['docker','network','inspect',item],env).stdout)[0]
                if info.get('Labels',{}).get(LABEL)!=run_id or info['Name']!=network or info.get('Containers'):raise AuditFailure('CleanupNetworkOwnershipMismatch')
                call(['docker','network','rm',item],env)
            report['cleanup']['networkRemoved']=not call(['docker','network','ls','-q','--filter','label='+LABEL+'='+run_id],env).stdout.strip()
            report['cleanup']['volumesAbsent']=not call(['docker','volume','ls','-q','--filter','label='+LABEL+'='+run_id],env).stdout.strip()
        except Exception:report['cleanup']['networkRemoved']=False;report['cleanup']['volumesAbsent']=False
        if not report['cleanup'] or not all(report['cleanup'].values()):report['status']='failed'
        report['completedAtUtc']=utc();(directory/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('Nonce store audit '+report['status']+'; sanitized report: '+str(directory/'report.json'),flush=True)
    if failure is not None or report['status']!='passed':raise AuditFailure('NonceStoreAcceptanceFailed') from None
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output');parser.add_argument('--with-servlet-wire',action='store_true');args=parser.parse_args();run(args.output,args.with_servlet_wire)


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Nonce acceptance failed: '+type(error).__name__+'; raw credentials/output withheld',file=sys.stderr);sys.exit(1)

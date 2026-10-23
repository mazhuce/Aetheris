#!/usr/bin/env python3
"""Run existing protocol tests on cached JRE21, with no network or application startup."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-protocol-runtime'
IMAGE='docker.1panel.live/library/eclipse-temurin:21-jre'
LABEL='aetheris.protocol.runtime.audit'
EXPECTED=160


class AuditFailure(RuntimeError): pass


def utc():return datetime.now(timezone.utc).isoformat()


def clean_environment(source=None):
    source=os.environ if source is None else source
    prefixes=('SPRING_','DB_','JWT_','AETHERIS_','COMPOSE_','POSTGRES_','REDIS_','DEEPSEEK_','INITIAL_ADMIN_')
    forbidden={'JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','CLASSPATH'}
    return {k:v for k,v in source.items() if not k.upper().startswith(prefixes) and k.upper() not in forbidden}


def call(args,env,timeout=30,allow_failure=False):
    result=subprocess.run([str(value) for value in args],cwd=ROOT,env=env,capture_output=True,text=True,
                          encoding='utf-8',errors='replace',timeout=timeout,
                          creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    if result.returncode and not allow_failure:raise AuditFailure('CommandFailed')
    return result


def java21(env):
    home=Path(os.environ.get('JAVA_HOME',''))
    if not home.is_absolute() or not home.is_dir():raise AuditFailure('AbsoluteJavaHomeRequired')
    home=home.resolve(); java=home/'bin'/('java.exe' if os.name=='nt' else 'java'); javac=java.with_name('javac.exe' if os.name=='nt' else 'javac')
    if not java.is_file() or not javac.is_file():raise AuditFailure('FullJdk21Required')
    version=call([java,'-XshowSettings:properties','-version'],env,15)
    if not re.search(r'java\.specification\.version\s*=\s*21\s',version.stderr):raise AuditFailure('Jdk21Required')
    return javac


def output_directory(path,run_id):
    if not re.fullmatch('[0-9a-f]{32}',run_id):raise ValueError('Invalid run id')
    path=Path(path).resolve()
    if OUTPUT.resolve() not in path.parents or path.exists():raise ValueError('New owned output directory required')
    return path


def hash_files(paths,base):
    result={}
    for path in sorted(paths):
        if path.is_symlink() or not path.is_file():raise AuditFailure('UnexpectedInputFile')
        result[path.relative_to(base).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def inputs():
    module=ROOT/'libs/security-protocol'
    reports=sorted((module/'target/surefire-reports').glob('TEST-*.xml'))
    if not reports:raise AuditFailure('MissingHostSurefireEvidence')
    classpaths=[]; host_count=0
    for path in reports:
        report=ET.parse(path).getroot()
        if any(int(report.get(key,'0')) for key in ('failures','errors','skipped')):raise AuditFailure('HostTestsNotAllSuccessful')
        host_count+=int(report.get('tests','0'))
        props={p.get('name'):p.get('value') for p in report.findall('properties/property')}
        classpaths.append(props.get('surefire.test.class.path') or props.get('java.class.path'))
    if host_count!=EXPECTED or not classpaths[0] or len(set(classpaths))!=1:raise AuditFailure('HostTestOrClasspathBaselineMismatch')
    entries=[Path(value).resolve() for value in classpaths[0].split(os.pathsep) if value]
    expected_dirs={(module/'target/classes').resolve(),(module/'target/test-classes').resolve()}
    if {p for p in entries if p.suffix!='.jar'}!=expected_dirs:raise AuditFailure('UnexpectedTestClasspathDirectories')
    jars=[p for p in entries if p.suffix=='.jar']
    cache=(Path.home()/'.m2/repository').resolve()
    if len(jars)!=11 or len({p.name for p in jars})!=11 or any(cache not in p.parents or not p.is_file() or p.is_symlink() for p in jars):
        raise AuditFailure('ExactlyElevenCachedDependencyJarsRequired')
    launcher=cache/'org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar'
    if not launcher.is_file() or launcher.is_symlink():raise AuditFailure('MissingCachedLauncher1114')
    jars.append(launcher)
    source_paths=[module/'pom.xml',*module.glob('src/**/*.java'),*module.glob('src/test/resources/**/*')]
    source_paths=[p for p in source_paths if p.is_file()]
    artifact_paths=[*(module/'target/classes').rglob('*'),*(module/'target/test-classes').rglob('*')]
    artifact_paths=[p for p in artifact_paths if p.is_file()]
    if not artifact_paths:raise AuditFailure('MissingCompiledTests')
    return module,reports,jars,hash_files(source_paths,ROOT),hash_files(artifact_paths,ROOT)


def parse_summary(stdout):
    lines=[line[len('AETHERIS_PROTOCOL_TESTS='):] for line in stdout.splitlines() if line.startswith('AETHERIS_PROTOCOL_TESTS=')]
    if len(lines)!=1:raise AuditFailure('MissingUniqueTestSummary')
    value=json.loads(lines[0])
    if not isinstance(value,dict):raise AuditFailure('InvalidTestSummary')
    return value


def successful(value):
    return value.get('status')=='passed' and value.get('javaSpecificationVersion')=='21' and value.get('found')==EXPECTED and value.get('succeeded')==EXPECTED and all(value.get(k)==0 for k in ('failed','skipped','aborted','containersFailed','containersSkipped','containersAborted'))


def run(output=None):
    run_id=uuid.uuid4().hex
    directory=output_directory(output or OUTPUT/run_id,run_id); directory.mkdir(parents=True)
    bundle=directory/'bundle'; bundle.mkdir(); container='aetheris-protocol-runtime-'+run_id
    report={'schemaVersion':1,'runId':run_id,'purpose':'protocol-library-runtime-compatibility-only','status':'failed',
            'startedAtUtc':utc(),'imageRequested':IMAGE,'cleanup':{},'network':'none','rootFilesystem':'read-only','containerUser':'10001:10001'}
    env=clean_environment(); failure=None; created=False
    try:
        javac=java21(env)
        image=json.loads(call(['docker','image','inspect',IMAGE],env).stdout)[0]
        report['imageId']=image['Id'];report['repoDigests']=image.get('RepoDigests',[]);report['imageOs']=image.get('Os');report['imageArchitecture']=image.get('Architecture')
        module,reports,jars,source_hashes,artifact_hashes=inputs()
        report['sourceHashes']=source_hashes;report['artifactHashes']=artifact_hashes
        report['hostSurefireHashes']=hash_files(reports,ROOT)
        report['dependencies']=[{'name':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars]
        report['helperSourceSha256']=hashlib.sha256((ROOT/'scripts/security/RunProtocolTests.java').read_bytes()).hexdigest()
        shutil.copytree(module/'target/classes',bundle/'protocol-classes')
        shutil.copytree(module/'target/test-classes',bundle/'test-classes')
        deps=bundle/'deps';deps.mkdir()
        for jar in jars:shutil.copyfile(jar,deps/jar.name)
        helper=bundle/'helper-classes';helper.mkdir()
        classpath=os.pathsep.join(str(deps/jar.name) for jar in jars)
        call([javac,'--release','21','-cp',classpath,'-d',helper,ROOT/'scripts/security/RunProtocolTests.java'],env,30)
        report['helperClassHashes']=hash_files(list(helper.rglob('*.class')),bundle)
        if call(['docker','ps','-aq','--filter','name=^/'+container+'$'],env).stdout.strip():raise AuditFailure('ContainerNameAlreadyExists')
        container_cp=':'.join(['/bundle/helper-classes','/bundle/test-classes','/bundle/protocol-classes',*['/bundle/deps/'+p.name for p in jars]])
        command=['docker','create','--name',container,'--label',LABEL+'='+run_id,'--pull','never','--network','none',
                 '--read-only','--user','10001:10001','--memory','512m','--cpus','1','--pids-limit','256','--cap-drop','ALL',
                 '--security-opt','no-new-privileges:true','--tmpfs','/tmp:rw,nosuid,nodev,size=128m,mode=1777',
                 '--mount','type=bind,src='+str(bundle)+',dst=/bundle,readonly',image['Id'],'java',
                 '-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.redirects.retrylimit=1',
                 '-Djava.io.tmpdir=/tmp','-cp',container_cp,'RunProtocolTests',str(EXPECTED)]
        cid=call(command,env).stdout.strip();created=True
        info=json.loads(call(['docker','inspect',cid],env).stdout)[0]
        if info['Config']['Labels'].get(LABEL)!=run_id or info['HostConfig']['NetworkMode']!='none' or not info['HostConfig']['ReadonlyRootfs']:
            raise AuditFailure('ContainerIsolationMismatch')
        report['containerId']=cid
        result=call(['docker','start','-a',cid],env,180,allow_failure=True)
        # Never persist or print raw test output. Only this fixed structured summary is retained.
        summary=parse_summary(result.stdout);report['tests']=summary
        exit_state=json.loads(call(['docker','inspect',cid],env).stdout)[0]['State']
        report['containerExitCode']=exit_state['ExitCode'];report['oomKilled']=exit_state.get('OOMKilled',False)
        report['sourceHashesMatchAtEnd']=hash_files([ROOT/p for p in source_hashes],ROOT)==source_hashes
        report['artifactHashesMatchAtEnd']=hash_files([ROOT/p for p in artifact_hashes],ROOT)==artifact_hashes
        if result.returncode!=0 or report['containerExitCode']!=0 or report['oomKilled'] or not successful(summary) or not report['sourceHashesMatchAtEnd'] or not report['artifactHashesMatchAtEnd']:
            raise AuditFailure('RuntimeTestsOrSourceBindingFailed')
        report['status']='passed'
    except Exception as error:
        failure=error;report['failureType']=type(error).__name__
        if isinstance(error,AuditFailure):report['failureCode']=str(error)
    finally:
        try:
            if created:
                info=json.loads(call(['docker','inspect',container],env).stdout)[0]
                if info['Config']['Labels'].get(LABEL)!=run_id:raise AuditFailure('CleanupOwnershipMismatch')
                call(['docker','rm','-f',container],env)
            report['cleanup']['containerRemoved']=not call(['docker','ps','-aq','--filter','label='+LABEL+'='+run_id],env).stdout.strip()
        except Exception:report['cleanup']['containerRemoved']=False
        try:
            if bundle.resolve().parent!=directory.resolve() or OUTPUT.resolve() not in bundle.resolve().parents or bundle.is_symlink():raise AuditFailure('UnownedBundleCleanup')
            shutil.rmtree(bundle);report['cleanup']['bundleRemoved']=not bundle.exists()
        except Exception:report['cleanup']['bundleRemoved']=False
        if not all(report['cleanup'].values()):report['status']='failed'
        report['completedAtUtc']=utc()
        (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('Protocol runtime audit '+report['status']+'; sanitized report: '+str(directory/'report.json'),flush=True)
    if failure is not None or report['status']!='passed':raise AuditFailure('ProtocolRuntimeAcceptanceFailed') from None
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output');args=parser.parse_args();run(args.output)


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Runtime acceptance failed: '+type(error).__name__+'; raw test/config output withheld',file=sys.stderr);sys.exit(1)

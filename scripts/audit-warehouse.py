#!/usr/bin/env python3
"""Execute warehouse SQL and failure assertions only in a disposable network-less Spark container."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-warehouse'
IMAGE='apache/spark:3.5.6'
LABEL='aetheris.warehouse.audit'
BASELINE='f22a32c836fdc7eab275d15a89919525ed3e3f19'
FILES=('01_layers.sql','02_ods.sql','03_dwd.sql','04_dws.sql','05_ads.sql','06_validate.sql')
NEGATIVES={'reject-'+name for name in ('dws-cent','ads-cent','category-cent','share','user-count','missing-summary',
           'missing-date','raw-phone','raw-email','order-cent','line-discount')}
VALUES={'USER_ROWS':'1000','PRODUCT_ROWS':'128','ORDER_START_DATE':'2024-02-28','ORDER_DAYS':'5',
        'ORDERS_PER_DAY':'400','DIM_DATE_START':'2024-02-01','DIM_DATE_DAYS':'62'}


def utc():return datetime.now(timezone.utc).isoformat()


def call(args,timeout=30,check=True):
    env={key:value for key,value in os.environ.items() if not key.upper().startswith(('AETHERIS_','SPARK_','HADOOP_','HIVE_','AWS_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'))}
    result=subprocess.run([str(value) for value in args],cwd=ROOT,env=env,capture_output=True,text=True,
                          encoding='utf-8',errors='replace',timeout=timeout,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    if check and result.returncode:raise RuntimeError('Warehouse fixture command failed')
    return result


def render(source):
    for name,value in VALUES.items():source=source.replace('@'+name+'@',value)
    if re.search(r'@[A-Z_]+@',source):raise ValueError('Unresolved warehouse fixture placeholder')
    return source


def new_directory(path):
    path=Path(path).resolve()
    if OUTPUT.resolve() not in path.parents or path.exists():raise ValueError('Use a new owned output directory')
    return path


def source_hashes():
    paths=[ROOT/'scripts/warehouse'/name for name in FILES]+[ROOT/'scripts/warehouse/audit_spark.py',ROOT/'scripts/gen-warehouse-data.sh',Path(__file__).resolve()]
    return {str(path.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def parse_summary(stdout):
    values=[line.split('=',1)[1] for line in stdout.splitlines() if line.startswith('AETHERIS_WAREHOUSE_AUDIT=')]
    if len(values)!=1:raise ValueError('A unique complete warehouse summary is required')
    report=json.loads(values[0])
    if report.get('status')!='passed' or report.get('sparkVersion')!='3.5.6':raise ValueError('Warehouse engine did not pass')
    checks=report.get('checks',[])
    required=set(re.findall(r'-- ASSERT: ([a-z0-9-]+)',(ROOT/'scripts/warehouse/06_validate.sql').read_text(encoding='utf-8')))
    required|=NEGATIVES|{'original-tables-still-pass-after-negative-views'}
    if len(required)!=33 or len(checks)!=33 or {value.get('case') for value in checks}!=required or any(value.get('passed') is not True for value in checks):
        raise ValueError('All 21 assertions, 11 corrupt-fixture failures and final restoration check are required')
    baselines=report.get('baseline',[])
    if len(baselines)!=2 or {value.get('case') for value in baselines}!={'original-date-dimension-syntax','independently-generated-order-payment'} or any(value.get('rejected') is not True for value in baselines):raise ValueError('Both original failures must be reproduced')
    counts=report.get('rowCounts',{})
    if len(counts)!=16 or any(type(value) is not int or value<=0 for value in counts.values()):raise ValueError('All 16 real warehouse tables must be nonempty')
    return report


def run(output=None):
    run_id=uuid.uuid4().hex;directory=new_directory(output or OUTPUT/run_id);directory.mkdir(parents=True)
    bundle=directory/'bundle';bundle.mkdir();name='aetheris-warehouse-'+run_id;created=False
    report={'schemaVersion':1,'runId':run_id,'purpose':'isolated-warehouse-engine-acceptance','startedAtUtc':utc(),'status':'failed',
            'baselineCommit':BASELINE,'fixture':VALUES,'cleanup':{}}
    try:
        image=json.loads(call(['docker','image','inspect',IMAGE]).stdout)[0]
        if image.get('Config',{}).get('Volumes'):raise ValueError('Image-declared anonymous volumes are not allowed')
        report.update(imageRequested=IMAGE,imageId=image['Id'],repoDigests=image.get('RepoDigests',[]),imageOs=image.get('Os'),imageArchitecture=image.get('Architecture'))
        report['sourceHashes']=source_hashes()
        for folder in ('current','baseline'):(bundle/folder).mkdir()
        for file in FILES:(bundle/'current'/file).write_text(render((ROOT/'scripts/warehouse'/file).read_text(encoding='utf-8')),encoding='utf-8')
        for file in ('01_layers.sql','02_ods.sql'):
            original=call(['git','show',BASELINE+':scripts/warehouse/'+file]).stdout
            (bundle/'baseline'/file).write_text(render(original),encoding='utf-8')
        shutil.copyfile(ROOT/'scripts/warehouse/audit_spark.py',bundle/'audit_spark.py')
        report['renderedHashes']={str(path.relative_to(bundle)).replace('\\','/'):hashlib.sha256(path.read_bytes()).hexdigest() for path in bundle.rglob('*') if path.is_file()}
        args=['docker','create','--name',name,'--label',LABEL+'='+run_id,'--pull','never','--network','none','--read-only',
              '--hostname','aetheris-warehouse-fixture','--add-host','aetheris-warehouse-fixture:127.0.0.1',
              '--user','185:185','--memory','2g','--cpus','2','--pids-limit','512','--cap-drop','ALL','--security-opt','no-new-privileges:true',
              '--tmpfs','/tmp:rw,exec,nosuid,nodev,size=1g,mode=1777','--mount','type=bind,src='+str(bundle)+',dst=/audit,readonly',
              '--workdir','/tmp','-e','SPARK_LOCAL_IP=127.0.0.1',image['Id'],'/opt/spark/bin/spark-submit','--master','local[2]',
              '--driver-memory','768m','--conf','spark.driver.extraJavaOptions=-XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError',
              '/audit/audit_spark.py','--isolated-audit']
        cid=call(args).stdout.strip();created=True
        identity=json.loads(call(['docker','inspect',cid]).stdout)[0]
        if identity['Config']['Labels'].get(LABEL)!=run_id or identity['HostConfig']['NetworkMode']!='none' or not identity['HostConfig']['ReadonlyRootfs']:
            raise ValueError('Container ownership or isolation mismatch')
        report['containerId']=cid
        result=call(['docker','start','-a',cid],timeout=900,check=False)
        # This bundle contains synthetic SQL only. Logs never contain an installation credential or real business row.
        (directory/'spark.log').write_text(result.stdout+'\n'+result.stderr,encoding='utf-8')
        state=json.loads(call(['docker','inspect',cid]).stdout)[0]['State']
        report['containerExitCode']=state['ExitCode'];report['oomKilled']=state.get('OOMKilled',False)
        report['sourceHashesMatchAtEnd']=source_hashes()==report['sourceHashes']
        report['engine']=parse_summary(result.stdout)
        if result.returncode or report['containerExitCode'] or report['oomKilled'] or not report['sourceHashesMatchAtEnd']:
            raise ValueError('Warehouse process, source or container result failed')
        report['status']='passed'
    except Exception as error:report['failureType']=type(error).__name__
    finally:
        try:
            if created:
                identity=json.loads(call(['docker','inspect',name]).stdout)[0]
                if identity['Config']['Labels'].get(LABEL)!=run_id:raise ValueError('Unowned cleanup refused')
                call(['docker','rm','-f',name])
            report['cleanup']['containerRemoved']=not call(['docker','ps','-aq','--filter','label='+LABEL+'='+run_id]).stdout.strip()
            report['cleanup']['noVolumesCreated']=not call(['docker','volume','ls','-q','--filter','label='+LABEL+'='+run_id]).stdout.strip()
        except Exception:report['cleanup']['containerRemoved']=False
        try:
            if bundle.resolve().parent!=directory.resolve() or OUTPUT.resolve() not in bundle.resolve().parents or bundle.is_symlink():raise ValueError('Unowned cleanup refused')
            shutil.rmtree(bundle);report['cleanup']['bundleRemoved']=not bundle.exists()
        except Exception:report['cleanup']['bundleRemoved']=False
        if not all(report['cleanup'].values()):report['status']='failed'
        report['completedAtUtc']=utc();(directory/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('Warehouse audit '+report['status']+'; report '+str(directory/'report.json'),flush=True)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path);args=parser.parse_args()
    raise SystemExit(run(args.output))

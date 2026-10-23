#!/usr/bin/env python3
"""Five real SQL-chain applications (optional full ten) and isolated PG/Redis; no existing environment."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,sys,time,traceback,uuid
from datetime import datetime,timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-workflow-sql-chain'
FULL_MODULES={name:'services/'+name for name in ('auth','system','metadata','workflow','ingestion','quality','serving','governance','ai-agent')}
FULL_MODULES['gateway']='apps/gateway'
MODULES={name:FULL_MODULES[name] for name in ('auth','system','metadata','workflow','gateway')}
LABEL='aetheris.workflow-sql-chain.audit'
spec=importlib.util.spec_from_file_location('workflow_sql_fixed_base',ROOT/'scripts/audit-fixed-execution-chain.py')
fixed=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixed)
SQL_CASES=frozenset(('actual-applications-healthy','workflow-admin-login','register-sql-target','create-sql-approver','sql-approver-login','create-sql-actor','workflow-operator-login',
    'workflow-calendar','workflow-calendar-date-valid','create-manual-sql','unapproved-sql-denied','unapproved-sql-zero-effects',
    'grant-sql-actor','grant-sql-approver','grant-approval-datasource','sql-approval-state','delegated-sql-approval','operator-sql-success',
    'sql-real-row-and-writer','sql-sealed-actor-and-approver','invalid-manual-date-denied','invalid-date-zero-attempts',
    'revoke-sql-actor','sql-revoked-actor-denied','sql-revocation-zero-new-attempt','create-cron-sql','cron-approval-state','cron-approve',
    'grant-cron-executor','cron-bound-state','cron-bind','cron-real-quartz-success','cron-date-and-writer','cron-generation-and-actor-seal',
    'workflow-no-workload-lookup-denied','workflow-sql-real-service-nonces','sql-owner-no-arbitrary-query'))
BASE_CASES=fixed.REQUIRED_CASES
REQUIRED_CASES=SQL_CASES
fixed.MODULES=MODULES;fixed.OUTPUT=OUTPUT;fixed.LABEL=LABEL;fixed.REQUIRED_CASES=REQUIRED_CASES
identity=fixed.identity;identity.MODULES=MODULES;identity.OUTPUT=OUTPUT;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
base=fixed.base;REQUIRED_CLEANUP=fixed.REQUIRED_CLEANUP
identity_clean=identity.clean_environment

def clean_environment():
    return {k:v for k,v in identity_clean().items() if not k.upper().startswith(('DEEPSEEK_','OPENAI_','ANTHROPIC_','AZURE_OPENAI_'))}
fixed.clean_environment=clean_environment;identity.clean_environment=clean_environment

def source_hashes():
    values=fixed.source_hashes();values[Path(__file__).relative_to(ROOT).as_posix()]=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();return values
def finalize_status(report):return identity.finalize_status(report)
def validate_output(value):return identity.validate_output(value)

def task_view_id(value):
    task=value.get('task') if isinstance(value,dict) else None
    identifier=task.get('id') if isinstance(task,dict) else None
    if type(identifier) is not int or identifier<=0:raise base.AuditFailure('TaskView response must contain a positive task.id')
    return identifier

def configure(full_platform=False):
    global MODULES,REQUIRED_CASES
    MODULES=dict(FULL_MODULES) if full_platform else {name:FULL_MODULES[name] for name in ('auth','system','metadata','workflow','gateway')}
    REQUIRED_CASES=frozenset((*BASE_CASES,*SQL_CASES)) if full_platform else SQL_CASES
    for module in (fixed,identity):module.MODULES=MODULES;module.REQUIRED_CASES=REQUIRED_CASES


class Audit(fixed.Audit):
    def jvm_memory_flags(self):return ('-Xms24m','-Xmx192m','-XX:MaxMetaspaceSize=160m','-XX:ReservedCodeCacheSize=48m')
    def __init__(self,output):
        super().__init__(output);self.report.update(purpose='real-applications-manual-and-quartz-fixed-sql',expectedServices=list(MODULES),
            limits='Five SQL-chain JVMs by default; --full-platform also tests ten JVMs plus transfer/quality. Not Spark/Flink/pipeline, paid AI/model, nine engines, browser or deployment')
    def setup_database(self):
        super().setup_database()
        self.sql('CREATE TABLE business.sql_output(id integer PRIMARY KEY,label text,biz_date date,writer text NOT NULL DEFAULT current_user); GRANT SELECT ON business.sql_output TO audit_target_reader; GRANT INSERT ON business.sql_output TO audit_target_writer;')
    def scenarios(self,ports):
        if len(MODULES)==10:super().scenarios(ports)
        self.check('actual-applications-healthy',len(self.report.get('processes',[]))==len(MODULES) and all(p.get('health')=='UP' and p.get('springPidMatched') is True for p in self.report['processes']),serviceCount=len(MODULES))
        gateway='http://127.0.0.1:'+str(ports['gateway']);admin=base.HttpClient(gateway,True,self.secrets)
        self.http('workflow-admin-login',admin,'POST','/api/auth/login',{200},{'username':self.admin_username,'password':self.admin_password})
        target=self.http('register-sql-target',admin,'POST','/api/metadata/datasources',{200},{'name':'workflow-target-writer','type':'postgresql','host':'127.0.0.1','port':self.report['ports']['postgres'],
            'databaseName':'aetheris_api_key_audit','username':'audit_target_reader','password':self.target_reader_password,'writeUsername':'audit_target_writer','writePassword':self.writer_password,'status':'active'})
        target_id=int(target['id'])
        name='sql_approver_'+self.run_id[:8];password=self.secret();person=self.http('create-sql-approver',admin,'POST','/api/auth/users',{201},{'username':name,'password':password,'role':'viewer','status':'active'})
        approver_id=int(person['id']);approver=base.HttpClient(gateway,True,self.secrets);self.http('sql-approver-login',approver,'POST','/api/auth/login',{200},{'username':name,'password':password})
        # The inherited operator password is intentionally not retained. Use a second explicit actor.
        actor_name='sql_actor_'+self.run_id[:8];actor_password=self.secret()
        actor=self.http('create-sql-actor',admin,'POST','/api/auth/users',{201},{'username':actor_name,'password':actor_password,'role':'viewer','status':'active'})
        actor_id=int(actor['id']);user=base.HttpClient(gateway,True,self.secrets);self.http('workflow-operator-login',user,'POST','/api/auth/login',{200},{'username':actor_name,'password':actor_password})
        calendar=self.http('workflow-calendar',admin,'GET','/api/workflow/business-calendar',{200})
        self.check('workflow-calendar-date-valid',calendar.get('zone')=='Asia/Shanghai' and datetime.strptime(calendar['previousDate'],'%Y-%m-%d').date()+timedelta(days=1)==datetime.strptime(calendar['today'],'%Y-%m-%d').date())
        definition={'name':'sealed-manual-sql','taskType':'sql','datasourceId':target_id,'taskSql':"INSERT INTO business.sql_output(id,label,biz_date) VALUES(1,'SQL甲','${bizDate}')",'targetTable':'business.sql_output','timeoutSeconds':30,'maxRetries':0,'status':'enabled'}
        task=self.http('create-manual-sql',admin,'POST','/api/workflow/tasks',{200},definition);task_id=task_view_id(task);prefix='/api/workflow/tasks/'+str(task_id)
        self.http('unapproved-sql-denied',admin,'POST',prefix+'/run',{409},{'bizDate':'2024-02-29'})
        self.check('unapproved-sql-zero-effects',self.sql('SELECT count(*) FROM business.sql_output',True)==0)
        def grant(label,uid,kind,rid,actions):return self.http(label,admin,'POST','/api/auth/rbac/grants',{200,201},{'granteeType':'user','granteeId':uid,'resourceType':kind,'resourceId':rid,'actions':actions,'enabled':True})
        actor_grant=grant('grant-sql-actor',actor_id,'workflow_task',task_id,['view','execute'])
        grant('grant-sql-approver',approver_id,'workflow_task',task_id,['view','update']);grant('grant-approval-datasource',approver_id,'datasource',target_id,['query','write'])
        state=self.http('sql-approval-state',admin,'GET',prefix+'/approval',{200});self.http('delegated-sql-approval',approver,'POST',prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        instance=self.http('operator-sql-success',user,'POST',prefix+'/run',{200},{'bizDate':'2024-02-29'})
        row=self.sql("SELECT json_build_array(id,label,biz_date::text,writer) FROM business.sql_output WHERE id=1",True)
        self.check('sql-real-row-and-writer',instance.get('status')=='success' and instance.get('affectedRows')==1 and row==[1,'SQL甲','2024-02-29','audit_target_writer'])
        instance_id=int(instance['id'])
        seal=self.sql("SELECT json_build_object('uid',(invocation_json->>'uid')::bigint,'kind',invocation_json->>'kind','approver',(approval_json->>'uid')::bigint,'attemptId',id,'fence',fencing_token,'receipt',metadata_receipt_id,'hashOk',manifest_sha256=encode(sha256(convert_to(sealed_manifest::text,'UTF8')),'hex'),'manualGeneration',source_schedule_generation IS NULL AND source_binding_generation IS NULL) FROM workflow.execution_attempt WHERE instance_id="+str(instance_id),True)
        self.check('sql-sealed-actor-and-approver',seal['uid']==actor_id and seal['approver']==approver_id and seal['kind']=='manual' and seal['attemptId']>0 and seal['fence']>0 and seal['receipt']>0 and seal['hashOk'] is True and seal['manualGeneration'] is True)
        self.http('invalid-manual-date-denied',user,'POST',prefix+'/run',{400},{'bizDate':'2024-02-30'})
        self.check('invalid-date-zero-attempts',self.sql('SELECT count(*) FROM workflow.execution_attempt WHERE instance_id='+str(instance_id),True)==1 and self.sql('SELECT count(*) FROM business.sql_output',True)==1)
        self.http('revoke-sql-actor',admin,'DELETE','/api/auth/rbac/grants/'+str(actor_grant['id']),{204});self.http('sql-revoked-actor-denied',user,'POST',prefix+'/run',{403},{'bizDate':'2024-03-01'})
        self.check('sql-revocation-zero-new-attempt',self.sql('SELECT count(*) FROM workflow.task_instance WHERE task_id='+str(task_id),True)==1)
        # One exact future Quartz fire: no periodic successful re-execution or sleep-based assertion.
        fire=datetime.now(ZoneInfo('Asia/Shanghai'))+timedelta(seconds=18)
        cron=f'{fire.second} {fire.minute} {fire.hour} {fire.day} {fire.month} ? {fire.year}'
        scheduled=self.http('create-cron-sql',admin,'POST','/api/workflow/tasks',{200},{**definition,'name':'sealed-cron-sql','taskSql':"INSERT INTO business.sql_output(id,label,biz_date) VALUES(2,'定时乙','${bizDate}')",'cronExpression':cron})
        cron_id=task_view_id(scheduled);cron_prefix='/api/workflow/tasks/'+str(cron_id)
        cron_state=self.http('cron-approval-state',admin,'GET',cron_prefix+'/approval',{200});self.http('cron-approve',admin,'POST',cron_prefix+'/approve',{200},{'expectedRevision':cron_state['executionRevision']})
        grant('grant-cron-executor',actor_id,'workflow_task',cron_id,['view','execute'])
        cron_state=self.http('cron-bound-state',admin,'GET',cron_prefix+'/approval',{200});self.http('cron-bind',user,'POST',cron_prefix+'/cron-binding',{200},{'expectedRevision':cron_state['executionRevision'],'expectedGeneration':cron_state['scheduleGeneration']})
        deadline=time.monotonic()+40;completed=None
        while time.monotonic()<deadline:
            completed=self.sql("SELECT coalesce(json_agg(json_build_object('status',status,'id',id,'bizDate',biz_date) ORDER BY id),'[]'::json) FROM workflow.task_instance WHERE task_id="+str(cron_id),True)
            if completed and completed[0]['status'] not in ('running','queued','submitting'):break
            time.sleep(.2)
        self.check('cron-real-quartz-success',len(completed or [])==1 and completed[0]['status']=='success')
        row=self.sql("SELECT json_build_array(id,label,biz_date::text,writer) FROM business.sql_output WHERE id=2",True)
        self.check('cron-date-and-writer',row==[2,'定时乙',calendar['previousDate'],'audit_target_writer'] and completed[0]['bizDate']==calendar['previousDate'])
        sealed=self.sql("SELECT json_build_object('kind',a.invocation_json->>'kind','uid',(a.invocation_json->>'uid')::bigint,'scheduleMatch',a.source_schedule_generation=t.schedule_version,'bindingMatch',a.source_binding_generation=t.cron_binding_generation,'receipt',a.metadata_receipt_id) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id JOIN workflow.task t ON t.id=i.task_id WHERE t.id="+str(cron_id),True)
        self.check('cron-generation-and-actor-seal',sealed['kind']=='cron' and sealed['uid']==actor_id and sealed['scheduleMatch'] is True and sealed['bindingMatch'] is True and sealed['receipt']>0)
        self.http('workflow-no-workload-lookup-denied',base.HttpClient('http://127.0.0.1:'+str(ports['workflow'])),'POST','/internal/v2/executions/lookup',{401},{'instanceId':instance_id,'attemptId':seal['attemptId'],'fence':seal['fence']})
        nonces=self.sql("SELECT json_build_object('workflowMetadata',(SELECT count(*) FROM workflow.internal_request_nonce WHERE caller='metadata'),'metadataWorkflow',(SELECT count(*) FROM metadata.internal_request_nonce WHERE caller='workflow'),'authMetadata',(SELECT count(*) FROM auth.internal_request_nonce WHERE caller='metadata'))",True)
        self.check('workflow-sql-real-service-nonces',all(n>0 for n in nonces.values()),counts=nonces)
        self.http('sql-owner-no-arbitrary-query',user,'POST','/api/metadata/datasources/'+str(target_id)+'/query',{403},{'sql':'select 1'})
    def run(self):
        self.report['sourceHashes']=source_hashes();self.report['migrationSourceHashes']=base.database_contract.source_manifest()
        try:
            self.java=base.resolve_java21();self.report['jdk']={'executable':self.java,'specificationVersion':'21'};redis=self.setup_containers();self.setup_database();self.generate_keys()
            jars=self.build();ports=self.start_apps(jars,redis);self.scenarios(ports)
            if source_hashes()!=self.report['sourceHashes']:raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd']=True;self.report['status']='passed'
        except (Exception,KeyboardInterrupt) as failure:
            self.report['status']='failed';self.report['failure']={'type':type(failure).__name__,'message':base.redact(str(failure),self.secrets) if isinstance(failure,base.AuditFailure) else 'Operation failed; inspect sanitized logs',
                'locations':[{'file':Path(f.filename).name,'line':f.lineno,'function':f.name} for f in traceback.extract_tb(failure.__traceback__)]}
        finally:self.cleanup()
        return self.report


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--full-platform',action='store_true');args=p.parse_args();configure(args.full_platform);r=Audit(args.output).run()
    print('Workflow SQL chain '+r['status']+'; checks='+str(len(r['checks']))+'; cleanup='+str(all(r['cleanup'].values())))
    return 0 if r['status']=='passed' else 1

if __name__=='__main__':sys.exit(main())

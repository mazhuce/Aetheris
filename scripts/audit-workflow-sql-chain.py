#!/usr/bin/env python3
"""Five real SQL-chain applications (optional full ten) and isolated PG/Redis; no existing environment."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,os,re,subprocess,sys,time,traceback,uuid
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
PIPELINE_CASES=frozenset(('pipeline-create-upstream','pipeline-create-target','pipeline-add-dependency',
    'pipeline-grant-actor-upstream','pipeline-grant-actor-target','pipeline-grant-approver-upstream','pipeline-grant-approver-target',
    'pipeline-unapproved-denied','pipeline-unapproved-zero-effects','pipeline-upstream-approval-state','pipeline-upstream-approve',
    'pipeline-target-approval-state','pipeline-target-approve','pipeline-invalid-date-denied','pipeline-invalid-date-zero-runs',
    'pipeline-start','pipeline-real-success','pipeline-detail','pipeline-real-rows-and-writer','pipeline-sealed-source-and-subject',
    'pipeline-private-snapshot-redacted','pipeline-revoke-target','pipeline-revoked-start-denied','pipeline-revoked-zero-new-effects'))
LOGOUT_CASES=frozenset(('grant-cron-approver','cron-actor-logout','cron-actor-old-browser-denied',
    'cron-approver-logout','cron-approver-old-browser-denied','cron-logout-account-versions-preserved',
    'cron-executed-after-both-family-revocations','cron-actor-new-browser-family','cron-approver-new-browser-family',
    'pipeline-owned-writer-blocked','pipeline-actor-logout','pipeline-actor-old-browser-denied',
    'pipeline-approver-logout','pipeline-approver-old-browser-denied','pipeline-logout-account-versions-preserved',
    'pipeline-owned-lock-released','pipeline-downstream-authorized-after-logout','pipeline-original-approval-preserved',
    'pipeline-actor-new-browser-family'))
SQL_CASES=SQL_CASES|PIPELINE_CASES|LOGOUT_CASES
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
        super().__init__(output);self.lock_processes=[];self.report.update(purpose='real-applications-manual-quartz-and-pipeline-fixed-sql',expectedServices=list(MODULES),
            limits='Five SQL-chain JVMs by default; --full-platform also tests ten JVMs plus transfer/quality. Pipeline scope is approved SQL only, not mixed engines, paid AI/model, nine engines, browser or deployment')
    def setup_database(self):
        super().setup_database()
        self.sql('CREATE TABLE business.sql_output(id integer PRIMARY KEY,label text,biz_date date,writer text NOT NULL DEFAULT current_user); GRANT SELECT ON business.sql_output TO audit_target_reader; GRANT SELECT,INSERT ON business.sql_output TO audit_target_writer;')
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
        self.approver_login={'username':name,'password':password}
        # The inherited operator password is intentionally not retained. Use a second explicit actor.
        actor_name='sql_actor_'+self.run_id[:8];actor_password=self.secret()
        actor=self.http('create-sql-actor',admin,'POST','/api/auth/users',{201},{'username':actor_name,'password':actor_password,'role':'viewer','status':'active'})
        actor_id=int(actor['id']);user=base.HttpClient(gateway,True,self.secrets);self.http('workflow-operator-login',user,'POST','/api/auth/login',{200},{'username':actor_name,'password':actor_password})
        self.actor_login={'username':actor_name,'password':actor_password}
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
        grant('grant-cron-approver',approver_id,'workflow_task',cron_id,['view','update'])
        cron_state=self.http('cron-approval-state',admin,'GET',cron_prefix+'/approval',{200});self.http('cron-approve',approver,'POST',cron_prefix+'/approve',{200},{'expectedRevision':cron_state['executionRevision']})
        grant('grant-cron-executor',actor_id,'workflow_task',cron_id,['view','execute'])
        cron_state=self.http('cron-bound-state',admin,'GET',cron_prefix+'/approval',{200});self.http('cron-bind',user,'POST',cron_prefix+'/cron-binding',{200},{'expectedRevision':cron_state['executionRevision'],'expectedGeneration':cron_state['scheduleGeneration']})
        cron_families=self.logout_execution_subjects('cron',user,approver,actor_id,approver_id)
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
        self.check('cron-executed-after-both-family-revocations',self.executed_after_logout(cron_id,cron_families),familiesRevokedBeforeAttempt=True)
        self.http('cron-actor-new-browser-family',user,'POST','/api/auth/login',{200},self.actor_login)
        self.http('cron-approver-new-browser-family',approver,'POST','/api/auth/login',{200},self.approver_login)
        self.http('workflow-no-workload-lookup-denied',base.HttpClient('http://127.0.0.1:'+str(ports['workflow'])),'POST','/internal/v2/executions/lookup',{401},{'instanceId':instance_id,'attemptId':seal['attemptId'],'fence':seal['fence']})
        nonces=self.sql("SELECT json_build_object('workflowMetadata',(SELECT count(*) FROM workflow.internal_request_nonce WHERE caller='metadata'),'metadataWorkflow',(SELECT count(*) FROM metadata.internal_request_nonce WHERE caller='workflow'),'authMetadata',(SELECT count(*) FROM auth.internal_request_nonce WHERE caller='metadata'))",True)
        self.check('workflow-sql-real-service-nonces',all(n>0 for n in nonces.values()),counts=nonces)
        self.http('sql-owner-no-arbitrary-query',user,'POST','/api/metadata/datasources/'+str(target_id)+'/query',{403},{'sql':'select 1'})
        self.pipeline_scenarios(admin,user,approver,actor_id,approver_id,target_id,grant)

    def pipeline_scenarios(self,admin,user,approver,actor_id,approver_id,datasource,grant):
        self.checkpoint('real-approved-sql-pipeline')
        definition={'taskType':'sql','datasourceId':datasource,'targetTable':'business.sql_output','timeoutSeconds':30,'maxRetries':0,'status':'enabled'}
        first=task_view_id(self.http('pipeline-create-upstream',admin,'POST','/api/workflow/tasks',{200},{**definition,'name':'pipeline-upstream',
            'taskSql':"INSERT INTO business.sql_output(id,label,biz_date) VALUES(10,'流水线上游甲','${bizDate}')"}))
        target=task_view_id(self.http('pipeline-create-target',admin,'POST','/api/workflow/tasks',{200},{**definition,'name':'pipeline-target',
            'taskSql':"INSERT INTO business.sql_output(id,label,biz_date) SELECT 11,'流水线下游乙','${bizDate}' FROM business.sql_output WHERE id=10"}))
        self.http('pipeline-add-dependency',admin,'POST','/api/workflow/tasks/'+str(target)+'/dependencies?upstreamTaskId='+str(first),{200})
        grant('pipeline-grant-actor-upstream',actor_id,'workflow_task',first,['view','execute'])
        target_grant=grant('pipeline-grant-actor-target',actor_id,'workflow_task',target,['view','execute'])
        grant('pipeline-grant-approver-upstream',approver_id,'workflow_task',first,['view','update'])
        grant('pipeline-grant-approver-target',approver_id,'workflow_task',target,['view','update'])
        body={'targetTaskId':target,'bizDate':'2024-02-29'}
        self.http('pipeline-unapproved-denied',user,'POST','/api/workflow/pipelines',{409},body)
        self.check('pipeline-unapproved-zero-effects',self.sql('SELECT count(*) FROM workflow.pipeline_run WHERE target_task_id='+str(target),True)==0
            and self.sql('SELECT count(*) FROM business.sql_output WHERE id>=10',True)==0)
        for label,identifier in (('upstream',first),('target',target)):
            prefix='/api/workflow/tasks/'+str(identifier)
            state=self.http('pipeline-'+label+'-approval-state',approver,'GET',prefix+'/approval',{200})
            self.http('pipeline-'+label+'-approve',approver,'POST',prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        self.http('pipeline-invalid-date-denied',user,'POST','/api/workflow/pipelines',{400},{**body,'bizDate':'2024-02-30'})
        self.check('pipeline-invalid-date-zero-runs',self.sql('SELECT count(*) FROM workflow.pipeline_run WHERE target_task_id='+str(target),True)==0)
        # Forged browser identity headers must not replace the verified non-admin source.
        lock=self.hold_output_lock()
        try:
            started=self.http('pipeline-start',user,'POST','/api/workflow/pipelines',{200},body,{'X-User':'admin','X-Username':'admin','X-Role':'admin'})
            run_id=int(started['id']);original=self.pipeline_material(run_id)
            deadline=time.monotonic()+10;blocked=0
            while time.monotonic()<deadline:
                blocked=self.sql("SELECT count(*) FROM pg_stat_activity writer WHERE writer.usename='audit_target_writer' AND writer.wait_event_type='Lock' AND EXISTS(SELECT 1 FROM pg_stat_activity holder WHERE holder.application_name='"+lock['application']+"' AND holder.pid=ANY(pg_blocking_pids(writer.pid)))",True)
                if blocked==1:break
                time.sleep(.1)
            self.check('pipeline-owned-writer-blocked',blocked==1 and self.sql('SELECT count(*) FROM business.sql_output WHERE id>=10',True)==0)
            families=self.logout_execution_subjects('pipeline',user,approver,actor_id,approver_id)
        finally:self.release_output_lock(lock)
        self.check('pipeline-owned-lock-released',lock['process'].poll()==0 and self.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+lock['application']+"'",True)==0)
        run_id=int(started['id']);deadline=time.monotonic()+45;state=None
        while time.monotonic()<deadline:
            state=self.sql('SELECT status FROM workflow.pipeline_run WHERE id='+str(run_id))
            if state not in ('queued','running'):break
            time.sleep(.2)
        self.check('pipeline-real-success',state=='succeeded',runId=run_id,observedStatus=state)
        self.check('pipeline-downstream-authorized-after-logout',self.executed_after_logout(target,families),downstreamTaskId=target)
        self.check('pipeline-original-approval-preserved',original==self.pipeline_material(run_id))
        self.http('pipeline-actor-new-browser-family',user,'POST','/api/auth/login',{200},self.actor_login)
        detail=self.http('pipeline-detail',user,'GET','/api/workflow/pipelines/'+str(run_id),{200})
        rows=self.sql("SELECT json_agg(json_build_array(id,label,biz_date::text,writer) ORDER BY id) FROM business.sql_output WHERE id>=10",True)
        self.check('pipeline-real-rows-and-writer',rows==[[10,'流水线上游甲','2024-02-29','audit_target_writer'],[11,'流水线下游乙','2024-02-29','audit_target_writer']])
        proof=self.sql("""SELECT json_build_object('nodes',count(*),'matched',count(*) FILTER(WHERE
            node.status='succeeded' AND instance.status='success' AND attempt.status='succeeded'
            AND node.attempt_id=instance.active_attempt_id AND node.fencing_token=instance.fencing_token
            AND attempt.pipeline_run_id=run.id AND attempt.pipeline_node_id=node.id AND attempt.pipeline_fence=run.execution_fence
            AND attempt.invocation_json=run.invocation_json AND attempt.invocation_json->>'kind'='pipeline'
            AND (attempt.invocation_json->>'uid')::bigint="""+str(actor_id)+""" AND (attempt.approval_json->>'uid')::bigint="""+str(approver_id)+"""
            AND attempt.execution_revision=node.execution_revision AND attempt.approval_json=node.approval_json
            AND attempt.metadata_receipt_id>0 AND attempt.manifest_sha256=encode(sha256(convert_to(attempt.sealed_manifest::text,'UTF8')),'hex')
            AND run.plan_sha256=encode(sha256(convert_to(run.plan_json::text,'UTF8')),'hex')))
            FROM workflow.pipeline_run run JOIN workflow.pipeline_node_run node ON node.pipeline_run_id=run.id
            JOIN workflow.task_instance instance ON instance.id=node.instance_id JOIN workflow.execution_attempt attempt ON attempt.id=node.attempt_id
            WHERE run.id="""+str(run_id),True)
        self.check('pipeline-sealed-source-and-subject',proof=={'nodes':2,'matched':2},proof=proof)
        self.check('pipeline-private-snapshot-redacted',not any(k in detail or k in started for k in ('plan_json','invocation_json','worker_id'))
            and len(detail.get('nodes',[]))==2 and all(not any(k in node for k in ('definition_json','approval_json','dependency_evidence')) for node in detail['nodes']))
        before=self.sql('SELECT count(*) FROM workflow.execution_attempt',True)
        self.http('pipeline-revoke-target',admin,'DELETE','/api/auth/rbac/grants/'+str(target_grant['id']),{204})
        self.http('pipeline-revoked-start-denied',user,'POST','/api/workflow/pipelines',{403},{**body,'bizDate':'2024-03-01'})
        self.check('pipeline-revoked-zero-new-effects',self.sql('SELECT count(*) FROM workflow.execution_attempt',True)==before
            and self.sql('SELECT count(*) FROM workflow.pipeline_run WHERE target_task_id='+str(target),True)==1
            and self.sql('SELECT count(*) FROM business.sql_output WHERE id>=10',True)==2)

    def logout_execution_subjects(self,label,user,approver,actor_id,approver_id):
        ids=str(actor_id)+','+str(approver_id)
        versions="SELECT json_agg(json_build_array(id,access_version,session_version) ORDER BY id) FROM auth.auth_user WHERE id IN ("+ids+")"
        before=self.sql(versions,True);families=[]
        for kind,client,uid in (('actor',user,actor_id),('approver',approver,approver_id)):
            selected=[c.value for c in client.jar if c.name=='aetheris_session']
            if len(selected)!=1 or str(uuid.UUID(selected[0]))!=selected[0]:raise base.AuditFailure('OneCanonicalBrowserFamilyRequired')
            families.append(selected[0])
            self.http(label+'-'+kind+'-logout',client,'POST','/api/auth/logout',{204})
            self.http(label+'-'+kind+'-old-browser-denied',client,'GET','/api/auth/me',{401})
            if self.sql("SELECT count(*) FROM auth.browser_session WHERE sid='"+selected[0]+"' AND user_id="+str(uid)+" AND revoked_at IS NOT NULL",True)!=1:
                raise base.AuditFailure('LogoutDidNotRevokeOriginalFamily')
        self.check(label+'-logout-account-versions-preserved',before==self.sql(versions,True))
        return families

    def executed_after_logout(self,task_id,families):
        if len(families)!=2 or any(str(uuid.UUID(sid))!=sid for sid in families):raise base.AuditFailure('InvalidLogoutEvidenceFamilies')
        literal=','.join("'"+sid+"'" for sid in families)
        return self.sql("SELECT count(*) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id WHERE i.task_id="+str(task_id)
            +" AND a.status='succeeded' AND a.metadata_receipt_id>0 AND (SELECT count(*) FROM auth.browser_session WHERE sid IN ("+literal+") AND revoked_at IS NOT NULL)=2"
            +" AND a.queued_at>(SELECT max(revoked_at) FROM auth.browser_session WHERE sid IN ("+literal+"))",True)==1

    def pipeline_material(self,run_id):
        return self.sql("SELECT json_build_object('plan',plan_sha256,'invocation',encode(sha256(convert_to(invocation_json::text,'UTF8')),'hex'),"
            +"'approvals',(SELECT json_agg(json_build_array(task_id,execution_revision,definition_sha256,approval_json) ORDER BY task_id) FROM workflow.pipeline_node_run WHERE pipeline_run_id="+str(run_id)
            +")) FROM workflow.pipeline_run WHERE id="+str(run_id),True)

    def hold_output_lock(self):
        if not getattr(self,'local_docker_verified',False) or not self.containers:raise base.AuditFailure('UnverifiedFixtureLockTarget')
        cid=self.containers[0];info=json.loads(self.docker('inspect',cid))[0]
        if info.get('Config',{}).get('Labels',{}).get(LABEL)!=self.run_id:raise base.AuditFailure('FixtureLockOwnershipMismatch')
        application='audit-logout-'+self.run_id
        path=self.folder/'pipeline-owned-lock.log';log=path.open('xb')
        try:
            process=subprocess.Popen(['docker','exec','-i',cid,'psql','-X','-qAt','-U','audit_admin','-d','aetheris_api_key_audit','-v','ON_ERROR_STOP=1'],
                cwd=self.folder,env=clean_environment(),stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,
                text=True,encoding='utf-8',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        except BaseException:
            log.close()
            raise
        lock={'process':process,'log':log,'path':path,'application':application};self.lock_processes.append(lock)
        process.stdin.write("SET application_name='"+application+"'; SET idle_in_transaction_session_timeout='20s'; BEGIN; LOCK TABLE business.sql_output IN SHARE MODE;\n");process.stdin.flush()
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            if process.poll() is not None:raise base.AuditFailure('OwnedLockProcessExited')
            ready=self.sql("SELECT count(*) FROM pg_stat_activity a JOIN pg_locks l ON l.pid=a.pid WHERE a.application_name='"+application+"' AND l.relation='business.sql_output'::regclass AND l.mode='ShareLock' AND l.granted",True)
            if ready==1:return lock
            time.sleep(.1)
        raise base.AuditFailure('OwnedLockReadinessDeadline')

    def release_output_lock(self,lock):
        process=lock['process']
        if not any(owned is lock for owned in self.lock_processes):raise base.AuditFailure('UnknownLockProcess')
        if process.poll() is None:
            process.stdin.write('ROLLBACK;\n\\q\n');process.stdin.flush();process.stdin.close();process.wait(timeout=5)
        if process.returncode!=0:raise base.AuditFailure('OwnedLockReleaseFailed')

    def cleanup(self):
        details=[]
        for lock in self.lock_processes:
            process=lock['process'];released=True
            try:self.release_output_lock(lock)
            except Exception:
                released=False
                try:
                    if process.stdin and not process.stdin.closed:process.stdin.close()
                    if process.poll() is None:process.terminate();process.wait(timeout=5)
                except Exception:pass
            lock['log'].close();details.append({'pid':process.pid,'exited':process.poll() is not None,'transactionReleasedNormally':released})
        self.report['fixtureLockCleanup']=details
        if any(not d['exited'] or not d['transactionReleasedNormally'] for d in details):self.report['status']='failed'
        super().cleanup()
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

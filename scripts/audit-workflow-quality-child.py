#!/usr/bin/env python3
"""Six real apps, owned PG/Redis and business reader/writer: source-bound workflow quality children."""
from __future__ import annotations
import argparse,hashlib,importlib.util,re,subprocess,sys,time
from datetime import datetime,timezone,timedelta
from zoneinfo import ZoneInfo
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-workflow-quality-child'
LABEL='aetheris.workflow-quality-child.audit'
spec=importlib.util.spec_from_file_location('workflow_child_sql_base',ROOT/'scripts/audit-workflow-sql-chain.py')
sql=importlib.util.module_from_spec(spec);spec.loader.exec_module(sql)
fixed=sql.fixed;identity=sql.identity;base=sql.base
MODULES={name:sql.FULL_MODULES[name] for name in ('auth','system','metadata','quality','workflow','gateway')}
CHILD_CASES=frozenset(('child-create-source','child-create-rule','child-rule-state','child-rule-approve','child-create-actor','child-actor-login',
    'child-create-approver','child-approver-login','child-grant-rule-actor','child-grant-rule-approver','child-create-parent','child-grant-parent-actor','child-grant-parent-approver',
    'child-unapproved-denied','child-unapproved-zero-results','child-parent-state','child-parent-approve','child-parent-pins-rule','child-manual-success','child-exact-subject-parent-and-receipt',
    'child-reader-connection-proven','child-no-arbitrary-datasource-query','child-no-workload-submit-denied','child-no-workload-status-denied',
    'child-revoke-rule-actor','child-revoked-run-unconfirmed','child-revocation-zero-reader-connections','child-regrant-rule-actor',
    'child-create-sql-target','child-add-sql-dependency','child-grant-sql-actor','child-sql-state','child-sql-approve','child-pipeline-start','child-pipeline-real-success','child-pipeline-exact-source-and-write',
    'child-create-cron-parent','child-grant-cron-actor','child-grant-cron-approver','child-cron-state','child-cron-approve',
    'child-cron-binding-state','child-cron-bind','child-cron-real-quartz-success','child-cron-exact-actor-generation-and-date',
    'child-create-denial-rule','child-denial-rule-state','child-denial-rule-approve','child-grant-denial-rule-actor','child-grant-denial-rule-approver','child-create-denial-parent','child-grant-denial-parent-actor','child-grant-denial-parent-approver','child-denial-parent-state','child-denial-parent-approve',
    'child-revoke-parent-approver','child-parent-approver-revoked-run','child-parent-approver-revoked-zero-reader-connections'))
REQUIRED_CASES=sql.SQL_CASES|CHILD_CASES
for module in (sql,fixed,identity):module.MODULES=MODULES;module.OUTPUT=OUTPUT;module.LABEL=LABEL;module.REQUIRED_CASES=REQUIRED_CASES
original_sources=sql.source_hashes
def source_hashes():
    result=original_sources();result[Path(__file__).relative_to(ROOT).as_posix()]=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();return result
sql.source_hashes=source_hashes
def connection_log_attachments_valid(report,folder):
    snapshots=report.get('connectionLogSnapshots',[]);seen=set()
    if len(snapshots)<6:return False
    try:
        for item in snapshots:
            path=(ROOT/item['path']).resolve()
            if path in seen or folder.resolve() not in path.parents or not path.is_file() or (ROOT/item['path']).is_symlink():return False
            seen.add(path);raw=path.read_bytes()
            if not re.fullmatch('[a-f0-9]{64}',item['sha256']) or hashlib.sha256(raw).hexdigest()!=item['sha256']:return False
            if type(item['readerConnections']) is not int or item['readerConnections']<0 or len(re.findall(r'connection authorized: user=audit_source_reader\b',raw.decode('utf-8')))!=item['readerConnections']:return False
            at=datetime.fromisoformat(item['capturedAtUtc'].replace('Z','+00:00'))
            if at.utcoffset()!=timezone.utc.utcoffset(at):return False
        return True
    except (KeyError,TypeError,ValueError,OSError,UnicodeDecodeError):return False
class Audit(sql.Audit):
    def __init__(self,output):
        super().__init__(output);self.report.update(purpose='six-real-applications-source-bound-workflow-quality-child',expectedServices=list(MODULES),
            limits='Actual Auth/System/Metadata/Quality/Workflow/Gateway, manual/Quartz/SQL pipeline and quality-child manual/pipeline with real business PG reader/writer. No Spark/Flink, browser, nine-engine, install/recovery or release claim.')
    def setup_database(self):
        super().setup_database();self.sql("ALTER SYSTEM SET log_connections='on';");self.sql('SELECT pg_reload_conf();')
    def reader_connections(self):
        result=subprocess.run(['docker','logs',self.containers[0]],capture_output=True,text=True,encoding='utf-8',errors='replace',env=sql.clean_environment(),timeout=15,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        if result.returncode:raise base.AuditFailure('OwnedPostgresConnectionLogUnavailable')
        clean=base.redact(result.stdout+result.stderr,self.secrets)
        snapshots=self.report.setdefault('connectionLogSnapshots',[]);path=self.folder/('postgres-connections-'+str(len(snapshots)+1).zfill(4)+'.log');path.write_text(clean,encoding='utf-8')
        count=len(re.findall(r'connection authorized: user=audit_source_reader\b',clean))
        snapshots.append({'path':path.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'capturedAtUtc':base.utc(),'readerConnections':count})
        return count
    def cleanup(self):
        valid=connection_log_attachments_valid(self.report,self.folder)
        self.report['connectionLogAttachmentsValidated']=valid
        if self.report.get('status')=='passed' and not valid:self.report['status']='failed';self.report['failure']={'type':'AuditFailure','message':'ConnectionLogAttachmentIntegrityFailed'}
        super().cleanup()
    def scenarios(self,ports):
        super().scenarios(ports)
        admin=base.HttpClient('http://127.0.0.1:'+str(ports['gateway']),True,self.secrets)
        # Reuse only the actual already-established administrator credentials, never a reconstructed identity.
        code,_=admin.request('POST','/api/auth/login',{'username':self.admin_username,'password':self.admin_password})
        if code!=200:raise base.AuditFailure('ChildAdminLoginFailed')
        source=self.http('child-create-source',admin,'POST','/api/metadata/datasources',{200},{'name':'workflow-quality-business-reader','type':'postgresql','host':'127.0.0.1','port':self.report['ports']['postgres'],'databaseName':'aetheris_api_key_audit','username':'audit_source_reader','password':self.reader_password,'status':'active'})
        datasource=int(source['id'])
        rule=self.http('child-create-rule',admin,'POST','/api/quality/rules',{200},{'name':'workflow-child-approved-rule','datasourceId':datasource,'targetTable':'business.orders','ruleType':'sql','expression':'SELECT COUNT(*) FROM business.orders WHERE amount<0','threshold':1,'status':'enabled'})
        rule_id=int(rule['id']);rule_prefix='/api/quality/rules/'+str(rule_id)
        state=self.http('child-rule-state',admin,'GET',rule_prefix+'/approval',{200});self.http('child-rule-approve',admin,'POST',rule_prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        actor_name='child_actor_'+self.run_id[:8];password=self.secret();actor=self.http('child-create-actor',admin,'POST','/api/auth/users',{201},{'username':actor_name,'password':password,'role':'viewer','status':'active'})
        actor_id=int(actor['id']);user=base.HttpClient(admin.base,True,self.secrets);self.http('child-actor-login',user,'POST','/api/auth/login',{200},{'username':actor_name,'password':password})
        approver_name='child_approver_'+self.run_id[:8];password=self.secret();approver=self.http('child-create-approver',admin,'POST','/api/auth/users',{201},{'username':approver_name,'password':password,'role':'viewer','status':'active'})
        approver_id=int(approver['id']);approve=base.HttpClient(admin.base,True,self.secrets);self.http('child-approver-login',approve,'POST','/api/auth/login',{200},{'username':approver_name,'password':password})
        def grant(label,uid,kind,rid,actions):return self.http(label,admin,'POST','/api/auth/rbac/grants',{200,201},{'granteeType':'user','granteeId':uid,'resourceType':kind,'resourceId':rid,'actions':actions,'enabled':True})
        actor_rule=grant('child-grant-rule-actor',actor_id,'quality_rule',rule_id,['execute']);approver_rule=grant('child-grant-rule-approver',approver_id,'quality_rule',rule_id,['execute'])
        parent=sql.task_view_id(self.http('child-create-parent',admin,'POST','/api/workflow/tasks',{200},{'name':'approved-workflow-quality-parent','taskType':'quality','qualityRuleId':rule_id,'timeoutSeconds':45,'maxRetries':0,'status':'enabled'}));prefix='/api/workflow/tasks/'+str(parent)
        grant('child-grant-parent-actor',actor_id,'workflow_task',parent,['view','execute']);grant('child-grant-parent-approver',approver_id,'workflow_task',parent,['view','update'])
        self.http('child-unapproved-denied',admin,'POST',prefix+'/run',{409},{'bizDate':'2024-02-29'})
        self.check('child-unapproved-zero-results',self.sql('SELECT count(*) FROM quality.quality_check_result WHERE rule_id='+str(rule_id),True)==0 and self.sql('SELECT count(*) FROM workflow.task_instance WHERE task_id='+str(parent),True)==0)
        state=self.http('child-parent-state',approve,'GET',prefix+'/approval',{200});self.http('child-parent-approve',approve,'POST',prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        self.check('child-parent-pins-rule',self.sql('SELECT quality_execution_revision IS NOT NULL AND quality_definition_sha256 IS NOT NULL AND execution_approval IS NOT NULL FROM workflow.task WHERE id='+str(parent))=='t')
        before=self.reader_connections();result=self.http('child-manual-success',user,'POST',prefix+'/run',{200},{'bizDate':'2024-02-29'},{'X-User':'admin','X-Username':'admin','X-Role':'admin'})
        proof=self.sql("""SELECT json_build_object('state',i.status,'parentKind',a.invocation_json->>'kind','actor',(a.invocation_json->>'uid')::bigint,
            'parentApprover',(a.approval_json->>'uid')::bigint,'childKind',q.invocation_json->>'kind','childActor',(q.invocation_json->>'uid')::bigint,
            'sameActor',a.invocation_json-'kind'=q.invocation_json-'kind','sameParent',q.parent_run_id=i.id AND q.parent_attempt_id=a.id AND q.parent_fence=a.fencing_token AND q.parent_manifest_sha256=a.manifest_sha256,
            'childId',a.quality_result_id=q.id,'childHash',a.quality_manifest_sha256=q.manifest_sha256,'receipt',a.metadata_receipt_id=q.metadata_receipt_id,
            'receiptOrigin',r.origin,'receiptState',r.status,'violations',q.violation_count,'threshold',q.threshold_value,'childFailureCode',q.failure_code,'parentFailureCode',a.error_code)
            FROM workflow.task_instance i JOIN workflow.execution_attempt a ON a.id=i.active_attempt_id
            LEFT JOIN quality.quality_check_result q ON q.parent_run_id=i.id AND q.parent_attempt_id=a.id AND q.parent_fence=a.fencing_token LEFT JOIN metadata.execution_receipt r ON r.id=q.metadata_receipt_id WHERE i.id="""+str(result['id']),True)
        self.check('child-exact-subject-parent-and-receipt',result.get('status')=='success' and proof['state']=='success' and proof['parentKind']=='manual' and proof['childKind']=='workflow-child' and proof['actor']==actor_id and proof['childActor']==actor_id and proof['parentApprover']==approver_id and all(proof[k] is True for k in ('sameActor','sameParent','childId','childHash','receipt')) and proof['receiptOrigin']=='quality' and proof['receiptState']=='succeeded' and proof['violations']==1 and proof['threshold']==1,evidence=proof)
        after=self.reader_connections();self.check('child-reader-connection-proven',after>before,before=before,after=after,attachments=self.report['connectionLogSnapshots'][-2:])
        self.http('child-no-arbitrary-datasource-query',user,'POST','/api/metadata/datasources/'+str(datasource)+'/query',{403},{'sql':'SELECT 1'})
        direct=base.HttpClient('http://127.0.0.1:'+str(ports['quality']));self.http('child-no-workload-submit-denied',direct,'POST','/internal/v2/workflow-children/submit',{401},{'instanceId':result['id'],'attemptId':1,'fence':1,'executionRevision':1,'manifestSha256':'a'*64});self.http('child-no-workload-status-denied',direct,'POST','/internal/v2/workflow-children/status',{401},{})
        datasource_target=self.sql("SELECT id FROM metadata.data_source WHERE name='workflow-target-writer'",True)
        target=sql.task_view_id(self.http('child-create-sql-target',admin,'POST','/api/workflow/tasks',{200},{'name':'child-gated-sql-target','taskType':'sql','datasourceId':datasource_target,'taskSql':"INSERT INTO business.sql_output(id,label,biz_date) VALUES(20,'质量门禁下游','${bizDate}')",'timeoutSeconds':30,'maxRetries':0,'status':'enabled'}))
        self.http('child-add-sql-dependency',admin,'POST','/api/workflow/tasks/'+str(target)+'/dependencies?upstreamTaskId='+str(parent),{200});grant('child-grant-sql-actor',actor_id,'workflow_task',target,['view','execute'])
        state=self.http('child-sql-state',admin,'GET','/api/workflow/tasks/'+str(target)+'/approval',{200});self.http('child-sql-approve',admin,'POST','/api/workflow/tasks/'+str(target)+'/approve',{200},{'expectedRevision':state['executionRevision']})
        started=self.http('child-pipeline-start',user,'POST','/api/workflow/pipelines',{200},{'targetTaskId':target,'bizDate':'2024-03-02'});run_id=int(started['id']);deadline=time.monotonic()+55;state=None
        while time.monotonic()<deadline:
            state=self.sql('SELECT status FROM workflow.pipeline_run WHERE id='+str(run_id))
            if state not in ('queued','running'):break
            time.sleep(.2)
        diagnostic=self.sql("SELECT coalesce(json_agg(json_build_object('nodeState',n.status,'attemptState',a.status,'attemptError',a.error_code,'childState',q.status,'childError',q.failure_code,'metadataState',r.status,'metadataError',r.safe_error)),'[]'::json) FROM workflow.pipeline_node_run n LEFT JOIN workflow.execution_attempt a ON a.id=n.attempt_id LEFT JOIN quality.quality_check_result q ON q.parent_attempt_id=a.id AND q.parent_run_id=a.instance_id AND q.parent_fence=a.fencing_token LEFT JOIN metadata.execution_receipt r ON r.id=q.metadata_receipt_id WHERE n.pipeline_run_id="+str(run_id),True)
        self.check('child-pipeline-real-success',state=='succeeded',observedState=state,evidence=diagnostic)
        self.check('child-pipeline-exact-source-and-write',self.sql("SELECT count(*) FROM workflow.pipeline_node_run n JOIN workflow.execution_attempt a ON a.id=n.attempt_id JOIN quality.quality_check_result q ON q.id=a.quality_result_id WHERE n.pipeline_run_id="+str(run_id)+" AND n.status='succeeded' AND a.invocation_json->>'kind'='pipeline' AND q.invocation_json->>'kind'='workflow-child' AND q.parent_attempt_id=a.id AND q.parent_manifest_sha256=a.manifest_sha256",True)==1 and self.sql("SELECT json_build_array(label,biz_date::text,writer) FROM business.sql_output WHERE id=20",True)==['质量门禁下游','2024-03-02','audit_target_writer'])
        fire=datetime.now(ZoneInfo('Asia/Shanghai'))+timedelta(seconds=20);cron=f'{fire.second} {fire.minute} {fire.hour} {fire.day} {fire.month} ? {fire.year}'
        cron_parent=sql.task_view_id(self.http('child-create-cron-parent',admin,'POST','/api/workflow/tasks',{200},{'name':'approved-quality-cron-parent','taskType':'quality','qualityRuleId':rule_id,'timeoutSeconds':45,'maxRetries':0,'cronExpression':cron,'status':'enabled'}));cron_prefix='/api/workflow/tasks/'+str(cron_parent)
        grant('child-grant-cron-actor',actor_id,'workflow_task',cron_parent,['view','execute']);grant('child-grant-cron-approver',approver_id,'workflow_task',cron_parent,['view','update'])
        state=self.http('child-cron-state',approve,'GET',cron_prefix+'/approval',{200});self.http('child-cron-approve',approve,'POST',cron_prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        state=self.http('child-cron-binding-state',user,'GET',cron_prefix+'/approval',{200});self.http('child-cron-bind',user,'POST',cron_prefix+'/cron-binding',{200},{'expectedRevision':state['executionRevision'],'expectedGeneration':state['scheduleGeneration']})
        deadline=time.monotonic()+55;completed=[]
        while time.monotonic()<deadline:
            completed=self.sql("SELECT coalesce(json_agg(json_build_object('status',status,'id',id,'bizDate',biz_date) ORDER BY id),'[]'::json) FROM workflow.task_instance WHERE task_id="+str(cron_parent),True)
            if completed and completed[0]['status'] not in ('running','queued','submitting'):break
            time.sleep(.2)
        self.check('child-cron-real-quartz-success',len(completed)==1 and completed[0]['status']=='success')
        proof=self.sql("SELECT json_build_object('kind',a.invocation_json->>'kind','uid',(a.invocation_json->>'uid')::bigint,'childKind',q.invocation_json->>'kind','bound',a.source_schedule_generation=t.schedule_version AND a.source_binding_generation=t.cron_binding_generation,'parentMatch',q.parent_attempt_id=a.id AND q.parent_manifest_sha256=a.manifest_sha256,'day',i.biz_date,'attempts',i.attempt) FROM workflow.task t JOIN workflow.task_instance i ON i.task_id=t.id JOIN workflow.execution_attempt a ON a.id=i.active_attempt_id JOIN quality.quality_check_result q ON q.id=a.quality_result_id WHERE t.id="+str(cron_parent),True)
        expected=(fire.date()-timedelta(days=1)).isoformat();self.check('child-cron-exact-actor-generation-and-date',proof['kind']=='cron' and proof['uid']==actor_id and proof['childKind']=='workflow-child' and proof['bound'] is True and proof['parentMatch'] is True and proof['day']==expected and proof['attempts']==1,evidence=proof)
        self.http('child-revoke-rule-actor',admin,'DELETE','/api/auth/rbac/grants/'+str(actor_rule['id']),{204});before=self.reader_connections()
        denied=self.http('child-revoked-run-unconfirmed',user,'POST',prefix+'/run',{200},{'bizDate':'2024-03-01'})
        after=self.reader_connections();self.check('child-revocation-zero-reader-connections',denied.get('status') in ('unknown','failed') and after==before and self.sql("SELECT count(*) FROM quality.quality_check_result WHERE parent_run_id="+str(denied['id'])+" AND status IN ('unknown','error')",True)==1,observedState=denied.get('status'),before=before,after=after,attachments=self.report['connectionLogSnapshots'][-2:])
        grant('child-regrant-rule-actor',actor_id,'quality_rule',rule_id,['execute'])
        denied_rule=self.http('child-create-denial-rule',admin,'POST','/api/quality/rules',{200},{'name':'independent-parent-approver-denial','datasourceId':datasource,'targetTable':'business.orders','ruleType':'sql','expression':'SELECT COUNT(*) FROM business.orders WHERE amount<0','threshold':1,'status':'enabled'});denied_rule_id=int(denied_rule['id']);denied_rule_prefix='/api/quality/rules/'+str(denied_rule_id)
        state=self.http('child-denial-rule-state',admin,'GET',denied_rule_prefix+'/approval',{200});self.http('child-denial-rule-approve',admin,'POST',denied_rule_prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        grant('child-grant-denial-rule-actor',actor_id,'quality_rule',denied_rule_id,['execute']);denial_rule_grant=grant('child-grant-denial-rule-approver',approver_id,'quality_rule',denied_rule_id,['execute'])
        denied_parent=sql.task_view_id(self.http('child-create-denial-parent',admin,'POST','/api/workflow/tasks',{200},{'name':'separate-parent-approver-denial','taskType':'quality','qualityRuleId':denied_rule_id,'timeoutSeconds':45,'maxRetries':0,'status':'enabled'}));denied_prefix='/api/workflow/tasks/'+str(denied_parent)
        grant('child-grant-denial-parent-actor',actor_id,'workflow_task',denied_parent,['view','execute']);grant('child-grant-denial-parent-approver',approver_id,'workflow_task',denied_parent,['view','update'])
        state=self.http('child-denial-parent-state',approve,'GET',denied_prefix+'/approval',{200});self.http('child-denial-parent-approve',approve,'POST',denied_prefix+'/approve',{200},{'expectedRevision':state['executionRevision']})
        self.http('child-revoke-parent-approver',admin,'DELETE','/api/auth/rbac/grants/'+str(denial_rule_grant['id']),{204});before=self.reader_connections();denied=self.http('child-parent-approver-revoked-run',user,'POST',denied_prefix+'/run',{200},{'bizDate':'2024-03-03'})
        after=self.reader_connections();self.check('child-parent-approver-revoked-zero-reader-connections',denied.get('status') in ('unknown','failed') and after==before,observedState=denied.get('status'),before=before,after=after,attachments=self.report['connectionLogSnapshots'][-2:])
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);report=Audit(p.parse_args().output).run();print('Workflow quality child '+report['status']+'; checks='+str(len(report['checks']))+'; cleanup='+str(all(report['cleanup'].values())));return 0 if report['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

#!/usr/bin/env python3
"""Five real v2 applications, ephemeral keys and disposable PostgreSQL/Redis; no existing environment."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-published-key-v2'
MODULES={'auth':'services/auth','system':'services/system','metadata':'services/metadata','serving':'services/serving','gateway':'apps/gateway'}
LABEL='aetheris.published-key-v2.audit'
CASES=('admin-login','create-owner','owner-login','create-reviewer','reviewer-login','register-reader','sync-business-table','search-business-table','unique-business-table',
       'create-domain','create-metric','submit-metric-review','approve-metric-review','publish-metric','published-metric-snapshot','create-table-asset','publish-table-asset','create-metric-asset','publish-metric-asset','create-other-asset','publish-other-asset','create-draft-asset',
       'grant-asset-table','grant-asset-metric','grant-target-table','grant-target-metric','pure-key-client-has-no-cookie',
       'grant-api-contract-create','delegated-api-contract-create','delegated-created-owner','delegated-datasource-write-denied','revoke-api-contract-create',
       'table-key-success','table-real-rows','metric-key-success','metric-real-value','spoofed-owner-headers-still-key-owner','spoofed-response-real-rows','cross-asset-denied','draft-asset-denied','missing-key-denied','invalid-key-denied',
       'raw-sql-parameter-denied','adjacent-key-management-denied','key-arbitrary-query-denied','key-wrong-method-denied',
       'quota-first-success','quota-first-rows','quota-second-denied','revoke-key','revoked-key-denied','expired-key-denied',
       'revoke-table-permission','table-permission-revoked-denied','restore-table-domain-grant','domain-grant-real-rows','domain-result-verified',
       'remove-domain-grant','grant-wrong-domain','wrong-domain-denied','remove-wrong-domain','grant-field-only','field-scope-denied','remove-field-grant','restore-real-domain',
       'revoke-asset-permission','asset-permission-revoked-denied','restore-asset-permission','rotate-key','rotated-old-key-denied','rotated-new-key-success','rotated-new-rows','rotation-owner-tuple-preserved',
       'owner-disable','disabled-owner-denied','owner-enable','old-access-version-still-denied','deprecate-metric-api','deprecated-api-denied',
       'metadata-direct-no-proof-denied','serving-redeem-no-proof-denied','owner-audit-provenance','real-service-nonces','business-reader-cannot-read-platform',
       'grant-audit-page-read','reviewer-business-query','reviewer-readonly-write-denied','rejected-query-zero-side-effect',
       'admin-global-audit-summary','owner-own-audit-summary','forged-role-audit-summary','audit-summaries-use-auth-scope',
       'ordinary-audit-trusted-request-id','ordinary-audit-has-no-driver-detail')
REQUIRED_CASES=frozenset((*CASES,*(label+'-'+name for name in ('main','quota','revoked','expired') for label in ('request-key','approve-key'))))
spec=importlib.util.spec_from_file_location('published_fixed_base',ROOT/'scripts/audit-fixed-execution-chain.py')
fixed=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixed)
# This import is private to this harness. No URL, service, command or destination is supplied by the caller.
fixed.MODULES=MODULES;fixed.OUTPUT=OUTPUT;fixed.LABEL=LABEL;fixed.REQUIRED_CASES=REQUIRED_CASES
identity=fixed.identity;identity.MODULES=MODULES;identity.OUTPUT=OUTPUT;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
base=fixed.base;REQUIRED_CLEANUP=fixed.REQUIRED_CLEANUP
def source_hashes():
    values=fixed.source_hashes();values[Path(__file__).relative_to(ROOT).as_posix()]=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();return values
def finalize_status(report):return identity.finalize_status(report)
def validate_output(value):return identity.validate_output(value)

def audit_scope_matches(admin,owner,forged,counts):
    try:
        for summary in (admin,owner,forged):
            if any(type(summary.get(key)) is not int or summary[key]<0 for key in ('total','success','failed')):return False
        return (owner==forged and owner['total']==counts['ownerTotal'] and owner['success']==counts['ownerSuccess']
                and owner['failed']==counts['ownerFailed'] and admin['total']==owner['total']+2
                and admin['success']==owner['success']+1 and admin['failed']==owner['failed']+1 and owner['success']>=6)
    except (KeyError,TypeError):return False

class Audit(fixed.Audit):
    def __init__(self,output):
        super().__init__(output);self.report.update(purpose='isolated-five-real-applications-published-key-v2',limits='Published table/metric query and key identity only; not workflow, browser, all engines or production deployment')
    def setup_database(self):
        identity.Audit.setup_database(self);self.reader_password=self.secret()
        self.sql("CREATE SCHEMA business; CREATE TABLE business.orders(id integer PRIMARY KEY,name text NOT NULL,amount integer NOT NULL); INSERT INTO business.orders VALUES(1,'甲',10),(2,'乙',20),(3,'丙',30); CREATE ROLE audit_business_reader LOGIN PASSWORD "+fixed.literal(self.reader_password)+"; GRANT CONNECT ON DATABASE aetheris_api_key_audit TO audit_business_reader; GRANT USAGE ON SCHEMA business TO audit_business_reader; GRANT SELECT ON business.orders TO audit_business_reader;")
    def scenarios(self,ports):
        self.checkpoint('real-v2-published-key-business-query');url='http://127.0.0.1:'+str(ports['gateway']);admin=base.HttpClient(url,True,self.secrets)
        self.http('admin-login',admin,'POST','/api/auth/login',{200},{'username':self.admin_username,'password':self.admin_password})
        owner='published_owner_'+self.run_id[:8];password=self.secret();person=self.http('create-owner',admin,'POST','/api/auth/users',{201},{'username':owner,'password':password,'role':'viewer','status':'active'})
        uid=int(person['id']);user=base.HttpClient(url,True,self.secrets);self.http('owner-login',user,'POST','/api/auth/login',{200},{'username':owner,'password':password})
        reviewer='published_reviewer_'+self.run_id[:8];review_password=self.secret();self.http('create-reviewer',admin,'POST','/api/auth/users',{201},{'username':reviewer,'password':review_password,'role':'admin','status':'active'})
        review=base.HttpClient(url,True,self.secrets);self.http('reviewer-login',review,'POST','/api/auth/login',{200},{'username':reviewer,'password':review_password})
        source=self.http('register-reader',admin,'POST','/api/metadata/datasources',{200},{'name':'published-reader','type':'postgresql','host':'127.0.0.1','port':self.report['ports']['postgres'],'databaseName':'aetheris_api_key_audit','username':'audit_business_reader','password':self.reader_password,'status':'active'})
        source_id=int(source['id']);sync=self.http('sync-business-table',admin,'POST','/api/metadata/tables/sync',{200},{'datasourceId':source_id,'schemaName':'business'})
        if isinstance(sync,dict) and sync.get('failures'):raise base.AuditFailure('TableSyncPartialFailure')
        tables=self.http('search-business-table',admin,'GET','/api/metadata/tables/search?keyword=orders&limit=50',{200});found=[t for t in tables if t.get('datasourceId')==source_id and t.get('schemaName')=='business' and t.get('tableName')=='orders']
        self.check('unique-business-table',len(found)==1);table_id=int(found[0]['id'])
        domain=self.http('create-domain',admin,'POST','/api/metadata/business-domains',{200},{'domainCode':'key_domain','domainName':'Key fixture domain','status':'active'})
        domain_id=int(domain['id']);domain_code=domain['domainCode'];self.sql('UPDATE metadata.metadata_table SET business_domain_id='+str(domain_id)+' WHERE id='+str(table_id))
        code='KEY_METRIC_'+self.run_id[:8];metric=self.http('create-metric',admin,'POST','/api/metadata/metric-definitions',{200},{'metricCode':code,'metricName':'Published total','sourceTableId':table_id,'businessDomainId':domain_id,'aggregation':'SUM','measureColumn':'amount','defaultTimeGrain':'none'})
        metric_id=int(metric['id']);code=metric['metricCode'];request=self.http('submit-metric-review',admin,'POST','/api/metadata/semantic-reviews/metric_definition/'+str(metric_id),{200},{'changeNote':'Isolated published metric acceptance'})
        self.http('approve-metric-review',review,'POST','/api/metadata/semantic-reviews/'+str(request['id'])+'/approve',{200},{'comment':'Approved fixture definition'})
        published=self.http('publish-metric',admin,'POST','/api/metadata/metric-definitions/'+str(metric_id)+'/publish',{200})
        self.check('published-metric-snapshot',published.get('id')==metric_id and published.get('metricCode')==code and published.get('status')=='published' and published.get('publishedVersion')==published.get('version') and isinstance(published.get('version'),int) and published['version']>0)
        assets={}
        for kind in ('table','metric','other','draft'):
            asset_code='KEY_'+kind.upper()+'_'+self.run_id[:8];target_type='metric' if kind=='metric' else 'table';ref=code if kind=='metric' else str(table_id)
            asset=self.http('create-'+kind+'-asset',admin,'POST','/api/metadata/api-assets',{200},{'assetCode':asset_code,'assetName':'Published '+kind,'apiPath':'/api/serving/v1/apis/'+asset_code+'/query','httpMethod':'POST','version':'v1','targetType':target_type,'targetRef':ref});assets[kind]=asset
            if kind!='draft':self.http('publish-'+kind+'-asset',admin,'POST','/api/metadata/api-assets/'+str(asset['id'])+'/publish',{200})
        def grant(label,kind,rid,action,scope=None):
            body={'granteeType':'user','granteeId':uid,'resourceType':kind,'resourceId':rid,'actions':[action],'enabled':True}
            if scope is not None:body['dataScope']=scope
            return self.http(label,admin,'POST','/api/auth/rbac/grants',{200,201},body)
        asset_grant=grant('grant-asset-table','data_service',int(assets['table']['id']),'execute');grant('grant-asset-metric','data_service',int(assets['metric']['id']),'execute')
        table_grant=grant('grant-target-table','table',table_id,'query');grant('grant-target-metric','metric',metric_id,'query',{'kind':'domain','values':[domain_code]})
        create_grant=grant('grant-api-contract-create','data_service',None,'create');delegated_code='KEY_DELEGATED_'+self.run_id[:8]
        created=self.http('delegated-api-contract-create',user,'POST','/api/metadata/api-assets',{200},{'assetCode':delegated_code,'assetName':'Delegated contract','apiPath':'/api/serving/v1/apis/'+delegated_code+'/query','httpMethod':'POST','version':'v1','targetType':'table','targetRef':str(table_id)})
        self.check('delegated-created-owner',created.get('createdBy')==owner and created.get('status')=='registered')
        self.http('delegated-datasource-write-denied',user,'POST','/api/metadata/datasources',{403},{'name':'must-not-create','type':'postgresql','host':'127.0.0.1','port':self.report['ports']['postgres'],'databaseName':'aetheris_api_key_audit','username':'audit_business_reader','password':'fixture-only'})
        self.http('revoke-api-contract-create',admin,'DELETE','/api/auth/rbac/grants/'+str(create_grant['id']),{204})
        keys={}
        for name in ('main','quota','revoked','expired'):
            application=self.http('request-key-'+name,user,'POST','/api/serving/api-key-applications',{200},{'name':'published-'+name,'justification':'Isolated API key acceptance','quotaPerMinute':1 if name=='quota' else 100})
            issued=self.http('approve-key-'+name,admin,'POST','/api/serving/api-key-applications/'+str(application['id'])+'/approve',{200});keys[name]=issued;self.secrets.append(issued['secret'])
        key_client=base.HttpClient(url);self.check('pure-key-client-has-no-cookie',key_client.jar is None)
        path=lambda kind:'/api/serving/v1/apis/'+assets[kind]['assetCode']+'/query'
        table_body={'columns':['id','name','amount'],'limit':10};metric_body={'limit':10};key=keys['main']['secret']
        def call(label,kind='table',expected={200},secret=None,body=None,extra=None,method='POST',route=None):
            return self.http(label,key_client,method,route or path(kind),expected,table_body if body is None and kind!='metric' else metric_body if body is None else body,base.pure_key_headers(secret or key,extra))
        def verify_rows(label,result):self.check(label,result.get('rowCount')==3 and result.get('columns')==['id','name','amount'] and sorted(result.get('rows',[]),key=lambda row:row[0])==[[1,'甲',10],[2,'乙',20],[3,'丙',30]])
        verify_rows('table-real-rows',call('table-key-success'))
        metric_result=call('metric-key-success','metric');self.check('metric-real-value',metric_result.get('rowCount')==1 and metric_result.get('rows') and float(metric_result['rows'][0][0])==60)
        verify_rows('spoofed-response-real-rows',call('spoofed-owner-headers-still-key-owner',extra={'X-Aetheris-User-Proof':'untrusted-proof','X-Aetheris-Identity':'untrusted-owner','X-User-Name':self.admin_username,'X-User-Id':str(uid),'X-User-Role':'admin','X-Request-Id':'published-spoof-test'}))
        call('cross-asset-denied','other',{403});call('draft-asset-denied','draft',{404})
        self.http('missing-key-denied',key_client,'POST',path('table'),{401},table_body);call('invalid-key-denied',expected={401},secret='ak_invalid_fixture_never_issued')
        call('raw-sql-parameter-denied',expected={400},body={'sql':'SELECT * FROM auth.auth_user'})
        call('adjacent-key-management-denied',expected={400,401,403,404,405},route='/api/serving/api-keys',method='GET')
        call('key-arbitrary-query-denied',expected={400,401,403,404},route='/api/serving/v1/query',body={'datasourceId':source_id,'sql':'select 1'})
        call('key-wrong-method-denied',expected={400,401,403,404,405},method='PUT')
        verify_rows('quota-first-rows',call('quota-first-success',secret=keys['quota']['secret']));call('quota-second-denied',expected={429},secret=keys['quota']['secret'])
        self.http('revoke-key',admin,'POST','/api/serving/api-keys/'+str(keys['revoked']['summary']['id'])+'/revoke',{200});call('revoked-key-denied',expected={401},secret=keys['revoked']['secret'])
        self.sql("UPDATE serving.service_api_key SET expires_at=clock_timestamp()-interval '1 second' WHERE id="+str(keys['expired']['summary']['id']));call('expired-key-denied',expected={401},secret=keys['expired']['secret'])
        def remove(label,item):self.http(label,admin,'DELETE','/api/auth/rbac/grants/'+str(item['id']),{204})
        remove('revoke-table-permission',table_grant);call('table-permission-revoked-denied',expected={403});table_grant=grant('restore-table-domain-grant','table',table_id,'query',{'kind':'domain','values':[domain_code]});verify_rows('domain-result-verified',call('domain-grant-real-rows'))
        remove('remove-domain-grant',table_grant);bad=grant('grant-wrong-domain','table',table_id,'query',{'kind':'domain','values':['other_domain']});call('wrong-domain-denied',expected={403});remove('remove-wrong-domain',bad)
        field=grant('grant-field-only','table',table_id,'query',{'kind':'field','values':['id']});call('field-scope-denied',expected={403});remove('remove-field-grant',field);grant('restore-real-domain','table',table_id,'query',{'kind':'domain','values':[domain_code]})
        remove('revoke-asset-permission',asset_grant);call('asset-permission-revoked-denied',expected={403});grant('restore-asset-permission','data_service',int(assets['table']['id']),'execute')
        rotated=self.http('rotate-key',admin,'POST','/api/serving/api-keys/'+str(keys['main']['summary']['id'])+'/rotate',{200});self.secrets.append(rotated['secret']);call('rotated-old-key-denied',expected={401});key=rotated['secret'];verify_rows('rotated-new-rows',call('rotated-new-key-success'))
        preserved=self.sql('SELECT json_build_object(\'ownerUid\',owner_uid,\'av\',owner_access_version,\'username\',owner_username) FROM serving.service_api_key WHERE id='+str(rotated['summary']['id']),True)
        self.check('rotation-owner-tuple-preserved',preserved['ownerUid']==uid and preserved['username']==owner)
        # A global metric catalogue grant must not expose other users' query activity.
        grant('grant-audit-page-read','metric',None,'view')
        read_request='audit-read-'+self.run_id[:8];deny_request='audit-deny-'+self.run_id[:8]
        self.http('reviewer-business-query',review,'POST','/api/metadata/query',{200},
                  {'datasourceId':source_id,'sql':'SELECT id,name FROM business.orders'},{'X-Request-Id':read_request})
        self.http('reviewer-readonly-write-denied',review,'POST','/api/metadata/query',{400},
                  {'datasourceId':source_id,'sql':'DELETE FROM business.orders'},{'X-Request-Id':deny_request})
        self.check('rejected-query-zero-side-effect',self.sql('SELECT to_json(count(*)) FROM business.orders',True)==3)
        global_summary=self.http('admin-global-audit-summary',review,'GET','/api/metadata/query-audit/summary',{200})
        owner_summary=self.http('owner-own-audit-summary',user,'GET','/api/metadata/query-audit/summary',{200})
        forged_summary=self.http('forged-role-audit-summary',user,'GET','/api/metadata/query-audit/summary?global=true&username='+reviewer,
                                 {200},None,{'X-User-Role':'admin','X-User-Name':reviewer})
        counts=self.sql("SELECT json_build_object('ownerTotal',count(*),'ownerSuccess',count(*) FILTER(WHERE status='success'),'ownerFailed',count(*) FILTER(WHERE status<>'success')) FROM metadata.semantic_query_audit WHERE username="+fixed.literal(owner),True)
        self.check('audit-summaries-use-auth-scope',audit_scope_matches(global_summary,owner_summary,forged_summary,counts),counts=counts)
        ordinary=self.sql("SELECT json_build_object('count',count(*),'requestIds',json_agg(request_id ORDER BY id),'badProvenance',count(*) FILTER(WHERE api_key_id IS NOT NULL OR admission_id IS NOT NULL OR owner_uid IS NOT NULL),'badError',count(*) FILTER(WHERE status='failed' AND (error_code IS DISTINCT FROM 'SQL_POLICY_REJECTED' OR error_message IS DISTINCT FROM 'Query failed; correlate with requestId')),'badHash',count(*) FILTER(WHERE query_hash IS NULL OR query_hash !~ '^[a-f0-9]{64}$')) FROM metadata.semantic_query_audit WHERE query_kind='adhoc' AND username="+fixed.literal(reviewer),True)
        self.check('ordinary-audit-trusted-request-id',ordinary['count']==2 and ordinary['requestIds']==[read_request,deny_request] and ordinary['badProvenance']==0,counts=ordinary)
        self.check('ordinary-audit-has-no-driver-detail',ordinary['count']==2 and ordinary['badError']==0 and ordinary['badHash']==0,counts=ordinary)
        self.http('deprecate-metric-api',admin,'POST','/api/metadata/api-assets/'+str(assets['metric']['id'])+'/deprecate',{200});call('deprecated-api-denied','metric',{404})
        self.http('owner-disable',admin,'PUT','/api/auth/users/'+str(uid),{200},{'role':'viewer','status':'disabled'});call('disabled-owner-denied',expected={403})
        self.http('owner-enable',admin,'PUT','/api/auth/users/'+str(uid),{200},{'role':'viewer','status':'active'});call('old-access-version-still-denied',expected={403})
        self.http('metadata-direct-no-proof-denied',base.HttpClient('http://127.0.0.1:'+str(ports['metadata'])),'POST','/internal/v2/published-api-assets/key-query',{401},{'admissionId':str(uuid.uuid4()),'admissionSha256':'a'*64,'parametersSha256':'b'*64})
        self.http('serving-redeem-no-proof-denied',base.HttpClient('http://127.0.0.1:'+str(ports['serving'])),'POST','/internal/v2/published-key-admissions/redeem',{401},{'admissionId':str(uuid.uuid4()),'admissionSha256':'a'*64,'parametersSha256':'b'*64})
        audit=self.sql("SELECT json_build_object('success',count(*) FILTER(WHERE status='success'),'badOwner',count(*) FILTER(WHERE owner_uid<>"+str(uid)+" OR username<>"+fixed.literal(owner)+" OR api_key_id IS NULL OR admission_id IS NULL OR request_id IS NULL),'badDatasource',count(*) FILTER(WHERE datasource_id<>"+str(source_id)+")) FROM metadata.semantic_query_audit WHERE query_kind='published_api'",True)
        self.check('owner-audit-provenance',audit['success']>=6 and audit['badOwner']==0 and audit['badDatasource']==0,counts=audit)
        nonces=self.sql("SELECT json_build_object('servingMetadata',(SELECT count(*) FROM serving.internal_request_nonce WHERE caller='metadata'),'metadataServing',(SELECT count(*) FROM metadata.internal_request_nonce WHERE caller='serving'),'authMetadata',(SELECT count(*) FROM auth.internal_request_nonce WHERE caller='metadata'))",True);self.check('real-service-nonces',all(n>0 for n in nonces.values()),counts=nonces)
        boundary=self.sql("SELECT to_json(has_schema_privilege('audit_business_reader','auth','USAGE') OR has_table_privilege('audit_business_reader','auth.auth_user','SELECT'))",True);self.check('business-reader-cannot-read-platform',boundary is False)
    def run(self):
        self.report['sourceHashes']=source_hashes();self.report['migrationSourceHashes']=base.database_contract.source_manifest()
        try:
            self.java=base.resolve_java21();self.report['jdk']={'executable':self.java,'specificationVersion':'21'};redis=self.setup_containers();self.setup_database();self.generate_keys();jars=self.build();ports=self.start_apps(jars,redis);self.scenarios(ports)
            if source_hashes()!=self.report['sourceHashes']:raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd']=True;self.report['status']='passed'
        except (Exception,KeyboardInterrupt) as exc:self.report['status']='failed';self.report['failure']={'type':type(exc).__name__,'message':base.redact(str(exc),self.secrets) if isinstance(exc,base.AuditFailure) else 'Operation failed; inspect sanitized logs'}
        finally:self.cleanup()
        return self.report
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(OUTPUT/uuid.uuid4().hex/'report.json'));r=Audit(parser.parse_args().output).run();print('Published key v2 '+r['status']+'; checks='+str(len(r['checks']))+'; cleanup='+str(all(r['cleanup'].values())));return 0 if r['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

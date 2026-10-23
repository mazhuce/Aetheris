#!/usr/bin/env python3
"""Six real applications and disposable PG/Redis: source-sealed transfer and quality execution."""
from __future__ import annotations
import argparse,base64,hashlib,importlib.util,json,os,re,secrets,shutil,subprocess,sys,time,urllib.error,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-fixed-execution-chain'
MODULES={'auth':'services/auth','system':'services/system','metadata':'services/metadata','ingestion':'services/ingestion','quality':'services/quality','gateway':'apps/gateway'}
LABEL='aetheris.fixed-execution-chain.audit'
REQUIRED_CASES=frozenset(('admin-login','admin-create-operator','operator-login','register-source','register-target','create-transfer',
    'unapproved-transfer-denied','unapproved-transfer-zero-writes','transfer-approval-state','approve-transfer','grant-transfer-execute',
    'operator-arbitrary-query-denied','operator-transfer-success','transfer-real-rows','transfer-actor-approval-seal','transfer-idempotent-result',
    'transfer-no-second-effect','revoke-transfer-execute','revoked-transfer-denied','revoked-transfer-zero-new-run',
    'create-quality','quality-approval-state','approve-quality','grant-quality-execute','operator-quality-failed-threshold',
    'quality-result-and-quarantine-atomic','quality-actor-approval-seal','metadata-no-workload-denied','source-lookup-no-workload-denied',
    'old-transfer-route-denied','durable-real-service-nonces'))
spec=importlib.util.spec_from_file_location('fixed_execution_identity_base',ROOT/'scripts/audit-identity-chain.py')
identity=importlib.util.module_from_spec(spec);spec.loader.exec_module(identity)
# A private imported module instance; all values are fixed here, never runtime caller input.
identity.OUTPUT=OUTPUT;identity.MODULES=MODULES;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
base=identity.base
REQUIRED_CLEANUP=identity.REQUIRED_CLEANUP

def source_hashes():
    values=identity.source_hashes()
    values[Path(__file__).relative_to(ROOT).as_posix()]=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    return values

def finalize_status(report):return identity.finalize_status(report)
def validate_output(value):return identity.validate_output(value)
def clean_environment():return identity.clean_environment()

def runtime_role(service):return base.database_contract.role(base.database_contract.services()[service])

def literal(value):return "'"+str(value).replace("'","''")+"'"

class Audit(identity.Audit):
    def __init__(self,output):
        super().__init__(output)
        self.report.update(purpose='isolated-six-real-application-fixed-execution',limits='Manual transfer and quality segments, not workflow/Spark/Flink/pipeline, browser, MySQL or production deployment')
    def jvm_memory_flags(self):return ('-Xms32m','-Xmx256m','-XX:MaxMetaspaceSize=192m','-XX:ReservedCodeCacheSize=64m')
    def setup_database(self):
        super().setup_database()
        self.reader_password=self.secret();self.target_reader_password=self.secret();self.writer_password=self.secret()
        self.sql("""
            CREATE SCHEMA business;
            CREATE TABLE business.orders(id integer PRIMARY KEY,label text NOT NULL,amount integer NOT NULL);
            INSERT INTO business.orders VALUES(1,'甲',10),(2,'乙',-1),(3,'丙',30);
            CREATE TABLE business.copied_orders(id integer,label text,amount integer);
            CREATE ROLE audit_source_reader LOGIN PASSWORD %s;
            CREATE ROLE audit_target_reader LOGIN PASSWORD %s;
            CREATE ROLE audit_target_writer LOGIN PASSWORD %s;
            GRANT CONNECT ON DATABASE aetheris_api_key_audit TO audit_source_reader,audit_target_reader,audit_target_writer;
            GRANT USAGE ON SCHEMA business TO audit_source_reader,audit_target_reader,audit_target_writer;
            GRANT SELECT ON business.orders TO audit_source_reader;
            GRANT SELECT ON business.copied_orders TO audit_target_reader;
            GRANT INSERT,DELETE ON business.copied_orders TO audit_target_writer;
            """%(literal(self.reader_password),literal(self.target_reader_password),literal(self.writer_password)))
    def start_apps(self,jars,redis_password):
        self.checkpoint('start-'+str(len(MODULES))+'-real-applications')
        self.report['jvmMemoryFlags']=list(self.jvm_memory_flags())
        slots={name:base.reserve_port() for name in [*MODULES,'unused']};self.reservations.extend(sock for sock,_ in slots.values())
        ports={name:port for name,(_,port) in slots.items()};self.report['ports'].update(ports)
        self.admin_username='fixed_admin_'+self.run_id[:8];self.admin_password=self.secret()
        self.master=base64.b64encode(secrets.token_bytes(32)).decode('ascii');self.secrets.append(self.master)
        common=clean_environment();common.update(SERVER_ADDRESS='127.0.0.1',DB_HOST='127.0.0.1',DB_PORT=str(self.report['ports']['postgres']),
            DB_NAME='aetheris_api_key_audit',JWT_SECRET=self.secret(),REDIS_HOST='127.0.0.1',REDIS_PORT=str(self.report['ports']['redis']),REDIS_PASSWORD=redis_password,
            SPRING_FLYWAY_ENABLED='false',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',
            SERVER_TOMCAT_THREADS_MAX='20',SERVER_TOMCAT_THREADS_MIN_SPARE='2',AETHERIS_RATE_LIMIT_CAPACITY='1000',
            AETHERIS_SECURITY_REGISTRY_FILE=str(self.private_keys/'public-registry.json'),AETHERIS_OUTBOUND_ALLOWED_CIDRS='127.0.0.1/32',
            AETHERIS_FIXED_EXECUTION_RECEIPT_LIFETIME='PT2M',AETHERIS_FIXED_EXECUTION_WAIT_TIMEOUT='PT30S')
        for name,prefix in {'auth':'AUTH_SERVICE','system':'SYSTEM_SERVICE','metadata':'METADATA_SERVICE','ingestion':'INGESTION_SERVICE','quality':'QUALITY_SERVICE',
                            'serving':'SERVING_SERVICE','workflow':'WORKFLOW_SERVICE','governance':'GOVERNANCE_SERVICE','ai-agent':'AI_AGENT'}.items():
            common[prefix+'_HOST']='127.0.0.1';common[prefix+'_PORT']=str(ports.get(name,ports['unused']))
        for name in MODULES:
            env=common.copy();env['SERVER_PORT']=str(ports[name]);key=self.key_manifest['services'][name]
            env.update(AETHERIS_SECURITY_WORKLOAD_KID=key['workloadKid'],AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE=str(self.private_keys/key['workloadPrivateFile']))
            if name=='gateway':env.update(AETHERIS_SECURITY_USER_KID=key['userKid'],AETHERIS_SECURITY_USER_PRIVATE_FILE=str(self.private_keys/key['userPrivateFile']))
            else:
                env.update(DB_USER=runtime_role(name),DB_PASSWORD=self.base['credentials'][name]['runtime'])
                for field in ('REDIS_HOST','REDIS_PORT','REDIS_PASSWORD'):env.pop(field,None)
            if name=='auth':env.update(INITIAL_ADMIN_USERNAME=self.admin_username,INITIAL_ADMIN_PASSWORD=self.admin_password)
            if name=='metadata':env['AETHERIS_DATASOURCE_MASTER_KEY']=self.master
            if name not in ('auth','gateway'):env.pop('JWT_SECRET',None)
            slots[name][0].close();path=self.folder/(name+'.log');log=path.open('wb')
            try:process=subprocess.Popen([self.java,*identity.FLAGS,*self.jvm_memory_flags(),
                    '-XX:ActiveProcessorCount=2','-XX:+ExitOnOutOfMemoryError','-jar',str(jars[name])],cwd=self.folder,env=env,stdout=log,stderr=subprocess.STDOUT,
                    creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            except BaseException:log.close();raise
            self.processes.append((name,process,log,path));record={'service':name,'pid':process.pid,'health':'starting','springPidMatched':False}
            self.report.setdefault('processes',[]).append(record);client=base.HttpClient('http://127.0.0.1:'+str(ports[name]));deadline=time.monotonic()+100
            while True:
                if process.poll() is not None:raise base.AuditFailure(name+' exited before health readiness')
                try:
                    status,body=client.request('GET','/actuator/health')
                    if status==200 and isinstance(body,dict) and body.get('status')=='UP':break
                except (OSError,urllib.error.URLError,TimeoutError):pass
                if time.monotonic()>deadline:raise base.AuditFailure(name+' health readiness deadline')
                time.sleep(.5)
            observed=re.search(r'\bwith PID (\d+)\b',path.read_text(encoding='utf-8',errors='replace'))
            if not observed or int(observed.group(1))!=process.pid:raise base.AuditFailure('DirectJavaPidMismatch')
            record.update(health='UP',springPidMatched=True)
        return ports
    def scenarios(self,ports):
        self.checkpoint('real-source-metadata-auth-business-jdbc')
        gateway='http://127.0.0.1:'+str(ports['gateway']);admin=base.HttpClient(gateway,True,self.secrets)
        self.http('admin-login',admin,'POST','/api/auth/login',{200},{'username':self.admin_username,'password':self.admin_password})
        operator='fixed_operator_'+self.run_id[:8];password=self.secret()
        person=self.http('admin-create-operator',admin,'POST','/api/auth/users',{201},{'username':operator,'password':password,'role':'viewer','status':'active','nickname':'Fixed execution operator'})
        operator_id=int(person['id']);user=base.HttpClient(gateway,True,self.secrets)
        self.http('operator-login',user,'POST','/api/auth/login',{200},{'username':operator,'password':password})
        source={'name':'fixed-source-reader','type':'postgresql','host':'127.0.0.1','port':self.report['ports']['postgres'],'databaseName':'aetheris_api_key_audit',
                'username':'audit_source_reader','password':self.reader_password,'status':'active'}
        src=self.http('register-source',admin,'POST','/api/metadata/datasources',{200},source);source_id=int(src['id'])
        target={**source,'name':'fixed-target-writer','username':'audit_target_reader','password':self.target_reader_password,
                'writeUsername':'audit_target_writer','writePassword':self.writer_password}
        dst=self.http('register-target',admin,'POST','/api/metadata/datasources',{200},target);target_id=int(dst['id'])
        task=self.http('create-transfer',admin,'POST','/api/ingestion/tasks',{200},{'name':'sealed-transfer','sourceDatasourceId':source_id,'targetDatasourceId':target_id,
                'sourceSql':'SELECT id,label,amount FROM business.orders ORDER BY id','targetTable':'business.copied_orders','writeMode':'append','batchSize':2,'status':'enabled'})
        task_id=int(task['id']);prefix='/api/ingestion/tasks/'+str(task_id)
        self.http('unapproved-transfer-denied',admin,'POST',prefix+'/run',{409})
        self.check('unapproved-transfer-zero-writes',self.sql('SELECT count(*) FROM business.copied_orders',True)==0)
        approval=self.http('transfer-approval-state',admin,'GET',prefix+'/approval',{200})
        self.http('approve-transfer',admin,'POST',prefix+'/approve',{200},{'expectedRevision':approval['executionRevision']})
        grant=self.http('grant-transfer-execute',admin,'POST','/api/auth/rbac/grants',{200,201},{'granteeType':'user','granteeId':operator_id,'resourceType':'ingestion_task','resourceId':task_id,'actions':['execute'],'enabled':True})
        self.http('operator-arbitrary-query-denied',user,'POST','/api/metadata/datasources/'+str(source_id)+'/query',{403},{'sql':'select 1'})
        transfer=self.http('operator-transfer-success',user,'POST',prefix+'/run',{200},headers={'Idempotency-Key':'fixed-chain-once'})
        self.check('transfer-real-rows',transfer.get('status')=='success' and transfer.get('rowsRead')==3 and transfer.get('rowsWritten')==3
                and self.sql("SELECT json_agg(json_build_array(id,label,amount) ORDER BY id) FROM business.copied_orders",True)==[[1,'甲',10],[2,'乙',-1],[3,'丙',30]])
        run_id=int(transfer['id']);self.report['runBindings']={'ingestionRunId':run_id}
        seal=self.sql("SELECT json_build_object('uid',(invocation_json->>'uid')::bigint,'kind',invocation_json->>'kind','approvedUid',(approval_json->>'uid')::bigint,'hashOk',manifest_sha256=encode(sha256(convert_to(sealed_manifest::text,'UTF8')),'hex'),'receipt',metadata_receipt_id,'state',status) FROM ingestion.ingestion_run WHERE id="+str(run_id),True)
        self.check('transfer-actor-approval-seal',seal['uid']==operator_id and seal['kind']=='manual' and seal['approvedUid']!=operator_id and seal['hashOk'] is True and int(seal['receipt'])>0 and seal['state']=='success')
        repeated=self.http('transfer-idempotent-result',user,'POST',prefix+'/run',{200},headers={'Idempotency-Key':'fixed-chain-once'})
        self.check('transfer-no-second-effect',repeated['id']==run_id and self.sql('SELECT count(*) FROM business.copied_orders',True)==3
                and self.sql("SELECT count(*) FROM metadata.execution_receipt WHERE origin='ingestion'",True)==1)
        self.http('revoke-transfer-execute',admin,'DELETE','/api/auth/rbac/grants/'+str(grant['id']),{204})
        self.http('revoked-transfer-denied',user,'POST',prefix+'/run',{403})
        self.check('revoked-transfer-zero-new-run',self.sql('SELECT count(*) FROM ingestion.ingestion_run WHERE task_id='+str(task_id),True)==1 and self.sql('SELECT count(*) FROM business.copied_orders',True)==3)
        rule=self.http('create-quality',admin,'POST','/api/quality/rules',{200},{'name':'sealed-quality','ruleType':'range','datasourceId':source_id,'targetTable':'business.orders','expression':'amount >= 0','threshold':0,'status':'enabled'})
        rule_id=int(rule['id']);quality='/api/quality/rules/'+str(rule_id)
        state=self.http('quality-approval-state',admin,'GET',quality+'/approval',{200})
        self.http('approve-quality',admin,'POST',quality+'/approve',{200},{'expectedRevision':state['executionRevision']})
        self.http('grant-quality-execute',admin,'POST','/api/auth/rbac/grants',{200,201},{'granteeType':'user','granteeId':operator_id,'resourceType':'quality_rule','resourceId':rule_id,'actions':['execute'],'enabled':True})
        result=self.http('operator-quality-failed-threshold',user,'POST',quality+'/run',{200});result_id=int(result['id']);self.report['runBindings']['qualityResultId']=result_id
        quarantine=self.sql("SELECT json_build_object('count',count(*),'target',min(target_table)) FROM quality.quality_quarantine_record WHERE result_id="+str(result_id),True)
        self.check('quality-result-and-quarantine-atomic',result.get('status')=='failed' and result.get('violationCount')==1 and quarantine=={'count':1,'target':'business.orders'})
        sealed=self.sql("SELECT json_build_object('uid',(invocation_json->>'uid')::bigint,'approvedUid',(approval_json->>'uid')::bigint,'hashOk',manifest_sha256=encode(sha256(convert_to(sealed_manifest::text,'UTF8')),'hex'),'receipt',metadata_receipt_id) FROM quality.quality_check_result WHERE id="+str(result_id),True)
        self.check('quality-actor-approval-seal',sealed['uid']==operator_id and sealed['approvedUid']!=operator_id and sealed['hashOk'] is True and int(sealed['receipt'])>0)
        direct_metadata=base.HttpClient('http://127.0.0.1:'+str(ports['metadata']));direct_source=base.HttpClient('http://127.0.0.1:'+str(ports['ingestion']))
        self.http('metadata-no-workload-denied',direct_metadata,'POST','/internal/v2/executions/status',{401,403},{'receiptId':None,'runId':run_id,'attemptId':0,'fence':1,'operation':'metadata.fixed-transfer.submit'})
        self.http('source-lookup-no-workload-denied',direct_source,'POST','/internal/v2/executions/lookup',{401,403},{'runId':run_id,'fence':1})
        self.http('old-transfer-route-denied',direct_metadata,'POST','/internal/ingestion/transfer',{400},{'sourceDatasourceId':source_id,'targetDatasourceId':target_id,'sourceSql':'select 1','targetTable':'business.copied_orders'})
        nonces=self.sql("SELECT json_build_object('authMetadata',(SELECT count(*) FROM auth.internal_request_nonce WHERE caller='metadata'),'metadataIngestion',(SELECT count(*) FROM metadata.internal_request_nonce WHERE caller='ingestion'),'metadataQuality',(SELECT count(*) FROM metadata.internal_request_nonce WHERE caller='quality'),'ingestionMetadata',(SELECT count(*) FROM ingestion.internal_request_nonce WHERE caller='metadata'),'qualityMetadata',(SELECT count(*) FROM quality.internal_request_nonce WHERE caller='metadata'))",True)
        self.check('durable-real-service-nonces',all(isinstance(value,int) and value>0 for value in nonces.values()),counts=nonces)
    def run(self):
        self.report['sourceHashes']=source_hashes();self.report['migrationSourceHashes']=base.database_contract.source_manifest()
        try:
            self.java=base.resolve_java21();self.report['jdk']={'executable':self.java,'specificationVersion':'21'}
            redis=self.setup_containers();self.setup_database();self.generate_keys();jars=self.build();ports=self.start_apps(jars,redis)
            self.scenarios(ports)
            if source_hashes()!=self.report['sourceHashes']:raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd']=True;self.report['status']='passed'
        except (Exception,KeyboardInterrupt) as exc:
            self.report['status']='failed';self.report['failure']={'type':type(exc).__name__,'message':base.redact(str(exc),self.secrets) if isinstance(exc,base.AuditFailure) else 'Operation failed; inspect sanitized logs'}
        finally:self.cleanup()
        return self.report

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default=str(OUTPUT/uuid.uuid4().hex/'report.json'))
    report=Audit(parser.parse_args().output).run()
    print('Fixed execution chain '+report['status']+'; checks='+str(len(report['checks']))+'; cleanup='+str(all(report['cleanup'].values())))
    return 0 if report['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

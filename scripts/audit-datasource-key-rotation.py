#!/usr/bin/env python3
"""Disposable PG rekey/rollback plus the current Java crypto implementation; no existing DB/key changes."""
from __future__ import annotations
import argparse,base64,hashlib,importlib.util,json,os,re,secrets,shutil,subprocess,sys,time,uuid
from datetime import datetime,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-datasource-key-rotation'
LABEL='aetheris.datasource-key-rotation.audit'
IMAGE='postgres:16-alpine'
DATABASE='aetheris_rekey_audit'
REQUIRED_CASES=frozenset(('actual-backup-restored-with-chinese-and-credential-ciphertexts','offline-preparation-did-not-change-database',
    'private-plan-hashes-and-row-counts','late-row-error-rolls-back-the-entire-transaction','actual-transaction-rotated-both-and-kept-null-empty',
    'java-reader-decrypts-new-key-both-passwords','old-java-key-refuses-new-ciphertext','apply-replay-rejected-without-change',
    'wrong-database-rollback-refused','new-row-refuses-rollback-without-change','late-stale-fence-refuses-rollback-without-change',
    'rollback-restores-exact-ciphertexts-and-advances-fence','rollback-java-old-key-readable','rollback-replay-rejected-without-change',
    'apply-wrong-oid-refused','no-plaintext-or-key-in-generated-files','source-unchanged'))
REQUIRED_CLEANUP=frozenset(('ownedContainerAbsent','privateInputsRemoved','privatePlanRemoved','noBackgroundProcessesStarted'))
FILES=['V1__init.sql','V4__add_datasource_password.sql','V5__datasource_connection_options.sql',
       'V9__datasource_password_encryption.sql','V17__datasource_connection_health.sql','V28__separate_business_write_credentials.sql']
spec=importlib.util.spec_from_file_location('rotation_audit_tool',ROOT/'scripts/security/datasource_key_rotation.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)


def utc():return datetime.now(timezone.utc).isoformat()
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def finalize(report):
    checks=report.get('checks',[]);names=[c.get('case') for c in checks]
    return 'passed' if report.get('status')=='passed' and set(names)==REQUIRED_CASES and len(names)==len(REQUIRED_CASES) and all(c.get('passed') is True for c in checks) and report.get('sourceHashesMatchAtEnd') is True and set(report.get('cleanup',{}))==REQUIRED_CLEANUP and all(v is True for v in report['cleanup'].values()) else 'failed'
def source_hashes():
    paths=[Path(__file__),ROOT/'scripts/security/datasource_key_rotation.py',ROOT/'scripts/security/ProbeDatasourceCrypto.java',
           ROOT/'scripts/database/contract.py',ROOT/'scripts/database/database_roles.py',ROOT/'scripts/requirements-audit.txt',
           ROOT/'services/metadata/src/main/java/com/aetheris/metadata/service/DataSourceSecretService.java']
    paths.extend(ROOT/'services/metadata/src/main/resources/db/migration'/name for name in FILES)
    return {p.relative_to(ROOT).as_posix():digest(p) for p in paths}


class Audit:
    def __init__(self,output,evidence):
        self.run_id=uuid.uuid4().hex;self.output=Path(output).resolve();self.evidence=Path(evidence).resolve()
        if OUTPUT.resolve() not in self.output.parents or self.output.exists():raise ValueError('New owned audit output required')
        self.output.parent.mkdir(parents=True,exist_ok=True);self.folder=self.output.parent/self.run_id;self.folder.mkdir()
        self.private=tool.PRIVATE/('rekey-audit-'+self.run_id);self.plan=tool.PRIVATE/('master-key-'+self.run_id)
        self.cid=None;self.report={'schemaVersion':1,'runId':self.run_id,'purpose':'isolated-offline-datasource-rekey',
            'startedAtUtc':utc(),'status':'failed','checks':[],'limits':'Six exact source SQL fixture files, not full Flyway/platform upgrade; no installed keys or existing database used'}
        self.env={k:v for k,v in os.environ.items() if not k.upper().startswith(('PG','POSTGRES_','AETHERIS_','DB_','SPRING_'))
                  and k.upper() not in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','CLASSPATH')}
    def command(self,args,*,stdin=None,env=None,allow_failure=False,timeout=45):
        r=subprocess.run([str(x) for x in args],input=stdin,capture_output=True,text=True,encoding='utf-8',errors='replace',
            env=self.env if env is None else env,timeout=timeout,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        if r.returncode and not allow_failure:raise RuntimeError('Owned audit command failed; sensitive output withheld')
        return r
    def sql(self,sql,database=DATABASE,allow_failure=False):
        r=self.command(['docker','exec','-i',self.cid,'psql','-X','-U','audit_admin','-d',database,'-v','ON_ERROR_STOP=1','-Atq'],stdin=sql,allow_failure=allow_failure)
        return r if allow_failure else r.stdout.strip()
    def rows(self,database=DATABASE):
        return json.loads(self.sql("SELECT coalesce(json_agg(json_build_object('id',id,'connectionRevision',connection_revision,'password',password,'writePassword',write_password) ORDER BY id),'[]'::json) FROM metadata.data_source",database))
    def check(self,name,passed):
        self.report['checks'].append({'case':name,'passed':bool(passed)})
        if not passed:raise RuntimeError('Audit assertion failed: '+name)
    def probe(self,operation,key,value,allow_failure=False):
        content='operation='+operation+'\nkey='+base64.b64encode(key).decode()+'\nvalue='+base64.b64encode(value.encode()).decode()+'\n'
        result=self.command([self.java,'--class-path',self.classpath,'--source','21',ROOT/'scripts/security/ProbeDatasourceCrypto.java'],stdin=content,allow_failure=allow_failure)
        return result if allow_failure else base64.b64decode(result.stdout.strip()).decode('utf-8')
    def prepare_java(self):
        java_home=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21'));self.java=java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
        v=self.command([self.java,'-XshowSettings:properties','-version'])
        if not re.search(r'java\.specification\.version\s*=\s*21\s',v.stderr):raise ValueError('JDK21 required')
        evidence=json.loads(self.evidence.read_text(encoding='utf-8-sig'));source='services/metadata/src/main/java/com/aetheris/metadata/service/DataSourceSecretService.java';compiled='com/aetheris/metadata/service/DataSourceSecretService.class'
        original=ROOT/'services/metadata/target/classes'/compiled
        if evidence['source']['files'][source]!=digest(ROOT/source) or evidence['compiled']['files'][compiled]!=digest(original):raise ValueError('Current crypto class/source lack matching tested evidence')
        target=self.folder/'classes'/compiled;target.parent.mkdir(parents=True);target.write_bytes(original.read_bytes())
        dependencies=[Path.home()/'.m2/repository/org/springframework'/name/'6.2.8'/(name+'-6.2.8.jar') for name in ['spring-beans','spring-context']]
        if any(not p.is_file() for p in dependencies):raise ValueError('Reviewed local Spring annotation dependencies required')
        self.classpath=os.pathsep.join(str(p) for p in [self.folder/'classes',*dependencies])
        self.report['javaCrypto']={'sourceSha256':digest(ROOT/source),'classSha256':digest(target),'evidenceSha256':digest(self.evidence),
            'dependencies':{p.name:digest(p) for p in dependencies}}
    def run(self):
        try:
            self.report['sourceHashes']=source_hashes();self.prepare_java()
            self.private.mkdir(parents=True,mode=0o700,exist_ok=False)
            self.report['imageId']=self.command(['docker','image','inspect',IMAGE,'--format','{{.Id}}']).stdout.strip()
            env=self.env.copy()
            env.update(POSTGRES_USER='audit_admin',POSTGRES_PASSWORD=secrets.token_urlsafe(32),POSTGRES_DB=DATABASE)
            self.cid=self.command(['docker','run','--rm','-d','--name','aetheris-rekey-'+self.run_id,'--label',LABEL+'='+self.run_id,
                '--network','none','--memory','512m','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true',
                '--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','--env','POSTGRES_USER','--env','POSTGRES_PASSWORD','--env','POSTGRES_DB',IMAGE],env=env).stdout.strip()
            self.report['containerId']=self.cid;deadline=time.monotonic()+40
            while self.command(['docker','exec',self.cid,'pg_isready','-U','audit_admin','-d',DATABASE],allow_failure=True).returncode:
                if time.monotonic()>deadline:raise RuntimeError('Readiness deadline')
                time.sleep(.2)
            migration='CREATE SCHEMA metadata; SET search_path=metadata;\n'+'\n'.join((ROOT/'services/metadata/src/main/resources/db/migration'/n).read_text(encoding='utf-8') for n in FILES)
            self.sql(migration);self.report['sourceSqlFixtureFiles']=FILES
            old,new=os.urandom(32),os.urandom(32);read_secret='隔离只读密码-'+secrets.token_hex(8);write_secret='隔离写入密码-'+secrets.token_hex(8)
            read_cipher=self.probe('encrypt',old,read_secret);write_cipher=self.probe('encrypt',old,write_secret)
            self.sql('DELETE FROM metadata.data_source; INSERT INTO metadata.data_source(id,name,type,host,password,write_password) VALUES (101,\'中文数据源\',\'postgresql\',\'fixture\','+tool.literal(read_cipher)+','+tool.literal(write_cipher)+'),(102,\'空凭据\',\'postgresql\',\'fixture\',NULL,\'\'),(103,\'只读凭据\',\'postgresql\',\'fixture\','+tool.literal(read_cipher)+',NULL);')
            before=self.rows();inventory=json.loads(self.sql("SELECT json_build_object('database',current_database(),'databaseOid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),'systemIdentifier',(SELECT system_identifier::text FROM pg_control_system()))"))
            self.command(['docker','exec',self.cid,'pg_dump','-Fc','-U','audit_admin','-d',DATABASE,'-f','/tmp/before.dump'])
            archive=self.private/'before.dump';self.command(['docker','cp',self.cid+':/tmp/before.dump',archive])
            backed_up=utc();self.command(['docker','exec',self.cid,'createdb','-U','audit_admin','rekey_restore'])
            self.command(['docker','exec',self.cid,'pg_restore','-U','audit_admin','-d','rekey_restore','--exit-on-error','--single-transaction','/tmp/before.dump'])
            self.check('actual-backup-restored-with-chinese-and-credential-ciphertexts',self.rows('rekey_restore')==before)
            receipt={'archive':str(archive),'sha256':digest(archive),'database':inventory['database'],'verifiedRestoreAtUtc':utc(),'backupCreatedAtUtc':backed_up,
                'sourceInventorySha256':tool.contract.digest(inventory),'sourceSystemIdentifier':inventory['systemIdentifier'],'sourceDatabaseOid':inventory['databaseOid']}
            snapshot={'schemaVersion':1,'inventorySha256':tool.contract.digest(inventory),'rows':before}
            for name,value in [('inventory.json',inventory),('snapshot.json',snapshot),('backup.json',receipt)]:
                p=self.private/name;p.write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8');os.chmod(p,0o600)
            for name,key in [('old.key',old),('new.key',new)]:
                p=self.private/name;p.write_text(base64.b64encode(key).decode(),encoding='ascii');os.chmod(p,0o600)
            command=[sys.executable,str(ROOT/'scripts/security/datasource_key_rotation.py')]
            for name,value in [('inventory','inventory.json'),('snapshot','snapshot.json'),('backup-receipt','backup.json'),('old-key-file','old.key'),('new-key-file','new.key')]:command.extend(['--'+name,str(self.private/value)])
            command.extend(['--directory',str(self.plan)]);self.command(command)
            report=json.loads((self.plan/'plan.json').read_text());apply=(self.plan/'apply.sql').read_text();rollback=(self.plan/'rollback.sql').read_text()
            self.check('offline-preparation-did-not-change-database',self.rows()==before)
            self.check('private-plan-hashes-and-row-counts',report['rows']==3 and report['changedRows']==2 and report['applySha256']==hashlib.sha256(apply.encode()).hexdigest() and report['rollbackSha256']==hashlib.sha256(rollback.encode()).hexdigest())
            self.sql("CREATE FUNCTION metadata.audit_rekey_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected late row rejection'; END $$; CREATE TRIGGER audit_rekey_reject BEFORE UPDATE ON metadata.data_source FOR EACH ROW WHEN (NEW.id=103) EXECUTE FUNCTION metadata.audit_rekey_reject();")
            self.check('late-row-error-rolls-back-the-entire-transaction',self.sql(apply,allow_failure=True).returncode!=0 and self.rows()==before)
            self.sql('DROP TRIGGER audit_rekey_reject ON metadata.data_source; DROP FUNCTION metadata.audit_rekey_reject();')
            self.sql(apply);after=self.rows();self.check('actual-transaction-rotated-both-and-kept-null-empty',after[0]['connectionRevision']==before[0]['connectionRevision']+1 and after[1]==before[1] and after[2]['connectionRevision']==before[2]['connectionRevision']+1)
            self.check('java-reader-decrypts-new-key-both-passwords',self.probe('decrypt',new,after[0]['password'])==read_secret and self.probe('decrypt',new,after[0]['writePassword'])==write_secret)
            self.check('old-java-key-refuses-new-ciphertext',self.probe('decrypt',old,after[0]['password'],True).returncode!=0)
            self.check('apply-replay-rejected-without-change',self.sql(apply,allow_failure=True).returncode!=0 and self.rows()==after)
            self.check('wrong-database-rollback-refused',self.sql(rollback,'rekey_restore',True).returncode!=0 and self.rows('rekey_restore')==before)
            self.sql("INSERT INTO metadata.data_source(id,name,type,host) VALUES(999,'audit new row','postgresql','fixture')")
            added=self.rows();self.check('new-row-refuses-rollback-without-change',self.sql(rollback,allow_failure=True).returncode!=0 and self.rows()==added)
            self.sql('DELETE FROM metadata.data_source WHERE id=999')
            stale=[dict(row) for row in after];stale[-1]['connectionRevision']+=1
            self.check('late-stale-fence-refuses-rollback-without-change',self.sql(tool.fenced_sql(inventory,stale,before),allow_failure=True).returncode!=0 and self.rows()==after)
            self.sql(rollback);restored=self.rows();self.check('rollback-restores-exact-ciphertexts-and-advances-fence',restored[0]['password']==before[0]['password'] and restored[0]['writePassword']==before[0]['writePassword'] and restored[0]['connectionRevision']==before[0]['connectionRevision']+2 and restored[1]==before[1])
            self.check('rollback-java-old-key-readable',self.probe('decrypt',old,restored[0]['password'])==read_secret)
            self.check('rollback-replay-rejected-without-change',self.sql(rollback,allow_failure=True).returncode!=0 and self.rows()==restored)
            # Put the original snapshot on a separately restored DB: the correct cluster but another OID still refuses.
            self.check('apply-wrong-oid-refused',self.sql(apply,'rekey_restore',True).returncode!=0 and self.rows('rekey_restore')==before)
            self.check('no-plaintext-or-key-in-generated-files',all(v not in apply+rollback+json.dumps(report) for v in [read_secret,write_secret,base64.b64encode(old).decode(),base64.b64encode(new).decode()]))
            self.report['sourceHashesMatchAtEnd']=source_hashes()==self.report['sourceHashes'];self.check('source-unchanged',self.report['sourceHashesMatchAtEnd'])
            self.report['planSummary']={k:report[k] for k in ['rows','changedRows','backupSha256','applySha256','rollbackSha256']}
            self.report['status']='passed'
        except Exception as error:self.report['failure']={'type':type(error).__name__,'message':'Isolated audit failed; sensitive details withheld'}
        finally:
            cleanup={}
            try:
                if self.cid:self.command(['docker','rm','-f',self.cid])
                cleanup['ownedContainerAbsent']=not self.command(['docker','ps','-aq','--filter','label='+LABEL+'='+self.run_id]).stdout.strip()
            except Exception:cleanup['ownedContainerAbsent']=False
            for name,path in [('privateInputsRemoved',self.private),('privatePlanRemoved',self.plan)]:
                try:
                    if path.resolve().parent!=tool.PRIVATE.resolve() or path.name not in ['rekey-audit-'+self.run_id,'master-key-'+self.run_id]:raise ValueError()
                    if path.exists():shutil.rmtree(path)
                    cleanup[name]=not path.exists()
                except Exception:cleanup[name]=False
            cleanup['noBackgroundProcessesStarted']=True;self.report['cleanup']=cleanup
            self.report['status']=finalize(self.report)
            self.report['completedAtUtc']=utc();self.output.write_text(json.dumps(self.report,indent=2),encoding='utf-8')
        return self.report


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True);parser.add_argument('--metadata-evidence',required=True)
    args=parser.parse_args();r=Audit(args.output,args.metadata_evidence).run();print('Rekey audit '+r['status']+'; checks='+str(len(r['checks']))+'; cleanup='+str(all(r['cleanup'].values())))
    return 0 if r['status']=='passed' else 1


if __name__=='__main__':sys.exit(main())

#!/usr/bin/env python3
"""Owned PostgreSQL V31 -> V33 upgrade and exact quality parent/fence constraints; remote receipts are fixtures."""
from __future__ import annotations
import argparse,hashlib,importlib.util,re,shutil,sys,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-workflow-quality-authority'
LABEL='aetheris.workflow-quality-authority.audit'
REQUIRED_CASES=frozenset(('v31-known-baseline','v34-upgrade-preserves-original-history','v32-preserves-sql-approved-projection',
    'fresh-real-postgres-runtime-tests','runtime-tests-unskipped-and-all-pass','quality-original-receipt-runtime-unskipped'))
spec=importlib.util.spec_from_file_location('quality_parent_pipeline_authority',ROOT/'scripts/audit-pipeline-authority.py')
authority=importlib.util.module_from_spec(spec);spec.loader.exec_module(authority)
uncertainty=authority.uncertainty;identity=authority.identity;base=authority.base
identity.OUTPUT=OUTPUT;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
uncertainty.TEST='com.aetheris.workflow.service.WorkflowQualityAuthorityPostgresIT'
uncertainty.EXPECTED_TEST_COUNT=19
original_sources=authority.source_hashes
def source_hashes():
    values=original_sources();paths=[Path(__file__),*ROOT.glob('services/quality/src/**/*'),*ROOT.glob('libs/security-protocol/src/**/*')]
    values.update({p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()});return values
uncertainty.source_hashes=source_hashes
class Audit(uncertainty.Audit):
    def __init__(self,output):
        super().__init__(output);self.report.update(purpose='isolated-workflow-quality-parent-authority-postgres',
            limits='Real PostgreSQL runtime roles, original history/SQL approval, quality parent/pipeline source, exact child/fence/result constraints. Child definitions/results/receipts and identities are controlled fixtures; not real HTTP/auth/business SQL or release.')
    def setup_database(self):
        self.checkpoint('known-v31-to-v34-upgrade-in-owned-database');self.prepare_database();self.runner({**self.base,'command':'provision-empty','sql':base.database.bootstrap()})
        historical=self.folder/'workflow-v31-migrations';historical.mkdir(exist_ok=False);current=ROOT/'services/workflow/src/main/resources/db/migration'
        legacy=[p for p in current.glob('V*__*.sql') if int(re.fullmatch(r'V([0-9]+)__.+[.]sql',p.name).group(1))<=31]
        if len(legacy)!=31:raise base.AuditFailure('ExpectedExactWorkflowV31History')
        for p in legacy:shutil.copyfile(p,historical/p.name)
        migrations=[]
        for service,schema in base.database_contract.services().items():
            migrations.append(self.runner({**self.base,'command':'migrate','schema':schema,'directory':str(historical if service=='workflow' else ROOT/'services'/service/'src/main/resources/db/migration')}))
        workflow_sources=sum(name.startswith('services/workflow/src/main/resources/db/migration/') for name in self.report['migrationSourceHashes'])
        expected_historical=len(self.report['migrationSourceHashes'])-workflow_sources+len(legacy)
        if sum(m['migrationsExecuted'] for m in migrations)!=expected_historical:raise base.AuditFailure('ExpectedExactHistoricalFixtureMigrations')
        self.report['historicalMigrationCount']=expected_historical
        self.sql("""INSERT INTO workflow.task(name,task_type,datasource_id,task_sql) VALUES('v31-preserved-sql-approval','sql',910001,'INSERT INTO business.output SELECT 1');
            UPDATE workflow.task SET execution_approval=jsonb_build_object('uid',43,'username','fixture-approver','accessVersion',4,'revision',execution_revision,'definitionSha256',execution_definition_sha256),execution_approved_at=clock_timestamp() WHERE name='v31-preserved-sql-approval';""")
        query="SELECT json_build_object('revision',execution_revision,'definition',execution_definition_json,'hash',execution_definition_sha256,'approval',execution_approval) FROM workflow.task WHERE name='v31-preserved-sql-approval'"
        sql_before=self.sql(query,True)
        before=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history",True)
        self.check('v31-known-baseline',self.sql("SELECT to_regproc('workflow.pipeline_binding_valid') IS NOT NULL AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='workflow' AND table_name='task' AND column_name='quality_execution_revision')")=='t')
        upgrade=self.runner({**self.base,'command':'migrate','schema':'workflow','directory':str(current)})
        after=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=31",True)
        self.check('v34-upgrade-preserves-original-history',before==after and upgrade['migrationsExecuted']==3 and upgrade['pending']==0 and upgrade['validated'] is True)
        self.check('v32-preserves-sql-approved-projection',sql_before==self.sql(query,True))
        self.report['upgrade']={'from':'workflow V31','to':'workflow V34','currentMigrationCount':len(self.report['migrationSourceHashes']),
            'scope':'Only workflow is historical; other service schemas use this run current sources',
            'historyPreserved':before==after,'sqlApprovalPreserved':sql_before==self.sql(query,True),'migration':upgrade,'historicalMigrationFileHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(legacy)}}
        self.report['migrations']=[*migrations,upgrade];snapshot=self.runner({**self.base,'command':'inventory'});base.database.check_owned_inventory(snapshot)
        self.runner({**self.base,'command':'sql','sql':'BEGIN;\n'+base.database_contract.grants_sql(snapshot)+'\nCOMMIT;'})
        self.runner({**self.base,'command':'verify','directories':base.database.directories()})
    def tests(self):
        super().tests();self.report['workflowTests']=dict(self.report['tests'])
        name='com.aetheris.quality.service.QualityReceiptReconciliationPostgresIT';suite=ROOT/'services/quality/target/surefire-reports'/('TEST-'+name+'.xml')
        prior=hashlib.sha256(suite.read_bytes()).hexdigest() if suite.exists() else None
        env=identity.clean_environment();env.update(AETHERIS_QUALITY_CHILD_TEST_URL='jdbc:postgresql://127.0.0.1:'+str(self.report['ports']['postgres'])+'/aetheris_api_key_audit',AETHERIS_QUALITY_CHILD_TEST_PASSWORD=self.base['credentials']['quality']['runtime'])
        mvn=shutil.which('mvn.cmd' if sys.platform=='win32' else 'mvn')
        if not mvn:raise base.AuditFailure('MavenMissing')
        code=0
        try:stdout=self.command([mvn,'-q','-o','-f',str(ROOT/'services/quality/pom.xml'),'-Dtest='+name.split('.')[-1],'-Daetheris.quality.receipt.audit='+self.run_id,'test'],env=env,timeout=180);(self.folder/'quality-receipt-it.log').write_text(base.redact(stdout,self.secrets),encoding='utf-8')
        except base.AuditFailure:code=1
        if not suite.is_file():raise base.AuditFailure('MissingQualityReceiptPostgresReport')
        xml=ET.fromstring(suite.read_bytes());tests={k:int(xml.get(k,'-1')) for k in ('tests','failures','errors','skipped')};props={p.get('name'):p.get('value') for p in xml.findall('properties/property')}
        archived=self.folder/'quality-receipt-it.xml';archived.write_text(identity.archive_evidence(suite,self.secrets),encoding='utf-8')
        details=suite.with_name(name+'.txt')
        if details.is_file():(self.folder/'quality-receipt-it.txt').write_text(identity.archive_evidence(details,self.secrets),encoding='utf-8')
        self.report['qualityReceiptTests']=tests;self.report['qualityReceiptTestExitCode']=code;self.report['qualityReceiptReportSha256']=hashlib.sha256(archived.read_bytes()).hexdigest()
        self.check('quality-original-receipt-runtime-unskipped',code==0 and xml.get('name')==name and props.get('aetheris.quality.receipt.audit')==self.run_id and hashlib.sha256(suite.read_bytes()).hexdigest()!=prior and tests=={'tests':7,'failures':0,'errors':0,'skipped':0})
        self.report['tests']={k:self.report['workflowTests'][k]+tests[k] for k in tests}
        self.report['compiledClasses'].update({p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'services/quality/target/classes').rglob('*')) if p.is_file()})
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);report=Audit(p.parse_args().output).run()
    print('Workflow quality authority '+report['status']+'; tests='+str(report.get('tests'))+'; cleanup='+str(all(report['cleanup'].values())))
    return 0 if report['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

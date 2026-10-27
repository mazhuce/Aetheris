#!/usr/bin/env python3
"""Four real PostgreSQL workflow state checks; remote execution is a controlled peer, not a live engine."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,re,shutil,sys,xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-execution-uncertainty'
LABEL='aetheris.execution-uncertainty.audit'
TEST='com.aetheris.workflow.service.WorkflowExecutionUncertaintyPostgresIT'
EXPECTED_TEST_COUNT=4
REQUIRED_CASES=frozenset(('v29-rejects-real-queued-claim','v30-v34-upgrade-preserves-original-history',
                        'fresh-real-postgres-runtime-tests','runtime-tests-unskipped-and-all-pass'))
spec=importlib.util.spec_from_file_location('execution_uncertainty_identity',ROOT/'scripts/audit-identity-chain.py')
identity=importlib.util.module_from_spec(spec);spec.loader.exec_module(identity)
identity.OUTPUT=OUTPUT;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
base=identity.base

def source_hashes():
    values=identity.source_hashes()
    paths=[Path(__file__),ROOT/'services/workflow/pom.xml',
           *ROOT.glob('services/workflow/src/main/**/*'),*ROOT.glob('services/workflow/src/test/**/*')]
    values.update({p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()})
    return values

class Audit(identity.Audit):
    def __init__(self,output):
        super().__init__(output)
        self.report.update(purpose='isolated-workflow-execution-uncertainty-postgres',
            limits='Real PostgreSQL runtime roles and workflow state/capacity only; remote worker responses are controlled peers. No trusted pipeline entry, real Spark/Flink, browser or release claim.')

    def setup_database(self):
        self.checkpoint('known-v29-to-v34-upgrade-in-owned-database')
        self.prepare_database();self.runner({**self.base,'command':'provision-empty','sql':base.database.bootstrap()})
        historical=self.folder/'workflow-v29-migrations';historical.mkdir(exist_ok=False)
        current=ROOT/'services/workflow/src/main/resources/db/migration'
        legacy_files=[p for p in current.glob('V*__*.sql') if int(re.fullmatch(r'V([0-9]+)__.+[.]sql',p.name).group(1))<=29]
        if len(legacy_files)!=29:raise base.AuditFailure('ExpectedExactWorkflowV29History')
        for source in legacy_files:shutil.copyfile(source,historical/source.name)
        migrations=[]
        for service,schema in base.database_contract.services().items():
            directory=historical if service=='workflow' else ROOT/'services'/service/'src/main/resources/db/migration'
            migrations.append(self.runner({**self.base,'command':'migrate','schema':schema,'directory':str(directory)}))
        current_count=len(self.report['migrationSourceHashes'])
        old_count=current_count-len(list(current.glob('V*__*.sql')))+len(legacy_files)
        if sum(m['migrationsExecuted'] for m in migrations)!=old_count:raise base.AuditFailure('ExpectedExactWorkflowV29FixtureMigrations')
        history=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=29",json_result=True)
        # This exact CHECK is the observed failure, not a fabricated failed report.
        self.sql("""DO $$ DECLARE task_key bigint; rejected_constraint text; BEGIN
          INSERT INTO workflow.task(name,task_type,quality_rule_id) VALUES('pre-v30-queued-probe','quality',77) RETURNING id INTO task_key;
          BEGIN
            INSERT INTO workflow.task_instance(task_id,biz_date,status,trigger_type) VALUES(task_key,'2026-10-25','queued','pipeline');
            RAISE EXCEPTION 'V29 unexpectedly accepted queued state';
          EXCEPTION WHEN check_violation THEN
            GET STACKED DIAGNOSTICS rejected_constraint=CONSTRAINT_NAME;
            IF rejected_constraint<>'chk_task_instance_status' THEN RAISE; END IF;
          END;
        END $$;""")
        self.check('v29-rejects-real-queued-claim',True)
        upgrade=self.runner({**self.base,'command':'migrate','schema':'workflow','directory':str(current)})
        after=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=29",json_result=True)
        self.report['upgrade']={'from':f'workflow V29 / {old_count} fixture migrations','to':f'workflow V34 / {current_count} current migrations',
                                'scope':'Only workflow is historical; other service schemas use this run current sources',
                                'historyPreserved':history==after,'migration':upgrade,
                                'historicalMigrationFileHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(legacy_files)}}
        self.check('v30-v34-upgrade-preserves-original-history',history==after and upgrade['migrationsExecuted']==5 and upgrade['pending']==0 and upgrade['validated'] is True)
        self.report['migrations']=[*migrations,upgrade]
        snapshot=self.runner({**self.base,'command':'inventory'});base.database.check_owned_inventory(snapshot)
        self.runner({**self.base,'command':'sql','sql':'BEGIN;\n'+base.database_contract.grants_sql(snapshot)+'\nCOMMIT;'})
        self.runner({**self.base,'command':'verify','directories':base.database.directories()})

    def tests(self):
        self.checkpoint('real-workflow-runtime-postgres-tests')
        suite=ROOT/'services/workflow/target/surefire-reports'/('TEST-'+TEST+'.xml')
        prior=hashlib.sha256(suite.read_bytes()).hexdigest() if suite.exists() else None
        env=identity.clean_environment()
        env.update(AETHERIS_WORKFLOW_UNCERTAINTY_URL='jdbc:postgresql://127.0.0.1:'+str(self.report['ports']['postgres'])+'/aetheris_api_key_audit',
                   AETHERIS_WORKFLOW_UNCERTAINTY_PASSWORD=self.base['credentials']['workflow']['runtime'],
                   AETHERIS_WORKFLOW_UNCERTAINTY_RUN=self.run_id)
        mvn=shutil.which('mvn.cmd' if sys.platform=='win32' else 'mvn')
        if not mvn:raise base.AuditFailure('MavenMissing')
        args=[mvn,'-q','-o','-f',str(ROOT/'services/workflow/pom.xml'),'-Dtest='+TEST.split('.')[-1],
              '-Daetheris.uncertainty.audit='+self.run_id,'test']
        try:
            stdout=self.command(args,env=env,timeout=180)
            self.report['testExitCode']=0
            (self.folder/'postgres-it.log').write_text(base.redact(stdout,self.secrets),encoding='utf-8')
        except base.AuditFailure:
            self.report['testExitCode']=1
        if not suite.is_file():raise base.AuditFailure('MissingRealPostgresTestReport')
        xml=ET.fromstring(suite.read_bytes())
        self.report['tests']={key:int(xml.get(key,'-1')) for key in ('tests','failures','errors','skipped')}
        archived=identity.archive_evidence(suite,self.secrets)
        report_path=self.folder/'postgres-it.xml';report_path.write_text(archived,encoding='utf-8')
        self.report['testReportSha256']=hashlib.sha256(report_path.read_bytes()).hexdigest()
        details=suite.with_name(TEST+'.txt')
        if details.is_file():(self.folder/'postgres-it.txt').write_text(identity.archive_evidence(details,self.secrets),encoding='utf-8')
        properties={p.get('name'):p.get('value') for p in xml.findall('properties/property')}
        self.check('fresh-real-postgres-runtime-tests',xml.get('name')==TEST and properties.get('aetheris.uncertainty.audit')==self.run_id
                   and hashlib.sha256(suite.read_bytes()).hexdigest()!=prior)
        self.check('runtime-tests-unskipped-and-all-pass',self.report['testExitCode']==0
                   and self.report['tests']=={'tests':EXPECTED_TEST_COUNT,'failures':0,'errors':0,'skipped':0})
        files=[p for p in (ROOT/'services/workflow/target/classes').rglob('*') if p.is_file()]
        self.report['compiledClasses']={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}

    def run(self):
        self.report['sourceHashes']=source_hashes();self.report['migrationSourceHashes']=base.database_contract.source_manifest()
        try:
            self.java=base.resolve_java21();self.report['jdk']={'executable':self.java,'specificationVersion':'21'}
            self.setup_containers();self.setup_database();self.tests()
            if source_hashes()!=self.report['sourceHashes']:raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd']=True;self.report['status']='passed'
        except (Exception,KeyboardInterrupt) as failure:
            self.report['status']='failed';self.report['failure']={'type':type(failure).__name__,
                'message':base.redact(str(failure),self.secrets) if isinstance(failure,base.AuditFailure) else 'Operation failed; inspect sanitized test report'}
        finally:self.cleanup()
        return self.report

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True)
    report=Audit(parser.parse_args().output).run()
    print('Workflow uncertainty '+report['status']+'; tests='+str(report.get('tests'))+'; cleanup='+str(all(report['cleanup'].values())))
    return 0 if report['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

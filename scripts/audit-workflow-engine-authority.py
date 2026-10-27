#!/usr/bin/env python3
"""Owned PostgreSQL V32 -> V33 -> V34 upgrades, one-shot dispatch, and controlled HTTP peers."""
from __future__ import annotations
import argparse,hashlib,importlib.util,re,shutil,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-workflow-engine-authority'
LABEL='aetheris.workflow-engine-authority.audit'
REQUIRED_CASES=frozenset(('v32-known-baseline','v33-upgrade-preserves-original-history','v33-preserves-sql-and-quality-approval',
    'v34-upgrade-preserves-original-history','v34-preserves-sql-and-quality-approval',
    'fresh-real-postgres-runtime-tests','runtime-tests-unskipped-and-all-pass'))
spec=importlib.util.spec_from_file_location('engine_authority_uncertainty',ROOT/'scripts/audit-execution-uncertainty.py')
uncertainty=importlib.util.module_from_spec(spec);spec.loader.exec_module(uncertainty)
identity=uncertainty.identity;base=uncertainty.base
identity.OUTPUT=OUTPUT;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
uncertainty.TEST='com.aetheris.workflow.service.WorkflowEngineAuthorityPostgresIT'
uncertainty.EXPECTED_TEST_COUNT=31
original_sources=uncertainty.source_hashes
def source_hashes():
    values=original_sources();values[Path(__file__).relative_to(ROOT).as_posix()]=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();return values
uncertainty.source_hashes=source_hashes
class Audit(uncertainty.Audit):
    def __init__(self,output):
        super().__init__(output);self.report.update(purpose='isolated-workflow-engine-authority-postgres-http',
            limits='Actual PostgreSQL runtime role, preserved migration history/SQL/quality approvals, actual loopback Spark/Flink transport and durable dispatch/receipt constraints. Actors/authorization decisions and engine business results are controlled peers. No real Spark/Flink engine, deployed Auth, browser, backend I/O sandbox or release claim.')
    def setup_database(self):
        self.checkpoint('known-v32-to-v33-upgrade-in-owned-database');self.prepare_database();self.runner({**self.base,'command':'provision-empty','sql':base.database.bootstrap()})
        current=ROOT/'services/workflow/src/main/resources/db/migration';historical=self.folder/'workflow-v32-migrations';historical.mkdir(exist_ok=False)
        legacy=[p for p in current.glob('V*__*.sql') if int(re.fullmatch(r'V([0-9]+)__.+[.]sql',p.name).group(1))<=32]
        if len(legacy)!=32:raise base.AuditFailure('ExpectedExactWorkflowV32History')
        for p in legacy:shutil.copyfile(p,historical/p.name)
        migrations=[]
        for service,schema in base.database_contract.services().items():
            migrations.append(self.runner({**self.base,'command':'migrate','schema':schema,'directory':str(historical if service=='workflow' else ROOT/'services'/service/'src/main/resources/db/migration')}))
        current_count=len(self.report['migrationSourceHashes']);old_count=current_count-len(list(current.glob('V*__*.sql')))+len(legacy)
        if sum(m['migrationsExecuted'] for m in migrations)!=old_count:raise base.AuditFailure('ExpectedExactWorkflowV32FixtureMigrations')
        self.sql("""INSERT INTO workflow.task(name,task_type,datasource_id,task_sql) VALUES('v32-preserved-sql-approval','sql',910001,'INSERT INTO business.output SELECT 1');
          INSERT INTO workflow.task(name,task_type,quality_rule_id,quality_execution_revision,quality_definition_sha256,timeout_seconds) VALUES('v32-preserved-quality-approval','quality',501,2,repeat('a',64),30);
          UPDATE workflow.task SET execution_approval=jsonb_build_object('uid',43,'username','fixture-approver','accessVersion',4,'revision',execution_revision,'definitionSha256',execution_definition_sha256),execution_approved_at=clock_timestamp() WHERE name IN ('v32-preserved-sql-approval','v32-preserved-quality-approval');""")
        query="SELECT json_agg(jsonb_build_object('name',name,'revision',execution_revision,'definition',execution_definition_json,'hash',execution_definition_sha256,'approval',execution_approval) ORDER BY name) FROM workflow.task WHERE name IN ('v32-preserved-sql-approval','v32-preserved-quality-approval')"
        approved=self.sql(query,True)
        before=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history",True)
        self.check('v32-known-baseline',self.sql("SELECT NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='workflow' AND table_name='task' AND column_name='engine_definition_json')")=='t')
        previous=self.folder/'workflow-v33-migrations';previous.mkdir(exist_ok=False)
        previous_files=[p for p in current.glob('V*__*.sql') if int(re.fullmatch(r'V([0-9]+)__.+[.]sql',p.name).group(1))<=33]
        if len(previous_files)!=33:raise base.AuditFailure('ExpectedExactWorkflowV33History')
        for p in previous_files:shutil.copyfile(p,previous/p.name)
        upgrade=self.runner({**self.base,'command':'migrate','schema':'workflow','directory':str(previous)})
        after=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=32",True)
        self.check('v33-upgrade-preserves-original-history',before==after and upgrade['migrationsExecuted']==1 and upgrade['pending']==0 and upgrade['validated'] is True)
        self.check('v33-preserves-sql-and-quality-approval',approved==self.sql(query,True))
        before_v34=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history",True)
        final_upgrade=self.runner({**self.base,'command':'migrate','schema':'workflow','directory':str(current)})
        after_v34=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=33",True)
        self.check('v34-upgrade-preserves-original-history',before_v34==after_v34 and final_upgrade['migrationsExecuted']==1 and final_upgrade['pending']==0 and final_upgrade['validated'] is True)
        self.check('v34-preserves-sql-and-quality-approval',approved==self.sql(query,True))
        self.report['upgrade']={'from':f'workflow V32 / {old_count} fixture migrations','intermediate':f'workflow V33 / {old_count+1} fixture migrations','to':f'workflow V34 / {current_count} current migrations',
            'scope':'Only workflow is historical; other service schemas use this run current sources',
            'historyPreserved':before==after,'v33HistoryPreserved':before_v34==after_v34,'sqlAndQualityApprovalPreserved':approved==self.sql(query,True),
            'migration':upgrade,'v34Migration':final_upgrade,'historicalMigrationFileHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(previous_files)}}
        self.report['migrations']=[*migrations,upgrade,final_upgrade];snapshot=self.runner({**self.base,'command':'inventory'});base.database.check_owned_inventory(snapshot)
        self.runner({**self.base,'command':'sql','sql':'BEGIN;\n'+base.database_contract.grants_sql(snapshot)+'\nCOMMIT;'})
        self.runner({**self.base,'command':'verify','directories':base.database.directories()})
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True);report=Audit(parser.parse_args().output).run()
    print('Workflow engine authority '+report['status']+'; tests='+str(report.get('tests'))+'; cleanup='+str(all(report['cleanup'].values())))
    return 0 if report['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

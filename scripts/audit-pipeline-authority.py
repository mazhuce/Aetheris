#!/usr/bin/env python3
"""Real PostgreSQL pipeline source constraints and V30 -> V33 upgrade; no live SQL engine claim."""
from __future__ import annotations
import argparse,hashlib,importlib.util,re,shutil,sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'output/audit-pipeline-authority'
LABEL='aetheris.pipeline-authority.audit'
REQUIRED_CASES=frozenset(('v30-baseline-without-trusted-pipeline-source','v31-v34-upgrade-preserves-original-history',
                        'fresh-real-postgres-runtime-tests','runtime-tests-unskipped-and-all-pass'))
spec=importlib.util.spec_from_file_location('pipeline_authority_uncertainty',ROOT/'scripts/audit-execution-uncertainty.py')
uncertainty=importlib.util.module_from_spec(spec);spec.loader.exec_module(uncertainty)
identity=uncertainty.identity;base=uncertainty.base
identity.OUTPUT=OUTPUT;identity.LABEL=LABEL;identity.REQUIRED_CASES=REQUIRED_CASES
uncertainty.TEST='com.aetheris.workflow.service.PipelineSqlAuthorityPostgresIT'
uncertainty.EXPECTED_TEST_COUNT=11
original_sources=uncertainty.source_hashes

def source_hashes():
    values=original_sources()
    paths=[Path(__file__),ROOT/'services/metadata/src/main/java/com/aetheris/metadata/fixedexecution/FixedSqlManifest.java']
    values.update({p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    return values
uncertainty.source_hashes=source_hashes

class Audit(uncertainty.Audit):
    def __init__(self,output):
        super().__init__(output)
        self.report.update(purpose='isolated-trusted-pipeline-sql-source-postgres',
            limits='Real PostgreSQL runtime role, immutable pipeline source/lease/fence/dependency and upgrade constraints. Identities, receipts and completion are controlled fixtures; no Auth/Metadata HTTP, actual business SQL, Spark/Flink, browser or release claim.')
    def setup_database(self):
        self.checkpoint('known-v30-to-v34-upgrade-in-owned-database')
        self.prepare_database();self.runner({**self.base,'command':'provision-empty','sql':base.database.bootstrap()})
        historical=self.folder/'workflow-v30-migrations';historical.mkdir(exist_ok=False)
        current=ROOT/'services/workflow/src/main/resources/db/migration'
        legacy=[p for p in current.glob('V*__*.sql') if int(re.fullmatch(r'V([0-9]+)__.+[.]sql',p.name).group(1))<=30]
        if len(legacy)!=30:raise base.AuditFailure('ExpectedExactWorkflowV30History')
        for path in legacy:shutil.copyfile(path,historical/path.name)
        migrations=[]
        for service,schema in base.database_contract.services().items():
            directory=historical if service=='workflow' else ROOT/'services'/service/'src/main/resources/db/migration'
            migrations.append(self.runner({**self.base,'command':'migrate','schema':schema,'directory':str(directory)}))
        workflow_sources=sum(name.startswith('services/workflow/src/main/resources/db/migration/') for name in self.report['migrationSourceHashes'])
        expected_historical=len(self.report['migrationSourceHashes'])-workflow_sources+len(legacy)
        if sum(m['migrationsExecuted'] for m in migrations)!=expected_historical:raise base.AuditFailure('ExpectedExactHistoricalFixtureMigrations')
        self.report['historicalMigrationCount']=expected_historical
        before=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=30",json_result=True)
        self.check('v30-baseline-without-trusted-pipeline-source',self.sql("SELECT to_regproc('workflow.pipeline_binding_valid') IS NULL")=='t')
        upgrade=self.runner({**self.base,'command':'migrate','schema':'workflow','directory':str(current)})
        after=self.sql("SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM workflow.flyway_schema_history WHERE version::int<=30",json_result=True)
        self.report['upgrade']={'from':'workflow V30','to':'workflow V34','currentMigrationCount':len(self.report['migrationSourceHashes']),
            'scope':'Only workflow is historical; other service schemas use this run current sources',
            'historyPreserved':before==after,'migration':upgrade,
            'historicalMigrationFileHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(legacy)}}
        self.check('v31-v34-upgrade-preserves-original-history',before==after and upgrade['migrationsExecuted']==4 and upgrade['pending']==0 and upgrade['validated'] is True)
        self.report['migrations']=[*migrations,upgrade]
        snapshot=self.runner({**self.base,'command':'inventory'})
        try:base.database.check_owned_inventory(snapshot)
        except ValueError as failure:raise base.AuditFailure('RuntimeInventoryRejected: '+str(failure)) from failure
        self.runner({**self.base,'command':'sql','sql':'BEGIN;\n'+base.database_contract.grants_sql(snapshot)+'\nCOMMIT;'})
        self.runner({**self.base,'command':'verify','directories':base.database.directories()})

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True)
    report=Audit(parser.parse_args().output).run()
    print('Pipeline authority '+report['status']+'; tests='+str(report.get('tests'))+'; cleanup='+str(all(report['cleanup'].values())))
    return 0 if report['status']=='passed' else 1
if __name__=='__main__':sys.exit(main())

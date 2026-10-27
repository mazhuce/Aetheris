import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('pipeline_authority_audit_test',ROOT/'scripts/audit-pipeline-authority.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class PipelineAuthorityAuditSafetyTests(unittest.TestCase):
    def test_reports_cannot_overwrite_or_escape_the_owned_output(self):
        for path in (ROOT/'report.json',audit.OUTPUT,audit.OUTPUT/'old.txt',ROOT/'docs/report.json'):
            with self.assertRaises(ValueError):audit.identity.validate_output(path)
    def test_all_checks_source_match_and_cleanup_are_mandatory(self):
        good={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'case':c,'passed':True} for c in audit.REQUIRED_CASES],
              'cleanup':dict.fromkeys(audit.identity.REQUIRED_CLEANUP,True)}
        candidates=[{**good,'checks':good['checks'][:-1]},{**good,'checks':good['checks']+[good['checks'][0]]},{**good,'sourceHashesMatchAtEnd':False}]
        candidates += [{**good,'cleanup':{k:True for k in audit.identity.REQUIRED_CLEANUP if k!=missing}} for missing in audit.identity.REQUIRED_CLEANUP]
        for report in candidates:audit.identity.finalize_status(report);self.assertEqual('failed',report['status'])
    def test_binds_exact_current_migrations_and_real_test_sources(self):
        sources=audit.source_hashes();migrations=audit.base.database_contract.source_manifest()
        self.assertEqual({p.relative_to(ROOT).as_posix() for p in ROOT.glob('services/*/src/main/resources/db/migration/*.sql')},set(migrations))
        self.assertEqual(migrations,{p:sources[p] for p in migrations})
        self.assertIn('services/auth/src/main/resources/db/migration/V11__browser_session_families.sql',migrations)
        for name in ('scripts/audit-pipeline-authority.py','scripts/audit-execution-uncertainty.py',
                     'services/workflow/src/main/resources/db/migration/V31__trusted_pipeline_sql_sources.sql',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/PipelineExecutionStore.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowExecutionStore.java',
                     'services/workflow/src/test/java/com/aetheris/workflow/service/PipelineSqlAuthorityPostgresIT.java',
                     'services/metadata/src/main/java/com/aetheris/metadata/fixedexecution/FixedSqlManifest.java'):
            self.assertIn(name,sources)
        self.assertEqual(11,audit.uncertainty.EXPECTED_TEST_COUNT)

if __name__=='__main__':unittest.main()

import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('uncertainty_audit_test',ROOT/'scripts/audit-execution-uncertainty.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class UncertaintyAuditSafetyTests(unittest.TestCase):
    def test_only_fresh_owned_report_paths_are_accepted(self):
        for path in (ROOT/'report.json',audit.OUTPUT,audit.OUTPUT/'bad.txt'):
            with self.assertRaises(ValueError):audit.identity.validate_output(path)
        with tempfile.TemporaryDirectory(dir=ROOT/'output') as folder:
            root=Path(folder);old=root/'old.json';old.write_text('preserve',encoding='utf-8')
            from unittest.mock import patch
            with patch.object(audit.identity,'OUTPUT',root):
                with self.assertRaises(ValueError):audit.identity.validate_output(old)
            self.assertEqual('preserve',old.read_text(encoding='utf-8'))

    def test_every_case_cleanup_and_source_match_remains_required(self):
        good={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'case':name,'passed':True} for name in audit.REQUIRED_CASES],
              'cleanup':dict.fromkeys(audit.identity.REQUIRED_CLEANUP,True)}
        candidates=[{**good,'checks':[]},{**good,'checks':good['checks'][:-1]},
                    {**good,'checks':good['checks']+[good['checks'][0]]},{**good,'sourceHashesMatchAtEnd':False}]
        candidates += [{**good,'cleanup':{key:True for key in audit.identity.REQUIRED_CLEANUP if key!=missing}} for missing in audit.identity.REQUIRED_CLEANUP]
        for candidate in candidates:
            audit.identity.finalize_status(candidate);self.assertEqual('failed',candidate['status'])

    def test_real_workflow_code_tests_and_all_migrations_are_bound(self):
        sources=audit.source_hashes()
        for name in ('scripts/audit-execution-uncertainty.py','scripts/audit-identity-chain.py','scripts/audit-api-key-chain.py',
                     'services/workflow/src/main/java/com/aetheris/workflow/service/QualityGateEvidence.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/service/PipelineExecutionService.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/service/SparkBatchExecutionService.java',
                     'services/workflow/src/test/java/com/aetheris/workflow/service/WorkflowExecutionUncertaintyPostgresIT.java'):
            self.assertIn(name,sources)
        migrations=audit.base.database_contract.source_manifest()
        self.assertEqual({p.relative_to(ROOT).as_posix() for p in ROOT.glob('services/*/src/main/resources/db/migration/*.sql')},set(migrations))
        self.assertEqual(migrations,{p:sources[p] for p in migrations})
        self.assertIn('services/auth/src/main/resources/db/migration/V11__browser_session_families.sql',migrations)

if __name__=='__main__':unittest.main()

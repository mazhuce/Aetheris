import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('engine_authority_audit_test',ROOT/'scripts/audit-workflow-engine-authority.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class EngineAuthorityAuditSafetyTests(unittest.TestCase):
    def good(self):
        return {'status':'passed','sourceHashesMatchAtEnd':True,
                'checks':[{'case':name,'passed':True} for name in sorted(audit.REQUIRED_CASES)],
                'cleanup':dict.fromkeys(audit.identity.REQUIRED_CLEANUP,True)}

    def test_missing_failing_duplicate_checks_wrong_source_and_incomplete_cleanup_cannot_pass(self):
        baseline=self.good();audit.identity.finalize_status(baseline);self.assertEqual('passed',baseline['status'])
        candidates=[{**self.good(),'sourceHashesMatchAtEnd':False},{**self.good(),'status':'running'}]
        for index in range(len(baseline['checks'])):
            missing=self.good();del missing['checks'][index];candidates.append(missing)
            failed=self.good();failed['checks'][index]['passed']=False;candidates.append(failed)
            duplicate=self.good();duplicate['checks'].append(copy.deepcopy(duplicate['checks'][index]));candidates.append(duplicate)
        for key in baseline['cleanup']:
            missing=self.good();del missing['cleanup'][key];candidates.append(missing)
            failed=self.good();failed['cleanup'][key]=False;candidates.append(failed)
        for report in candidates:
            audit.identity.finalize_status(report);self.assertEqual('failed',report['status'])

    def test_old_report_cannot_be_overwritten_or_relocated_into_acceptance(self):
        for path in (ROOT/'report.json',audit.OUTPUT,audit.OUTPUT/'report.txt',ROOT/'docs/report.json'):
            with self.assertRaises(ValueError):audit.identity.validate_output(path)
        with tempfile.TemporaryDirectory(dir=ROOT/'output') as folder:
            path=Path(folder)/'old.json';path.write_bytes(b'original failure')
            with patch.object(audit.identity,'OUTPUT',Path(folder)):
                with self.assertRaises(ValueError):audit.identity.validate_output(path)
            self.assertEqual(b'original failure',path.read_bytes())

    def test_current_engine_producer_binds_exact_current_migrations_and_actual_dispatch_sources(self):
        sources=audit.source_hashes();migrations=audit.base.database_contract.source_manifest()
        self.assertEqual({p.relative_to(ROOT).as_posix() for p in ROOT.glob('services/*/src/main/resources/db/migration/*.sql')},set(migrations))
        self.assertEqual(migrations,{p:sources[p] for p in migrations})
        self.assertIn('services/auth/src/main/resources/db/migration/V11__browser_session_families.sql',migrations)
        for name in ('scripts/audit-workflow-engine-authority.py',
                     'services/workflow/src/main/resources/db/migration/V33__trusted_engine_sources.sql',
                     'services/workflow/src/main/resources/db/migration/V34__pipeline_engine_success_evidence.sql',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowEngineAuthority.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowEngineMaterial.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowEngineAuthorizationClient.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowSparkExecutor.java',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowFlinkExecutor.java',
                     'services/workflow/src/test/java/com/aetheris/workflow/service/WorkflowEngineAuthorityPostgresIT.java'):
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),sources[name],name)
        self.assertEqual('com.aetheris.workflow.service.WorkflowEngineAuthorityPostgresIT',audit.uncertainty.TEST)
        self.assertEqual(31,audit.uncertainty.EXPECTED_TEST_COUNT)

if __name__=='__main__':unittest.main()

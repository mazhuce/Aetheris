import copy
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('fixed_storage_audit',ROOT/'scripts/audit-fixed-execution-storage.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class FixedExecutionStorageAuditTests(unittest.TestCase):
    def good(self):
        return {'testExitCode':0,'sourceHashesMatchAtEnd':True,
            'tests':{'tests':4,'failures':0,'errors':0,'skipped':0},
            'cleanup':{'ownedJavaExited':True,'containersRemoved':True,'volumesAbsent':True}}

    def test_current_complete_run_passes(self):
        report=self.good();audit.finalize(report);self.assertEqual('passed',report['status'])

    def test_missing_failed_or_skipped_tests_cannot_pass(self):
        for key,value in [('tests',0),('tests',3),('failures',1),('errors',1),('skipped',1)]:
            with self.subTest(key=key):
                report=self.good();report['tests'][key]=value;audit.finalize(report);self.assertEqual('failed',report['status'])
        report=self.good();report.pop('tests');audit.finalize(report);self.assertEqual('failed',report['status'])

    def test_exit_failure_and_source_drift_cannot_use_successful_xml(self):
        for field,value in [('testExitCode',1),('sourceHashesMatchAtEnd',False),('sourceHashesMatchAtEnd',None)]:
            report=self.good();report[field]=value;audit.finalize(report);self.assertEqual('failed',report['status'])

    def test_cleanup_must_be_complete_and_explicit(self):
        for key in self.good()['cleanup']:
            report=self.good();report['cleanup'][key]=False;audit.finalize(report);self.assertEqual('failed',report['status'])
            report=self.good();report['cleanup'].pop(key);audit.finalize(report);self.assertEqual('failed',report['status'])

    def test_output_cannot_overwrite_source_or_existing_report(self):
        for path in [ROOT/'pom.xml',ROOT/'scripts/audit-fixed-execution-storage.py',audit.OUTPUT,ROOT/'output/other/report.json']:
            with self.subTest(path=path),self.assertRaises(ValueError):audit.output_path(path)

    def test_source_manifest_includes_transitive_policy_and_migrations(self):
        paths=audit.source_hashes()
        for name in ['scripts/database/contract.py','scripts/database/database_roles.py','configs/database/services.json',
                     'services/metadata/src/test/java/com/aetheris/metadata/service/FixedExecutionStoragePostgresIT.java',
                     'services/metadata/src/main/resources/db/migration/V30__fixed_execution_receipts.sql']:
            self.assertIn(name,paths)
        self.assertTrue(all(len(value)==64 for value in paths.values()))


if __name__=='__main__':unittest.main()

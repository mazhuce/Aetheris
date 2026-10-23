import importlib.util,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('credential_audit',ROOT/'scripts/audit-datasource-credentials.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class ContractTests(unittest.TestCase):
    def test_restricts_report_and_container_port(self):
        with self.assertRaises(ValueError):audit.output_path(ROOT/'wrong.json')
        for value in ['0.0.0.0:5432','remote:5432','127.0.0.1:12\nother:34']:
            with self.assertRaises(ValueError):audit.valid_port(value)
        self.assertEqual(12345,audit.valid_port('127.0.0.1:12345'))
    def test_redacts_actual_ephemeral_secrets(self):
        self.assertEqual('x=[REDACTED]',audit.redact('x=private-fixture',['private-fixture']))
    def test_success_requires_real_unskipped_it_source_consistency_and_cleanup(self):
        good={'testExitCode':0,'tests':{'tests':1,'failures':0,'errors':0,'skipped':0},'sourceHashesMatchAtEnd':True,'cleanup':{'containerRemoved':True,'ownedJavaExited':True}}
        audit.finalize(good);self.assertEqual('passed',good['status'])
        for change in [{'testExitCode':1},{'sourceHashesMatchAtEnd':False},{'cleanup':{'containerRemoved':False,'ownedJavaExited':True}},{'cleanup':{'containerRemoved':True,'ownedJavaExited':False}},{'tests':{'tests':1,'skipped':1}}]:
            bad={**good,**change};audit.finalize(bad);self.assertEqual('failed',bad['status'])
if __name__=='__main__':unittest.main()

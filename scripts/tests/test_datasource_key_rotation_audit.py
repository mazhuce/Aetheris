import copy
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('rotation_audit_test',ROOT/'scripts/audit-datasource-key-rotation.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class RotationAuditContractTests(unittest.TestCase):
    def good(self):return {'status':'passed','checks':[{'case':c,'passed':True} for c in audit.REQUIRED_CASES],
                           'sourceHashesMatchAtEnd':True,'cleanup':{k:True for k in audit.REQUIRED_CLEANUP}}
    def test_missing_duplicate_failed_checks_or_cleanup_never_pass(self):
        self.assertEqual('passed',audit.finalize(self.good()))
        for mutation in ['missing','duplicate','failed','source','cleanup','extra-cleanup']:
            r=self.good()
            if mutation=='missing':r['checks'].pop()
            elif mutation=='duplicate':r['checks'].append(copy.deepcopy(r['checks'][0]))
            elif mutation=='failed':r['checks'][0]['passed']=False
            elif mutation=='source':r['sourceHashesMatchAtEnd']=False
            elif mutation=='cleanup':r['cleanup'][next(iter(r['cleanup']))]=False
            else:r['cleanup']['unverified']=True
            with self.subTest(mutation=mutation):self.assertEqual('failed',audit.finalize(r))
    def test_output_refuses_overwrite_and_non_audit_locations_before_starting_any_process(self):
        for p in [ROOT/'docker-compose.yml',ROOT/'output/other/report.json',audit.OUTPUT]:
            with self.subTest(path=p.name),self.assertRaises(ValueError):audit.Audit(p,ROOT/'missing.json')
    def test_bound_sources_include_actual_crypto_and_all_fixture_trigger_definitions(self):
        sources=audit.source_hashes();self.assertIn('services/metadata/src/main/java/com/aetheris/metadata/service/DataSourceSecretService.java',sources)
        for name in audit.FILES:self.assertIn('services/metadata/src/main/resources/db/migration/'+name,sources)
        self.assertIn('scripts/security/datasource_key_rotation.py',sources)

if __name__=='__main__':unittest.main()

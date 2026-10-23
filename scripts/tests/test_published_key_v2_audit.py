"""Tool-only acceptance guards; external processes, HTTP and Docker are forbidden here."""
import importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('published_key_audit_test',ROOT/'scripts/audit-published-key-v2.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class PublishedKeyV2ToolTest(unittest.TestCase):
    def setUp(self):
        for name in ('Popen','run'):
            guard=patch.object(audit.fixed.subprocess,name,side_effect=AssertionError('External process forbidden'))
            guard.start();self.addCleanup(guard.stop)
    def test_only_reviewed_five_modules_and_all_transitive_tools_are_fingerprinted(self):
        self.assertEqual({'auth','system','metadata','serving','gateway'},set(audit.MODULES));self.assertEqual(5,len(audit.MODULES));sources=audit.source_hashes()
        for path in ('scripts/audit-published-key-v2.py','scripts/audit-fixed-execution-chain.py','scripts/audit-identity-chain.py','scripts/audit-api-key-chain.py','services/serving/src/main/resources/db/migration/V11__published_key_admission.sql','services/metadata/src/main/resources/db/migration/V31__published_key_query_audit.sql'):
            self.assertIn(path,sources)
    def test_missing_duplicate_failed_check_dirty_source_and_cleanup_never_pass(self):
        report={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'case':name,'passed':True} for name in audit.REQUIRED_CASES],'cleanup':dict.fromkeys(audit.REQUIRED_CLEANUP,True)}
        original=json.dumps(report);audit.finalize_status(report);self.assertEqual('passed',report['status'])
        for mutate in (lambda r:r['checks'].pop(),lambda r:r['checks'].append(r['checks'][0]),lambda r:r['checks'][0].update(passed=False),lambda r:r.update(sourceHashesMatchAtEnd=False)):
            value=json.loads(original);mutate(value);audit.finalize_status(value);self.assertEqual('failed',value['status'])
        for name in audit.REQUIRED_CLEANUP:
            value=json.loads(original);value['cleanup'][name]=False;audit.finalize_status(value);self.assertEqual('failed',value['status'])
    def test_output_is_owned_fresh_and_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='published-key-tool-',dir=ROOT/'output') as directory:
            folder=Path(directory)
            with patch.object(audit.identity,'OUTPUT',folder):
                self.assertEqual(folder/'fresh.json',audit.validate_output(folder/'fresh.json'))
                old=folder/'existing.json';old.write_text('preserve',encoding='utf-8')
                for path in (old,folder/'bad.txt',folder.parent/'outside.json'):
                    with self.assertRaises(ValueError):audit.validate_output(path)
                self.assertEqual('preserve',old.read_text(encoding='utf-8'))
    def test_fixture_roles_are_fresh_reader_only_without_platform_grants(self):
        instance=audit.Audit.__new__(audit.Audit);instance.secret=Mock(return_value='fixture-only');instance.sql=Mock()
        with patch.object(audit.identity.Audit,'setup_database') as parent:instance.setup_database()
        parent.assert_called_once();query=instance.sql.call_args.args[0]
        self.assertIn('CREATE SCHEMA business',query);self.assertIn('CREATE ROLE audit_business_reader',query);self.assertIn('GRANT SELECT ON business.orders',query)
        for forbidden in ('GRANT ALL','DROP ','UPDATE auth.','GRANT SELECT ON auth.'):
            self.assertNotIn(forbidden,query)
    def test_pure_key_headers_cannot_smuggle_a_cookie_or_bearer(self):
        self.assertEqual({'X-API-Key':'fixture'},audit.base.pure_key_headers('fixture'))
        for name in ('Authorization','Cookie','authorization','cookie'):
            with self.assertRaises(ValueError):audit.base.pure_key_headers('fixture',{name:'forbidden'})
    def test_failure_status_is_not_a_permission_rejection(self):
        instance=audit.Audit.__new__(audit.Audit);instance.report={'checks':[]};client=Mock();client.request.return_value=(500,{})
        with self.assertRaises(audit.base.AuditFailure):instance.http('rejection',client,'POST','/api/serving/v1/apis/orders/query',{403},{})
        self.assertFalse(instance.report['checks'][0]['passed'])
    def test_audit_scope_requires_real_other_user_rows_and_rejects_forged_global_scope(self):
        owner={'total':9,'success':6,'failed':3};admin={'total':11,'success':7,'failed':4}
        counts={'ownerTotal':9,'ownerSuccess':6,'ownerFailed':3}
        self.assertTrue(audit.audit_scope_matches(admin,owner,owner.copy(),counts))
        self.assertFalse(audit.audit_scope_matches(owner,owner,owner,counts))
        self.assertFalse(audit.audit_scope_matches(admin,owner,admin,counts))
        self.assertFalse(audit.audit_scope_matches(admin,{**owner,'total':True},owner,counts))
        self.assertFalse(audit.audit_scope_matches(admin,owner,owner,{}))
        self.assertTrue({'ordinary-audit-trusted-request-id','ordinary-audit-has-no-driver-detail',
                         'audit-summaries-use-auth-scope','reviewer-readonly-write-denied'}.issubset(audit.REQUIRED_CASES))

if __name__=='__main__':unittest.main()

import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('api_chain',ROOT/'scripts/audit-api-key-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class ApiKeyAuditContractTests(unittest.TestCase):
    def test_pure_key_never_contains_jwt_or_cookie(self):
        headers=audit.pure_key_headers('ak_unit_fixture_secret')
        self.assertEqual('ak_unit_fixture_secret',headers['X-API-Key'])
        self.assertFalse(any(k.lower() in ('authorization','cookie') for k in headers))
        for extra in ({'Cookie':'a=b'},{'Authorization':'Bearer fixture'}):
            with self.assertRaises(ValueError):audit.pure_key_headers('fixture',extra)

    def test_server_error_cannot_count_as_authorization_rejection(self):
        audit.require_status(403,{401,403},'forbidden-fixture')
        with self.assertRaises(audit.AuditFailure):audit.require_status(500,{401,403},'forbidden-fixture')

    def test_true_data_rows_required_not_just_http_200(self):
        good={'rowCount':3,'columns':['id','name','amount'],'rows':[[1,'A',10],[2,'B','20.00'],[3,'C',30.0]]}
        audit.require_business_rows(good)
        for bad in ({'error':'query failed'}, {'rowCount':3,'columns':['id','name','amount'],'rows':[[1,'A',0],[2,'B',20],[3,'C',30]]}):
            with self.assertRaises(audit.AuditFailure):audit.require_business_rows(bad)

    def test_logs_redact_known_secrets_keys_and_jwts(self):
        result=audit.redact('password=private-fixture ak_123456789012_abcdefghijklmnopqrstuvwxyz eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.signature',['private-fixture'])
        self.assertNotIn('private-fixture',result)
        self.assertNotIn('ak_123456789012',result)
        self.assertNotIn('eyJhbGci',result)

    def test_outside_output_and_non_loopback_rejected(self):
        with self.assertRaises(ValueError):audit.validate_output(ROOT.parent/'escape.json')
        with self.assertRaises(ValueError):audit.HttpClient('http://example.com:8080')

    def test_failed_cleanup_makes_final_report_failed(self):
        for component in ('processes','containers','credentialsRetired'):
            cleanup={key:True for key in ('processes','containers','credentialsRetired')};cleanup[component]=False
            report={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'passed':True}],'cleanup':cleanup}
            audit.finalize_status(report)
            self.assertEqual('failed',report['status'])

    def test_failed_http_check_makes_final_report_failed_with_successful_cleanup(self):
        report={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'passed':False}],'cleanup':{'processes':True,'containers':True,'credentialsRetired':True}}
        audit.finalize_status(report)
        self.assertEqual('failed',report['status'])

    def test_missing_source_consistency_evidence_cannot_pass(self):
        report={'status':'passed','checks':[{'passed':True}],'cleanup':{'processes':True,'containers':True,'credentialsRetired':True}}
        audit.finalize_status(report)
        self.assertEqual('failed',report['status'])


if __name__=='__main__':unittest.main()

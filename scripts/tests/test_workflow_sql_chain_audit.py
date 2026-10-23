import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('workflow_sql_chain_audit_test',ROOT/'scripts/audit-workflow-sql-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class WorkflowSqlChainAuditTests(unittest.TestCase):
    def tearDown(self):audit.configure(False)
    def test_all_nine_services_and_gateway_have_distinct_real_module_roots(self):
        audit.configure(True)
        self.assertEqual(10,len(audit.MODULES));self.assertEqual(set(audit.base.database_contract.services())|{'gateway'},set(audit.MODULES))
        self.assertEqual(10,len(set(audit.MODULES.values())))
    def test_runtime_role_uses_actual_schema_including_ai_agent(self):
        for name,schema in audit.base.database_contract.services().items():
            with self.subTest(service=name):self.assertEqual('aetheris_'+schema,audit.fixed.runtime_role(name))
        with self.assertRaises(KeyError):audit.fixed.runtime_role('unregistered')
    def test_all_module_sources_helpers_and_exact_114_migrations_are_bound(self):
        audit.configure(True)
        values=audit.source_hashes();manifest=audit.base.database_contract.source_manifest();self.assertEqual(116,len(manifest));self.assertTrue(set(manifest).issubset(values))
        for module in audit.MODULES.values():self.assertIn(module+'/pom.xml',values)
        for path in ['scripts/audit-workflow-sql-chain.py','scripts/audit-fixed-execution-chain.py','scripts/audit-identity-chain.py']:
            self.assertIn(path,values)
    def test_provider_credentials_are_not_inherited_by_any_service(self):
        with patch.dict(audit.fixed.os.environ,{'DEEPSEEK_API_KEY':'paid','OPENAI_API_KEY':'paid','ANTHROPIC_API_KEY':'paid','AZURE_OPENAI_KEY':'paid'},clear=False):
            value=audit.clean_environment();self.assertFalse(any(k.startswith(('DEEPSEEK_','OPENAI_','ANTHROPIC_','AZURE_OPENAI_')) for k in value))
    def test_cases_require_real_sql_cron_and_inherited_transfer_quality(self):
        audit.configure(True)
        for name in ['sql-real-row-and-writer','delegated-sql-approval','sql-sealed-actor-and-approver','cron-real-quartz-success',
                     'cron-generation-and-actor-seal','sql-revocation-zero-new-attempt','transfer-real-rows','quality-result-and-quarantine-atomic']:
            self.assertIn(name,audit.REQUIRED_CASES)
        self.assertEqual(5,len(audit.REQUIRED_CLEANUP))
    def test_default_is_five_sql_applications_and_never_claims_ten(self):
        audit.configure(False);self.assertEqual({'auth','system','metadata','workflow','gateway'},set(audit.MODULES))
        self.assertEqual(audit.SQL_CASES,audit.REQUIRED_CASES)
        self.assertNotIn('transfer-real-rows',audit.REQUIRED_CASES)
    def test_task_create_response_uses_existing_task_view_envelope(self):
        self.assertEqual(7,audit.task_view_id({'task':{'id':7},'upstreamCount':0}))
        for value in [{'id':7},{'task':{'id':True}},{'task':{'id':0}},{'task':None}]:
            with self.subTest(value=value),self.assertRaises(audit.base.AuditFailure):audit.task_view_id(value)
    def test_output_refuses_existing_workspace_and_non_owned_tree(self):
        for path in [ROOT/'docker-compose.yml',ROOT/'output/other/report.json',audit.OUTPUT]:
            with self.subTest(path=path.name),self.assertRaises(ValueError):audit.validate_output(path)

if __name__=='__main__':unittest.main()

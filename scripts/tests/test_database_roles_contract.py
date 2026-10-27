import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('database_contract', ROOT / 'scripts/database/contract.py')
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class DatabaseRoleContractTests(unittest.TestCase):
    def helpers(self,schema):
        return [{'schema':schema,'name':name,'arguments':args,'resultType':result,'owner':'legacy_admin','securityDefiner':False,'acl':None}
            for name,args,result in [('execution_identity_valid','value jsonb','boolean'),('execution_actor_valid','value jsonb','boolean'),
                ('execution_approval_valid','value jsonb, revision bigint, definition_hash text','boolean'),('execution_definition_projection','body jsonb','jsonb')]] + (
                [{'schema':schema,'name':name,'arguments':args,'resultType':result,'owner':'legacy_admin','securityDefiner':False,'acl':None}
                 for name,(args,types,result) in contract.WORKFLOW_PIPELINE_HELPERS.items()] if schema=='workflow' else [])

    def test_exact_fixed_execution_helpers_get_only_local_execute(self):
        before=self.snapshot();before['functions']=[f for s in ('workflow','ingestion','quality') for f in self.helpers(s)]
        sql=contract.grants_sql(before)
        grants=[line for line in sql.splitlines() if line.startswith('GRANT EXECUTE')]
        self.assertEqual(17,len(grants))
        for schema in ('workflow','ingestion','quality'):
            self.assertIn(f'GRANT EXECUTE ON FUNCTION "{schema}"."execution_approval_valid"(jsonb, bigint, text) TO "aetheris_{schema}";',grants)
            self.assertIn(f'GRANT EXECUTE ON FUNCTION "{schema}"."execution_definition_projection"(jsonb) TO "aetheris_{schema}";',grants)
        self.assertTrue(all('ALL FUNCTIONS' not in line and 'PUBLIC' not in line for line in grants))
        before['functions']=[{'schema':'auth','name':'existing_trigger','arguments':'','resultType':'trigger','owner':'legacy_admin','securityDefiner':False,'acl':None}]
        self.assertNotIn('GRANT EXECUTE',contract.grants_sql(before))

    def test_helper_schema_name_identity_signature_result_and_definer_fail_closed(self):
        mutations=[('schema','auth'),('schema','public'),('name','unknown_function'),('name','execution_identity_valid; DROP ROLE x'),
            ('arguments','jsonb'),('arguments','value text'),('arguments','value jsonb DEFAULT NULL'),
            ('arguments','value jsonb); GRANT ALL ON DATABASE aetheris TO PUBLIC;--'),('resultType','text'),('securityDefiner',True)]
        for key,value in mutations:
            before=self.snapshot();helper=self.helpers('workflow')[0];helper[key]=value;before['functions']=[helper]
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):contract.grants_sql(before)
        before=self.snapshot();before['functions']=[{'schema':'auth','name':'unexpected_scalar','arguments':'','resultType':'integer','owner':'legacy_admin','securityDefiner':False,'acl':None}]
        with self.assertRaises(ValueError):contract.grants_sql(before)
        for name,(args,types,result) in contract.WORKFLOW_PIPELINE_HELPERS.items():
            helper={'schema':'workflow','name':name,'arguments':args,'resultType':result,'owner':'legacy_admin','securityDefiner':False,'acl':None}
            before=self.snapshot();before['functions']=[helper]
            self.assertIn('TO "aetheris_workflow"',contract.grants_sql(before))
            for schema in ('ingestion','quality','auth'):
                before['functions']=[{**helper,'schema':schema}]
                with self.assertRaises(ValueError):contract.grants_sql(before)

    def snapshot(self):
        return {'database': 'aetheris', 'databaseOwner': 'legacy_admin', 'databaseAcl': None,
                'schemas': [{'name': 'auth', 'owner': 'legacy_admin', 'acl': None}],
                'relations': [{'schema': 'auth', 'name': 'auth_user', 'kind': 'r', 'owner': 'legacy_admin', 'acl': None},
                              {'schema': 'auth', 'name': 'flyway_schema_history', 'kind': 'r', 'owner': 'legacy_admin', 'acl': None}],
                'functions': [], 'types': [], 'dependencies': [], 'extensions': [], 'defaultAcls': [],
                'roles': [], 'memberships': [], 'sourceValidation':{'auth':{'validationSuccessful':True,'pending':0}},
                'history': {'auth': [{'version': '1', 'script': 'V1__auth_user.sql', 'success': True}]}}

    def test_schema_mapping_and_secret_free_runtime(self):
        mapping = contract.services()
        self.assertEqual('ai', mapping['ai-agent'])
        self.assertEqual(9, len(mapping))
        for schema in mapping.values():
            sql = contract.bootstrap_sql(schema)
            self.assertIn('NOLOGIN', sql)
            self.assertIn('NOCREATEROLE', sql)
            self.assertNotIn('PASSWORD', sql)
            self.assertNotIn('REASSIGN OWNED', sql)

    def test_history_never_gets_runtime_dml(self):
        sql = contract.grants_sql(self.snapshot())
        self.assertIn('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "auth"."auth_user"', sql)
        self.assertNotIn('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "auth"."flyway_schema_history"', sql)
        self.assertNotIn('GRANT ALL', sql)
        self.assertNotIn('GRANT EXECUTE', sql)
        self.assertNotIn('GRANT UPDATE ON SEQUENCE', sql)

    def test_plan_records_previous_owner_and_acl_and_is_hash_bound(self):
        before = self.snapshot()
        result = contract.plan(before)
        self.assertEqual(before, result['before'])
        self.assertEqual(contract.digest(before), result['inventorySha256'])
        self.assertIn('OWNER TO "legacy_admin"', result['rollbackSql'])
        self.assertIn('BEGIN;', result['applySql'])
        self.assertTrue(result['requiresBackup'])
        changed = json.loads(json.dumps(before))
        changed['relations'][0]['owner'] = 'unexpected'
        self.assertNotEqual(contract.digest(changed), result['inventorySha256'])

    def test_unknown_privilege_chains_definers_and_dependencies_block(self):
        for key, value in [('functions', [{'schema': 'auth', 'identity': 'auth.bad()', 'owner': 'legacy_admin', 'securityDefiner': True, 'acl': None}]),
                           ('dependencies', [{'fromSchema': 'auth', 'toSchema': 'metadata'}]),
                           ('relations', [{'schema': 'auth', 'name': 'x', 'kind': 'f', 'owner': 'legacy_admin', 'acl': None}]),
                           ('relations', [{'schema': 'auth', 'name': 'x', 'kind': 'v', 'owner': 'legacy_admin', 'acl': None}]),
                           ('relations', [{'schema': 'auth', 'name': 'x', 'kind': 'm', 'owner': 'legacy_admin', 'acl': None}]),
                           ('defaultAcls', [{'owner': 'legacy_admin', 'schema': 'auth', 'acl': '{other=r/legacy_admin}'}])]:
            before = self.snapshot()
            before[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                contract.plan(before)

    def test_successful_history_is_not_enough_without_current_source_validation(self):
        for state in ({}, {'auth':{'validationSuccessful':False,'pending':0}}, {'auth':{'validationSuccessful':True,'pending':1}}):
            before=self.snapshot();before['sourceValidation']=state
            with self.assertRaises(ValueError):contract.plan(before)

    def test_identifier_and_credential_input_rejected(self):
        for schema in ['public', 'auth;DROP ROLE x', '../auth']:
            with self.assertRaises(ValueError): contract.bootstrap_sql(schema)
        before = self.snapshot()
        before['schemas'][0]['owner'] = 'x"; DROP DATABASE aetheris;--'
        result = contract.plan(before)
        self.assertNotIn('OWNER TO "x";', result['rollbackSql'])

    def test_all_source_migrations_are_enumerated_and_hashed(self):
        sources = contract.source_manifest(ROOT)
        self.assertEqual(set(sources),{p.relative_to(ROOT).as_posix() for p in ROOT.glob('services/*/src/main/resources/db/migration/*.sql')})
        self.assertEqual(9,len({p.split('/')[1] for p in sources}))
        self.assertTrue(all(len(v) == 64 for v in sources.values()))
        self.assertTrue(all('/src/main/resources/db/migration/' in p for p in sources))


if __name__ == '__main__': unittest.main()

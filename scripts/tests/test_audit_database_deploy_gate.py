import importlib.util
import json
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('audit_database_deploy_gate', ROOT / 'scripts/audit-database-deploy-gate.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)


class DeployGateAuditSafetyTests(unittest.TestCase):
    def test_frozen_gate_accepts_current_sources_and_refuses_old_or_extra_migrations(self):
        current = audit.contract.source_manifest()
        audit.require_frozen_sources(current)
        for count in (0, 93, 105, 109, 112, 113, 115):
            with self.subTest(count=count), self.assertRaises(audit.AuditFailure):
                audit.require_frozen_sources({str(i): 'a' * 64 for i in range(count)})

    def test_environment_cannot_inherit_real_application_credentials(self):
        cleaned = audit.clean_environment({'PATH': 'path', 'DB_PASSWORD': 'secret', 'JWT_SECRET': 'secret',
            'POSTGRES_PASSWORD': 'secret', 'AETHERIS_DB_OPERATOR_CONFIG': 'real', 'COMPOSE_FILE': 'real.yml',
            'JAVA_TOOL_OPTIONS': '-Dspring.datasource.password=secret', 'MAVEN_OPTS': 'secret', 'DEEPSEEK_API_KEY': 'paid',
            'COMPOSE_ENV_FILES': '.env', 'SPRING_APPLICATION_JSON': 'secret'})
        self.assertEqual({'PATH': 'path'}, cleaned)

    def test_refuses_relative_java_home_before_launch(self):
        with self.assertRaises(audit.AuditFailure): audit.java_environment({'JAVA_HOME': '.'})

    def test_private_directory_requires_exact_random_id_and_new_owned_child(self):
        for value in ('', 'a' * 31, 'a' * 33, '../escape', 'A' * 32):
            with self.subTest(value=value), self.assertRaises(ValueError): audit.private_directory(value)
        path = audit.private_directory('f' * 32)
        self.assertEqual(audit.PRIVATE.resolve(), path.parent)
        self.assertEqual('deploy-gate-' + 'f' * 32, path.name)

    def test_cleanup_path_never_accepts_parent_or_outside_directory(self):
        for path in (ROOT, audit.PRIVATE, ROOT / 'services', audit.PRIVATE / ('other-' + 'f' * 32)):
            with self.subTest(path=path), self.assertRaises(ValueError): audit.owned_cleanup_path(path, 'f' * 32)
        self.assertEqual(audit.PRIVATE.resolve() / ('deploy-gate-' + 'f' * 32), audit.owned_cleanup_path(audit.PRIVATE / ('deploy-gate-' + 'f' * 32), 'f' * 32))

    def test_output_cannot_overwrite_or_escape_audit_tree(self):
        for path in (ROOT / 'docker-compose.yml', audit.OUTPUT, ROOT / 'output/report.json', audit.OUTPUT / '..' / 'other.json'):
            with self.subTest(path=path), self.assertRaises(ValueError): audit.validate_output(path)
        self.assertEqual(audit.OUTPUT.resolve() / ('f' * 32) / 'report.json', audit.validate_output(audit.OUTPUT / ('f' * 32) / 'report.json'))

    def test_postgres_fixture_disables_persistent_storage_and_fixed_network(self):
        value = audit.fixture_override('dg-' + 'f' * 32 + '-primary', 'f' * 32)
        self.assertIn('volumes: !reset []', value)
        self.assertIn('volumes: !reset {}', value)
        self.assertIn('ipam: !reset {}', value)
        self.assertIn('tmpfs:', value)
        self.assertIn('pull_policy: never', value)
        self.assertNotIn('aetheris_default', value)
        self.assertIn('  redis:\n    volumes: !reset []', value)
        with self.assertRaises(ValueError): audit.fixture_override('aetheris', 'f' * 32)

    def test_manifest_is_explicitly_unsigned_fixture_and_uses_actual_service_mapping(self):
        release_spec = importlib.util.spec_from_file_location('gate_audit_release_test', ROOT / 'scripts/release-manifest.py')
        release = importlib.util.module_from_spec(release_spec); release_spec.loader.exec_module(release)
        sources = audit.contract.source_manifest()
        value = audit.synthetic_manifest(release, sources, 'a' * 40)
        release.validate(value)
        self.assertIn('UNSIGNED', value['fixturePurpose'])
        self.assertEqual(11, len(value['images']))
        self.assertEqual(116, len(value['databaseSources']))
        mapped = release.compose_override(value)['services']
        self.assertIn('auth-service', mapped)
        self.assertNotIn('auth', mapped)

    def test_gate_bootstrap_only_relocates_runner_and_calls_real_gate(self):
        self.assertIn('database_roles.RUNNER=Path(', audit.GATE_BOOTSTRAP)
        self.assertIn('deploy_gate.main()', audit.GATE_BOOTSTRAP)
        for forbidden in ('compose_identity=', 'verify_receipt=', 'source_manifest=', 'unittest.mock', 'patch('):
            self.assertNotIn(forbidden, audit.GATE_BOOTSTRAP)

    def test_configuration_mutations_cover_new_bypasses_with_only_synthetic_values(self):
        mutations = audit.configuration_mutations()
        self.assertEqual(10, len(mutations))
        self.assertEqual(len(mutations), len({name for name, key, value in mutations}))
        values = {key: value for name, key, value in mutations}
        required = {'SPRING_DATASOURCE_HIKARI_JDBC_URL', 'SPRING_DATASOURCE_HIKARI_USERNAME',
                    'SPRING_DATASOURCE_HIKARI_PASSWORD', 'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION',
                    'JDK_JAVA_OPTIONS', 'spring_datasource_hikari_jdbc_url', 'spring.datasource.hikari.jdbcUrl',
                    'SPRING-DATASOURCE-HIKARI-USERNAME', 'JAVA_TOOL_OPTIONS'}
        self.assertEqual(required, set(values))
        self.assertEqual('-DDB_HOST=unverified', values['JDK_JAVA_OPTIONS'])
        self.assertTrue(values['JAVA_TOOL_OPTIONS'].startswith('@'))

    def test_live_observation_is_readonly_and_covers_real_gate_functions(self):
        self.assertIn('sys.setprofile(observe)', audit.GATE_BOOTSTRAP)
        self.assertIn("observed[name]+=1", audit.GATE_BOOTSTRAP)
        self.assertIn('GATE_OBSERVED_CALLS=', audit.GATE_BOOTSTRAP)
        self.assertIn('GATE_FAILURE_FUNCTION=', audit.GATE_BOOTSTRAP)
        self.assertIn('sys.setprofile(None)', audit.GATE_BOOTSTRAP)

    def test_source_binding_includes_gate_and_all_sql_sources(self):
        value = audit.source_hashes()
        self.assertIn('scripts/database/deploy_gate.py', value)
        self.assertIn('scripts/release-manifest.py', value)
        self.assertIn('docker-compose.database-roles.yml', value)
        self.assertTrue(set(audit.contract.source_manifest()).issubset(value))


if __name__ == '__main__': unittest.main()

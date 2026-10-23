import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('query_observability_test',ROOT/'scripts/audit-query-observability.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class QueryObservabilityAuditTests(unittest.TestCase):
    def good(self):
        return {'testExitCode':0,'sourceHashesMatchAtEnd':True,'freshTestXml':True,
                'tests':{'tests':4,'failures':0,'errors':0,'skipped':0},
                'cleanup':{key:True for key in audit.CLEANUP},'compiledClassHashes':{'class':'a'*64},
                'imageId':'sha256:'+'b'*64,'testReportSha256':'c'*64}

    def test_no_missing_failed_skipped_stale_or_unclean_result_can_pass(self):
        self.assertEqual('passed',audit.finalize(self.good()))
        for key,value in [('testExitCode',1),('sourceHashesMatchAtEnd',False),('freshTestXml',False),
                          ('compiledClassHashes',{}),('testReportSha256',None),('imageId','latest')]:
            with self.subTest(key=key):self.assertEqual('failed',audit.finalize({**self.good(),key:value}))
        for key,value in [('tests',0),('tests',1),('tests',2),('tests',3),('tests',5),('failures',1),('errors',1),('skipped',1)]:
            report=self.good();report['tests'][key]=value
            with self.subTest(key=key,value=value):self.assertEqual('failed',audit.finalize(report))
        for key in audit.CLEANUP:
            report=self.good();report['cleanup'][key]=False
            with self.subTest(key=key):self.assertEqual('failed',audit.finalize(report))

    def test_output_cannot_escape_or_overwrite_source(self):
        for path in (ROOT/'pom.xml',ROOT/'services/metadata/report.json',audit.OUTPUT/'..'/'outside.json'):
            with self.subTest(path=path),self.assertRaises(ValueError):audit.output_path(path)
        self.assertEqual(audit.OUTPUT.resolve()/'new/report.json',audit.output_path(audit.OUTPUT/'new/report.json'))

    def test_parent_jvm_database_and_provider_secrets_are_not_inherited(self):
        env={'PATH':'safe','JAVA_TOOL_OPTIONS':'bad','CLASSPATH':'bad','MAVEN_ARGS':'bad','MAVEN_OPTS':'bad',
             'DB_PASSWORD':'private','OPENAI_API_KEY':'paid','ANTHROPIC_API_KEY':'paid','AZURE_OPENAI_API_KEY':'paid',
             'DEEPSEEK_API_KEY':'paid','COMPOSE_FILE':'installed.yml'}
        self.assertEqual({'PATH':'safe'},audit.clean_environment(env))

    def test_cleanup_never_accepts_an_unchecked_process_marker(self):
        for value in ('','not-a-run','0'*31,'0'*32+"' injected"):
            with self.subTest(value=value),self.assertRaises(ValueError):audit.owned_java(value,{})

    def test_source_manifest_includes_real_service_sql_and_direct_identity_fixture(self):
        sources=audit.source_hashes()
        self.assertIn('services/metadata/src/main/java/com/aetheris/metadata/service/QueryAuditService.java',sources)
        self.assertIn('services/metadata/src/main/resources/db/migration/V31__published_key_query_audit.sql',sources)
        self.assertIn('services/metadata/src/main/resources/db/migration/V32__query_history_and_audit_stable_uid.sql',sources)
        self.assertIn('services/metadata/src/test/java/com/aetheris/metadata/security/MetadataProofFixture.java',sources)
        self.assertIn('services/metadata/src/test/java/com/aetheris/metadata/service/QueryAuditPostgresIT.java',sources)

if __name__=='__main__':unittest.main()

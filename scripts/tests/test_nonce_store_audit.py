import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('nonce_store_audit',ROOT/'scripts/audit-nonce-store.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class NonceStoreAuditTests(unittest.TestCase):
    def test_fixture_url_cannot_select_any_other_host_database_or_query(self):
        self.assertEqual('jdbc:postgresql://127.0.0.1:25432/aetheris_nonce_audit',audit.fixture_url(25432))
        for value in (0,-1,65536,'5432',True):
            with self.subTest(value=value),self.assertRaises(ValueError):audit.fixture_url(value)

    def test_credentials_jvm_options_and_existing_app_environment_removed(self):
        self.assertEqual({'PATH':'safe'},audit.clean_environment({'PATH':'safe','JAVA_TOOL_OPTIONS':'secret','CLASSPATH':'unreviewed',
            'JDK_JAVA_OPTIONS':'bad','MAVEN_OPTS':'bad','AETHERIS_TEST_NONCE_POSTGRES_PASSWORD':'real',
            'SPRING_DATASOURCE_URL':'real','POSTGRES_PASSWORD':'real','JWT_SECRET':'real','DEEPSEEK_API_KEY':'paid'}))

    def test_output_must_be_new_and_within_owned_tree(self):
        for value in (ROOT,ROOT/'libs/security-servlet',audit.OUTPUT,audit.OUTPUT/'..'/'elsewhere'):
            with self.subTest(value=value),self.assertRaises(ValueError):audit.valid_output(value)
        self.assertEqual(audit.OUTPUT.resolve()/('f'*32),audit.valid_output(audit.OUTPUT/('f'*32)))

    def xml(self,tests='1',failures='0',errors='0',skipped='0',child=''):
        return '<testsuite name="'+audit.SUITE+'" tests="'+tests+'" failures="'+failures+'" errors="'+errors+'" skipped="'+skipped+'" time="1"><testcase classname="'+audit.SUITE+'" name="'+audit.METHOD+'">'+child+'</testcase></testsuite>'

    def test_fresh_exact_it_required_not_empty_skipped_or_wrong_method(self):
        audit.OUTPUT.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='xml-',dir=audit.OUTPUT) as temp:
            path=Path(temp)/'it.xml'
            for value in (self.xml(tests='0'),self.xml(tests='2'),self.xml(failures='1'),self.xml(errors='1'),
                          self.xml(skipped='1'),self.xml(child='<skipped/>'),self.xml().replace(audit.METHOD,'other')):
                path.write_text(value,encoding='utf-8')
                with self.subTest(value=value),self.assertRaises(audit.AuditFailure):audit.verify_xml(path,0)
            path.write_text(self.xml(),encoding='utf-8');self.assertEqual(1,audit.verify_xml(path,0)['tests'])
            with self.assertRaises(audit.AuditFailure):audit.verify_xml(path,time.time_ns()+10**9)

    def observed(self):return {'database':audit.DATABASE,'rows':6,'schemas':['metadata'],
        'expiryGroups':[{'expiresAt':10000,'count':2},{'expiresAt':92801,'count':1},{'expiresAt':96431,'count':3}],
        'runtimeInsert':True,'runtimeUpdate':False,'readerSelect':True,'readerInsert':False,'readerDelete':False}

    def test_database_observation_rejects_wrong_retention_units_and_privileges(self):
        self.assertEqual(self.observed(),audit.observed_database(self.observed()))
        for key,value in [('database','other'),('rows',9),('schemas',['auth','metadata']),('runtimeInsert',False),
                          ('runtimeUpdate',True),('readerInsert',True),('readerDelete',True),('readerSelect',False),
                          ('expiryGroups',[{'expiresAt':10000,'count':1},{'expiresAt':13630000,'count':3}])]:
            with self.subTest(key=key),self.assertRaises(audit.AuditFailure):audit.observed_database({**self.observed(),key:value})

    def test_source_fingerprint_covers_store_it_template_and_filter(self):
        value=audit.source_hashes()
        for suffix in ('src/main/java/com/aetheris/security/servlet/PostgresNonceStore.java',
                       'src/test/java/com/aetheris/security/servlet/PostgresNonceStoreIT.java',
                       'src/main/resources/internal-request-nonce.sql.template',
                       'src/main/java/com/aetheris/security/servlet/WorkloadProofFilter.java'):
            self.assertIn('libs/security-servlet/'+suffix,value)

    def test_wire_mode_keeps_original_it_and_requires_exact_new_method(self):
        self.assertEqual([(audit.SUITE,audit.METHOD)],audit.expected_suites())
        self.assertEqual([(audit.SUITE,audit.METHOD),(audit.WIRE_SUITE,audit.WIRE_METHOD)],audit.expected_suites(True))
        audit.OUTPUT.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='xml-',dir=audit.OUTPUT) as temp:
            path=Path(temp)/'wire.xml';path.write_text(self.xml(),encoding='utf-8')
            with self.assertRaises(audit.AuditFailure):audit.verify_xml(path,0,audit.WIRE_SUITE,audit.WIRE_METHOD)
            path.write_text(self.xml().replace(audit.SUITE,audit.WIRE_SUITE).replace(audit.METHOD,audit.WIRE_METHOD),encoding='utf-8')
            self.assertEqual(1,audit.verify_xml(path,0,audit.WIRE_SUITE,audit.WIRE_METHOD)['tests'])

    def test_wire_cleanup_marker_and_database_state_fail_closed(self):
        value={'tomcatVersion':'10.1.42.0','servers':2,'duplicateRequests':24,'duplicateWinners':1,
               'duplicateSignedConflicts':23,'nonceRows':5,'portsClosed':True,'threadsStopped':True,
               'ownedTempRemoved':True,'sessionValidator':'local-test-state'}
        audit.OUTPUT.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='xml-',dir=audit.OUTPUT) as temp:
            path=Path(temp)/'wire.xml'
            for candidate in (None,{**value,'threadsStopped':False},{**value,'duplicateWinners':2},{**value,'ownedTempRemoved':False}):
                marker='' if candidate is None else 'AETHERIS_SERVLET_WIRE_RESULT='+json.dumps(candidate)
                path.write_text('<testsuite><system-out>'+marker+'</system-out></testsuite>',encoding='utf-8')
                with self.assertRaises(audit.AuditFailure):audit.verify_wire_evidence(path)
            path.write_text('<testsuite><testcase><system-out>AETHERIS_SERVLET_WIRE_RESULT='+json.dumps(value)+'</system-out></testcase></testsuite>',encoding='utf-8')
            with self.assertRaises(audit.AuditFailure):audit.verify_wire_evidence(path)
            path.write_text('<testsuite><testcase><system-out>AETHERIS_SERVLET_WIRE_DEADLINE=header:closed\nAETHERIS_SERVLET_WIRE_DEADLINE=body:408\nAETHERIS_SERVLET_WIRE_RESULT='+json.dumps(value)+'</system-out></testcase></testsuite>',encoding='utf-8')
            self.assertEqual({**value,'inputDeadlineOutcomes':{'header':'closed','body':'408'}},audit.verify_wire_evidence(path))
        database={**self.observed(),'schemas':['auth','metadata'],'wireRows':5,'wireRuntimeInsert':True,'wireRuntimeUpdate':False}
        self.assertEqual(database,audit.observed_database(database,True))
        for key,bad in [('schemas',['metadata']),('wireRows',0),('wireRuntimeInsert',False),('wireRuntimeUpdate',True)]:
            with self.subTest(key=key),self.assertRaises(audit.AuditFailure):audit.observed_database({**database,key:bad},True)


if __name__=='__main__':unittest.main()

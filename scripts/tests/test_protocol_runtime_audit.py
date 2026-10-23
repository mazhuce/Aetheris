import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('protocol_runtime_audit',ROOT/'scripts/audit-protocol-runtime.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class ProtocolRuntimeAuditTests(unittest.TestCase):
    def good(self):return {'status':'passed','javaSpecificationVersion':'21','found':160,'succeeded':160,
        'failed':0,'skipped':0,'aborted':0,'containersFailed':0,'containersSkipped':0,'containersAborted':0}

    def test_exact_nonempty_all_pass_required(self):
        self.assertTrue(audit.successful(self.good()))
        for key,value in [('found',0),('found',159),('succeeded',159),('skipped',1),('aborted',1),('failed',1),
                          ('containersFailed',1),('containersSkipped',1),('containersAborted',1),('javaSpecificationVersion','17')]:
            with self.subTest(key=key,value=value):self.assertFalse(audit.successful({**self.good(),key:value}))

    def test_summary_must_be_unique_structured_output(self):
        line='AETHERIS_PROTOCOL_TESTS='+json.dumps(self.good())
        self.assertEqual(self.good(),audit.parse_summary('ignored text\n'+line))
        for value in ('',line+'\n'+line,'AETHERIS_PROTOCOL_TESTS=[]'):
            with self.subTest(value=value),self.assertRaises(audit.AuditFailure):audit.parse_summary(value)

    def test_no_inherited_credentials_or_jvm_injection(self):
        self.assertEqual({'PATH':'safe'},audit.clean_environment({'PATH':'safe','CLASSPATH':'unreviewed',
            'JAVA_TOOL_OPTIONS':'secret','_JAVA_OPTIONS':'secret','JDK_JAVA_OPTIONS':'secret','DB_PASSWORD':'secret',
            'JWT_SECRET':'secret','AETHERIS_INTERNAL_TOKEN':'secret','DEEPSEEK_API_KEY':'paid','COMPOSE_FILE':'real.yml'}))

    def test_output_cannot_escape_or_reuse_existing_directory(self):
        for path in (ROOT,ROOT/'libs/security-protocol',audit.OUTPUT,audit.OUTPUT/'..'/'outside'):
            with self.subTest(path=path),self.assertRaises(ValueError):audit.output_directory(path,'f'*32)
        self.assertEqual(audit.OUTPUT.resolve()/('f'*32),audit.output_directory(audit.OUTPUT/('f'*32),'f'*32))
        with self.assertRaises(ValueError):audit.output_directory(audit.OUTPUT/('f'*32),'../bad')

    def test_host_inputs_exactly_eleven_jars_plus_cached_launcher(self):
        module,reports,jars,sources,artifacts=audit.inputs()
        self.assertEqual(12,len(jars));self.assertEqual('junit-platform-launcher-1.11.4.jar',jars[-1].name)
        self.assertTrue(reports);self.assertTrue(artifacts)
        self.assertIn('libs/security-protocol/src/test/resources/vectors/v2-fixture.json',sources)
        self.assertTrue(any(path.endswith('UserProofBindingTest.class') for path in artifacts))

    def test_java_helper_is_fail_closed_and_does_not_print_failures_or_test_output(self):
        text=(ROOT/'scripts/security/RunProtocolTests.java').read_text(encoding='utf-8')
        self.assertIn('if(initial==0)throw',text)
        self.assertIn('selectPackage("com.aetheris.security.protocol")',text)
        self.assertIn('OutputStream.nullOutputStream()',text)
        self.assertNotIn('printStackTrace',text)
        self.assertNotIn('printFailuresTo',text)
        self.assertIn('summary.getTestsSkippedCount()==0',text)


if __name__=='__main__':unittest.main()

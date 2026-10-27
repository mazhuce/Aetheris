import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('protocol_runtime_audit',ROOT/'scripts/audit-protocol-runtime.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class ProtocolRuntimeAuditTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'output').mkdir(exist_ok=True)
        for name in ('Popen','run'):
            blocker=patch.object(audit.subprocess,name,side_effect=AssertionError('No external tool in protocol fixture tests'))
            blocker.start();self.addCleanup(blocker.stop)

    def good(self):return {'status':'passed','javaSpecificationVersion':'21','found':169,'succeeded':169,
        'failed':0,'skipped':0,'aborted':0,'containersFailed':0,'containersSkipped':0,'containersAborted':0}

    def test_exact_nonempty_all_pass_required(self):
        self.assertEqual(169,audit.EXPECTED)
        self.assertTrue(audit.successful(self.good()))
        for key,value in [('found',0),('found',160),('found',166),('found',168),('succeeded',168),('skipped',1),('aborted',1),('failed',1),
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
        with tempfile.TemporaryDirectory(prefix='protocol-inputs-test-',dir=ROOT/'output') as folder:
            fixture=self.host_fixture(Path(folder))
            with patch.object(audit,'ROOT',fixture.root),patch.object(Path,'home',return_value=fixture.home):
                module,reports,jars,sources,artifacts=audit.inputs()
            self.assertEqual(fixture.module,module)
            self.assertEqual([fixture.report],reports)
            self.assertEqual([*fixture.jars,fixture.launcher],jars)
            self.assertEqual(12,len(jars));self.assertEqual('junit-platform-launcher-1.11.4.jar',jars[-1].name)
            self.assertIn('libs/security-protocol/src/test/resources/vectors/v2-fixture.json',sources)
            self.assertTrue(any(path.endswith('UserProofBindingTest.class') for path in artifacts))
            self.assertEqual(2,len(artifacts))

    def test_host_inputs_reject_missing_surefire_reports_without_using_historical_evidence(self):
        with tempfile.TemporaryDirectory(prefix='protocol-inputs-test-',dir=ROOT/'output') as folder:
            fixture=self.host_fixture(Path(folder));fixture.report.unlink()
            with patch.object(audit,'ROOT',fixture.root),patch.object(Path,'home',return_value=fixture.home):
                with self.assertRaisesRegex(audit.AuditFailure,'^MissingHostSurefireEvidence$'):audit.inputs()

    def test_host_inputs_reject_a_missing_dependency_jar(self):
        with tempfile.TemporaryDirectory(prefix='protocol-inputs-test-',dir=ROOT/'output') as folder:
            fixture=self.host_fixture(Path(folder));fixture.jars[3].unlink()
            with patch.object(audit,'ROOT',fixture.root),patch.object(Path,'home',return_value=fixture.home):
                with self.assertRaisesRegex(audit.AuditFailure,'^ExactlyElevenCachedDependencyJarsRequired$'):audit.inputs()

    def test_host_inputs_reject_a_missing_cached_launcher(self):
        with tempfile.TemporaryDirectory(prefix='protocol-inputs-test-',dir=ROOT/'output') as folder:
            fixture=self.host_fixture(Path(folder));fixture.launcher.unlink()
            with patch.object(audit,'ROOT',fixture.root),patch.object(Path,'home',return_value=fixture.home):
                with self.assertRaisesRegex(audit.AuditFailure,'^MissingCachedLauncher1114$'):audit.inputs()

    def test_host_inputs_reject_failed_reports_and_old_or_partial_test_counts(self):
        for key,value,reason in [('failures','1','HostTestsNotAllSuccessful'),('errors','1','HostTestsNotAllSuccessful'),
                                 ('skipped','1','HostTestsNotAllSuccessful'),('tests','166','HostTestOrClasspathBaselineMismatch'),
                                 ('tests','168','HostTestOrClasspathBaselineMismatch')]:
            with self.subTest(key=key,value=value),tempfile.TemporaryDirectory(prefix='protocol-inputs-test-',dir=ROOT/'output') as folder:
                fixture=self.host_fixture(Path(folder))
                tree=ET.parse(fixture.report);tree.getroot().set(key,value);tree.write(fixture.report,encoding='utf-8')
                with patch.object(audit,'ROOT',fixture.root),patch.object(Path,'home',return_value=fixture.home):
                    with self.assertRaisesRegex(audit.AuditFailure,'^'+reason+'$'):audit.inputs()

    @staticmethod
    def host_fixture(root):
        """Synthetic input bytes only; never restore, build, execute or claim a host test/JAR acceptance."""
        module=root/'libs/security-protocol'
        home=root/'synthetic-home';cache=home/'.m2/repository'
        files={module/'pom.xml':b'<project>synthetic input fixture</project>',
               module/'src/main/java/com/aetheris/security/protocol/SyntheticProtocol.java':b'// synthetic source fixture',
               module/'src/test/java/com/aetheris/security/protocol/UserProofBindingTest.java':b'// synthetic test source fixture',
               module/'src/test/resources/vectors/v2-fixture.json':b'{"synthetic":true}',
               module/'target/classes/com/aetheris/security/protocol/SyntheticProtocol.class':b'synthetic class input, not executable',
               module/'target/test-classes/com/aetheris/security/protocol/UserProofBindingTest.class':b'synthetic test class input, not executable'}
        jars=[cache/'synthetic/dependencies'/('dependency-'+str(index)+'.jar') for index in range(11)]
        launcher=cache/'org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar'
        files.update({path:b'synthetic dependency input, not a runtime JAR' for path in [*jars,launcher]})
        for path,data in files.items():path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        report=module/'target/surefire-reports/TEST-synthetic.xml';report.parent.mkdir(parents=True)
        suite=ET.Element('testsuite',name='synthetic-input-contract',tests='169',failures='0',errors='0',skipped='0')
        properties=ET.SubElement(suite,'properties')
        classpath=os.pathsep.join(str(path.resolve()) for path in [module/'target/test-classes',module/'target/classes',*jars])
        ET.SubElement(properties,'property',name='surefire.test.class.path',value=classpath)
        ET.ElementTree(suite).write(report,encoding='utf-8',xml_declaration=True)
        return SimpleNamespace(root=root,module=module,home=home,jars=jars,launcher=launcher,report=report)

    def test_java_helper_is_fail_closed_and_does_not_print_failures_or_test_output(self):
        text=(ROOT/'scripts/security/RunProtocolTests.java').read_text(encoding='utf-8')
        self.assertIn('if(initial==0)throw',text)
        self.assertIn('selectPackage("com.aetheris.security.protocol")',text)
        self.assertIn('OutputStream.nullOutputStream()',text)
        self.assertNotIn('printStackTrace',text)
        self.assertNotIn('printFailuresTo',text)
        self.assertIn('summary.getTestsSkippedCount()==0',text)


if __name__=='__main__':unittest.main()

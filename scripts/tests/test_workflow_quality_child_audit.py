"""Pure producer contracts only: no Java, Docker, HTTP or existing target mutation."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]


def temporary_directory(prefix):
    # The Windows app sandbox may expose an unusable system TEMP; use this owned tree.
    (ROOT/'output').mkdir(exist_ok=True)
    return tempfile.TemporaryDirectory(prefix=prefix,dir=ROOT/'output')


def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


authority=load('quality_authority_contract_under_test','audit-workflow-quality-authority.py')
child=load('quality_child_contract_under_test','audit-workflow-quality-child.py')


class WorkflowQualityProducerContractTests(unittest.TestCase):
    def setUp(self):
        # Fail closed if a contract test accidentally reaches an actual external operation.
        for name in ('Popen','run'):
            blocker=patch.object(child.subprocess,name,side_effect=AssertionError('External processes forbidden in producer contract tests'))
            blocker.start();self.addCleanup(blocker.stop)

    @staticmethod
    def good_report(producer):
        return {'status':'passed','sourceHashesMatchAtEnd':True,
                'checks':[{'case':case,'passed':True} for case in sorted(producer.REQUIRED_CASES)],
                'cleanup':dict.fromkeys(producer.identity.REQUIRED_CLEANUP,True)}

    def test_exact_six_real_application_modules_are_shared_by_the_child_harness(self):
        expected={'auth','system','metadata','quality','workflow','gateway'}
        self.assertEqual(expected,set(child.MODULES))
        self.assertEqual(6,len(set(child.MODULES.values())))
        for module in (child.sql,child.fixed,child.identity):
            self.assertEqual(child.MODULES,module.MODULES)
            self.assertEqual(child.OUTPUT,module.OUTPUT)
            self.assertEqual(child.REQUIRED_CASES,module.REQUIRED_CASES)
        self.assertEqual({'processes','containers','credentialsRetired','privateKeysRemoved','volumesAbsent'},set(child.identity.REQUIRED_CLEANUP))

    def test_both_producers_bind_exact_current_migrations_and_critical_sources(self):
        for producer in (authority,child):
            with self.subTest(producer=producer.__name__):
                sources=producer.source_hashes();migrations=producer.base.database_contract.source_manifest()
                self.assertEqual({p.relative_to(ROOT).as_posix() for p in ROOT.glob('services/*/src/main/resources/db/migration/*.sql')},set(migrations))
                self.assertEqual(migrations,{p:sources[p] for p in migrations})
                self.assertIn('services/auth/src/main/resources/db/migration/V11__browser_session_families.sql',migrations)
                required={*migrations,'scripts/audit-workflow-quality-authority.py' if producer is authority else 'scripts/audit-workflow-quality-child.py',
                          'scripts/audit-identity-chain.py','scripts/audit-api-key-chain.py','scripts/database/contract.py',
                          'scripts/database/runner/pom.xml','libs/security-protocol/src/main/java/com/aetheris/security/protocol/Codec.java',
                          'libs/security-protocol/src/main/java/com/aetheris/security/protocol/execution/WorkflowQualityParent.java',
                          'services/workflow/src/main/resources/db/migration/V32__trusted_quality_parent.sql',
                          'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowExecutionStore.java',
                          'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowQualityFacade.java',
                          'services/quality/src/main/java/com/aetheris/quality/fixedexecution/FixedJson.java',
                          'services/quality/src/main/java/com/aetheris/quality/service/WorkflowChildQualityService.java',
                          'services/quality/src/main/java/com/aetheris/quality/service/QualityQueryClient.java',
                          'services/quality/src/main/java/com/aetheris/quality/service/QualityReceiptReconciler.java',
                          'services/quality/src/main/java/com/aetheris/quality/service/ExecutionRecoveryService.java'}
                if producer is authority:
                    required.update({'services/workflow/src/test/java/com/aetheris/workflow/service/WorkflowQualityAuthorityPostgresIT.java',
                                     'services/quality/src/test/java/com/aetheris/quality/service/QualityReceiptReconciliationPostgresIT.java',
                                     'services/quality/src/test/java/com/aetheris/quality/service/QualityReceiptReadbackWireTest.java'})
                else:
                    required.update(module+'/pom.xml' for module in child.MODULES.values())
                    required.update({'services/metadata/src/main/java/com/aetheris/metadata/fixedexecution/FixedQualityPeers.java',
                                     'services/metadata/src/main/java/com/aetheris/metadata/fixedexecution/FixedQualityManifest.java'})
                for relative in sorted(required):
                    self.assertTrue(relative in sources,'Missing source binding: '+relative)
                    self.assertEqual(hashlib.sha256((ROOT/relative).read_bytes()).hexdigest(),sources[relative],relative)

    def test_required_cases_include_upgrade_approval_recovery_and_real_child_bindings(self):
        self.assertEqual('com.aetheris.workflow.service.WorkflowQualityAuthorityPostgresIT',authority.uncertainty.TEST)
        self.assertEqual(19,authority.uncertainty.EXPECTED_TEST_COUNT)
        self.assertEqual({'v31-known-baseline','v34-upgrade-preserves-original-history','v32-preserves-sql-approved-projection',
                          'fresh-real-postgres-runtime-tests','runtime-tests-unskipped-and-all-pass',
                          'quality-original-receipt-runtime-unskipped'},set(authority.REQUIRED_CASES))
        self.assertEqual(child.sql.SQL_CASES|child.CHILD_CASES,child.REQUIRED_CASES)
        for case in ('child-exact-subject-parent-and-receipt','child-reader-connection-proven','child-parent-pins-rule',
                     'child-unapproved-zero-results','child-revocation-zero-reader-connections',
                     'child-parent-approver-revoked-zero-reader-connections','child-pipeline-exact-source-and-write',
                     'child-no-workload-submit-denied','child-no-workload-status-denied','cron-real-quartz-success'):
            self.assertIn(case,child.REQUIRED_CASES)

    def test_only_fresh_json_reports_in_each_owned_output_tree_are_accepted(self):
        self.assertEqual(ROOT/'output/audit-workflow-quality-authority',authority.OUTPUT)
        self.assertEqual(ROOT/'output/audit-workflow-quality-child',child.OUTPUT)
        self.assertNotEqual(authority.OUTPUT,child.OUTPUT)
        with temporary_directory('workflow-quality-output-contract-') as directory:
            root=Path(directory)
            for index,producer in enumerate((authority,child)):
                allowed=root/str(index);allowed.mkdir()
                with patch.object(producer.identity,'OUTPUT',allowed):
                    self.assertEqual((allowed/'new.json').resolve(),producer.identity.validate_output(allowed/'new.json'))
                    for invalid in (root/'outside.json',allowed,allowed/'old.txt',allowed/'..'/'escape.json'):
                        with self.subTest(producer=producer.__name__,path=str(invalid)),self.assertRaises(ValueError):
                            producer.identity.validate_output(invalid)
                    old=allowed/'historical-success.json';old.write_text('{"status":"passed"}',encoding='utf-8')
                    with self.assertRaises(ValueError):producer.identity.validate_output(old)
                    self.assertEqual('{"status":"passed"}',old.read_text(encoding='utf-8'))

    def test_complete_current_report_can_pass_without_promoting_a_failed_attempt(self):
        for producer in (authority,child):
            good=self.good_report(producer);producer.identity.finalize_status(good);self.assertEqual('passed',good['status'])
            old=self.good_report(producer);old['status']='failed';old['historicalStatus']='passed'
            producer.identity.finalize_status(old);self.assertEqual('failed',old['status'])

    def test_missing_duplicate_extra_failed_and_non_boolean_cases_block_acceptance(self):
        for producer in (authority,child):
            good=self.good_report(producer)
            cases=[[],good['checks'][:-1],good['checks']+[good['checks'][0]],
                   good['checks']+[{'case':'historical-success','passed':True}]]
            cases.extend([[{**item,'passed':value} if i==0 else item for i,item in enumerate(good['checks'])]
                          for value in (False,None,1,'true')])
            for checks in cases:
                candidate=copy.deepcopy(good);candidate['checks']=checks
                producer.identity.finalize_status(candidate);self.assertEqual('failed',candidate['status'])
            for name in ('sourceHashesMatchAtEnd','checks'):
                candidate=copy.deepcopy(good);candidate.pop(name)
                producer.identity.finalize_status(candidate);self.assertEqual('failed',candidate['status'])
            candidate=copy.deepcopy(good);candidate['sourceHashesMatchAtEnd']=False
            producer.identity.finalize_status(candidate);self.assertEqual('failed',candidate['status'])

    def test_each_cleanup_flag_is_required_and_must_be_literal_true(self):
        for producer in (authority,child):
            good=self.good_report(producer)
            for name in producer.identity.REQUIRED_CLEANUP:
                for value in (False,None,1,'true'):
                    candidate=copy.deepcopy(good);candidate['cleanup'][name]=value
                    producer.identity.finalize_status(candidate);self.assertEqual('failed',candidate['status'])
                candidate=copy.deepcopy(good);candidate['cleanup'].pop(name)
                producer.identity.finalize_status(candidate);self.assertEqual('failed',candidate['status'])

    def test_quality_receipt_pg_contract_adds_seven_fresh_tests_to_nineteen_workflow_tests(self):
        with temporary_directory('workflow-quality-pg-report-contract-') as directory:
            root=Path(directory);instance,suite=self.receipt_fixture(root)
            self.invoke_receipt_contract(instance,root,suite)
            self.assertEqual({'tests':19,'failures':0,'errors':0,'skipped':0},instance.report['workflowTests'])
            self.assertEqual({'tests':7,'failures':0,'errors':0,'skipped':0},instance.report['qualityReceiptTests'])
            self.assertEqual({'tests':26,'failures':0,'errors':0,'skipped':0},instance.report['tests'])
            self.assertEqual(0,instance.report['qualityReceiptTestExitCode'])
            self.assertEqual(hashlib.sha256((instance.folder/'quality-receipt-it.xml').read_bytes()).hexdigest(),instance.report['qualityReceiptReportSha256'])
            args=instance.command.call_args.args[0]
            self.assertIn('-o',args);self.assertIn('-Dtest=QualityReceiptReconciliationPostgresIT',args)
            self.assertIn('-Daetheris.quality.receipt.audit='+instance.run_id,args)

    def test_old_wrong_run_wrong_suite_skipped_failed_and_incomplete_pg_reports_are_rejected(self):
        variants=({'rewrite':False},{'marker':'old-run'},{'name':'com.aetheris.quality.service.OtherTest'},
                  {'counts':{'tests':7,'failures':0,'errors':0,'skipped':1}},
                  {'counts':{'tests':7,'failures':1,'errors':0,'skipped':0}},
                  {'counts':{'tests':7,'failures':0,'errors':1,'skipped':0}},
                  {'counts':{'tests':6,'failures':0,'errors':0,'skipped':0}}, {'command_failed':True})
        for variant in variants:
            with self.subTest(variant=variant),temporary_directory('workflow-quality-old-report-contract-') as directory:
                root=Path(directory);instance,suite=self.receipt_fixture(root)
                with self.assertRaises(authority.base.AuditFailure):self.invoke_receipt_contract(instance,root,suite,**variant)
                self.assertTrue(any(item['passed'] is False for item in instance.report['checks']))

    def test_workflow_pg_stage_also_refuses_a_previous_success_without_a_fresh_run_marker(self):
        for rewrite,marker in ((False,'old-run'),(True,'old-run'),(True,'current-contract-run')):
            with self.subTest(rewrite=rewrite,marker=marker),temporary_directory('workflow-quality-parent-report-') as directory:
                root=Path(directory);instance=authority.Audit.__new__(authority.Audit)
                instance.folder=root/'evidence';instance.folder.mkdir();instance.run_id='current-contract-run'
                instance.report={'checks':[],'ports':{'postgres':49100}};instance.secrets=[];instance.checkpoint=Mock()
                instance.base={'credentials':{'workflow':{'runtime':'synthetic-not-an-account'}}}
                suite=root/'services/workflow/target/surefire-reports'/('TEST-'+authority.uncertainty.TEST+'.xml');suite.parent.mkdir(parents=True)
                def xml(value):
                    node=ET.Element('testsuite',{'name':authority.uncertainty.TEST,'tests':'19','failures':'0','errors':'0','skipped':'0'})
                    ET.SubElement(ET.SubElement(node,'properties'),'property',{'name':'aetheris.uncertainty.audit','value':value})
                    return ET.tostring(node,encoding='unicode')
                suite.write_text(xml('old-run'),encoding='utf-8')
                def command(*args,**kwargs):
                    if rewrite:suite.write_text(xml(marker),encoding='utf-8')
                    return 'synthetic workflow report; no process was started'
                instance.command=Mock(side_effect=command)
                with patch.object(authority.uncertainty,'ROOT',root),patch.object(authority.uncertainty.shutil,'which',return_value='synthetic-mvn.cmd'):
                    if rewrite and marker==instance.run_id:
                        authority.uncertainty.Audit.tests(instance)
                        self.assertEqual({'tests':19,'failures':0,'errors':0,'skipped':0},instance.report['tests'])
                    else:
                        with self.assertRaises(authority.base.AuditFailure):authority.uncertainty.Audit.tests(instance)
                        self.assertTrue(any(item['passed'] is False for item in instance.report['checks']))

    @staticmethod
    def receipt_xml(marker,name='com.aetheris.quality.service.QualityReceiptReconciliationPostgresIT',counts=None):
        counts=counts or {'tests':7,'failures':0,'errors':0,'skipped':0}
        suite=ET.Element('testsuite',{'name':name,**{key:str(value) for key,value in counts.items()}})
        properties=ET.SubElement(suite,'properties')
        ET.SubElement(properties,'property',{'name':'aetheris.quality.receipt.audit','value':marker})
        return ET.tostring(suite,encoding='unicode')

    @classmethod
    def receipt_fixture(cls,root):
        instance=authority.Audit.__new__(authority.Audit);instance.folder=root/'evidence';instance.folder.mkdir()
        instance.run_id='current-contract-run';instance.report={'checks':[],'ports':{'postgres':49100},'compiledClasses':{}}
        instance.base={'credentials':{'quality':{'runtime':'synthetic-not-an-account'}}};instance.secrets=[]
        suite=root/'services/quality/target/surefire-reports/TEST-com.aetheris.quality.service.QualityReceiptReconciliationPostgresIT.xml'
        suite.parent.mkdir(parents=True);suite.write_text(cls.receipt_xml('historical-contract-run'),encoding='utf-8')
        return instance,suite

    @classmethod
    def invoke_receipt_contract(cls,instance,root,suite,*,rewrite=True,marker=None,name='com.aetheris.quality.service.QualityReceiptReconciliationPostgresIT',counts=None,command_failed=False):
        def workflow_tests(self):self.report['tests']={'tests':19,'failures':0,'errors':0,'skipped':0}
        def command(*args,**kwargs):
            if rewrite:suite.write_text(cls.receipt_xml(marker or instance.run_id,name,counts),encoding='utf-8')
            if command_failed:raise authority.base.AuditFailure('Synthetic command failure')
            return 'synthetic offline report; no process was started'
        instance.command=Mock(side_effect=command)
        with patch.object(authority,'ROOT',root),patch.object(authority.uncertainty.Audit,'tests',workflow_tests),patch.object(authority.shutil,'which',return_value='synthetic-mvn.cmd'):
            instance.tests()


class WorkflowQualityConnectionAttachmentTests(unittest.TestCase):
    def setUp(self):
        blocker=patch.object(child.subprocess,'run',side_effect=AssertionError('No Docker or other external process is permitted'))
        blocker.start();self.addCleanup(blocker.stop)

    @staticmethod
    def fixture(root):
        folder=root/'current-run';folder.mkdir();snapshots=[]
        for index in range(6):
            path=folder/('connection-'+str(index)+'.log')
            raw=('connection authorized: user=audit_source_reader database=fixture\n'*(index+1)).encode('utf-8');path.write_bytes(raw)
            snapshots.append({'path':path.relative_to(root).as_posix(),'sha256':hashlib.sha256(raw).hexdigest(),
                              'capturedAtUtc':'2026-10-26T02:03:04Z','readerConnections':index+1})
        return folder,{'connectionLogSnapshots':snapshots}

    def test_six_distinct_current_run_attachments_with_real_counts_and_utc_are_accepted(self):
        with temporary_directory('workflow-quality-attachments-') as directory:
            root=Path(directory);folder,report=self.fixture(root)
            with patch.object(child,'ROOT',root):self.assertTrue(child.connection_log_attachments_valid(report,folder))

    def test_replaced_and_missing_attachments_cannot_reuse_the_old_success_digest(self):
        for missing in (False,True):
            with self.subTest(missing=missing),temporary_directory('workflow-quality-tamper-') as directory:
                root=Path(directory);folder,report=self.fixture(root);path=root/report['connectionLogSnapshots'][0]['path']
                if missing:path.unlink()
                else:path.write_text('replaced attachment',encoding='utf-8')
                with patch.object(child,'ROOT',root):self.assertFalse(child.connection_log_attachments_valid(report,folder))

    def test_missing_duplicate_and_same_file_alias_snapshots_are_rejected(self):
        with temporary_directory('workflow-quality-duplicate-') as directory:
            root=Path(directory);folder,report=self.fixture(root)
            candidates=[{'connectionLogSnapshots':[]},{'connectionLogSnapshots':report['connectionLogSnapshots'][:-1]}]
            duplicate=copy.deepcopy(report);duplicate['connectionLogSnapshots'][-1]=copy.deepcopy(duplicate['connectionLogSnapshots'][0]);candidates.append(duplicate)
            alias=copy.deepcopy(duplicate);alias['connectionLogSnapshots'][-1]['path']='current-run/../'+alias['connectionLogSnapshots'][-1]['path'];candidates.append(alias)
            with patch.object(child,'ROOT',root):
                for candidate in candidates:self.assertFalse(child.connection_log_attachments_valid(candidate,folder))

    def test_attachment_from_another_run_or_parent_path_is_rejected_even_with_correct_digest(self):
        with temporary_directory('workflow-quality-boundary-') as directory:
            root=Path(directory);folder,report=self.fixture(root);outside=root/'old-run.log';raw=b'connection authorized: user=audit_source_reader\n';outside.write_bytes(raw)
            for path in ('old-run.log','current-run/../old-run.log',str(outside.resolve())):
                candidate=copy.deepcopy(report);candidate['connectionLogSnapshots'][0].update(path=path,sha256=hashlib.sha256(raw).hexdigest(),readerConnections=1)
                with self.subTest(path=path),patch.object(child,'ROOT',root):self.assertFalse(child.connection_log_attachments_valid(candidate,folder))

    def test_forged_counts_hashes_naive_times_and_non_utc_offsets_are_rejected(self):
        with temporary_directory('workflow-quality-snapshot-fields-') as directory:
            root=Path(directory);folder,report=self.fixture(root)
            changes=[{'readerConnections':999},{'readerConnections':True},{'readerConnections':'1'},{'readerConnections':-1},
                     {'sha256':'A'*64},{'sha256':'bad'},{'capturedAtUtc':'2026-10-26T02:03:04'},
                     {'capturedAtUtc':'2026-10-26T10:03:04+08:00'},{'capturedAtUtc':'not-a-time'}]
            with patch.object(child,'ROOT',root):
                for change in changes:
                    candidate=copy.deepcopy(report);candidate['connectionLogSnapshots'][0].update(change)
                    with self.subTest(change=change):self.assertFalse(child.connection_log_attachments_valid(candidate,folder))
                for field in ('path','sha256','capturedAtUtc','readerConnections'):
                    candidate=copy.deepcopy(report);candidate['connectionLogSnapshots'][0].pop(field)
                    with self.subTest(missing=field):self.assertFalse(child.connection_log_attachments_valid(candidate,folder))

    def test_symlink_attachment_is_refused_without_requiring_windows_symlink_privilege(self):
        with temporary_directory('workflow-quality-symlink-') as directory:
            root=Path(directory);folder,report=self.fixture(root)
            with patch.object(child,'ROOT',root),patch.object(Path,'is_symlink',return_value=True):
                self.assertFalse(child.connection_log_attachments_valid(report,folder))

    def test_cleanup_blocks_a_previous_pass_when_connection_evidence_is_missing(self):
        with temporary_directory('workflow-quality-cleanup-contract-') as directory:
            instance=child.Audit.__new__(child.Audit);instance.folder=Path(directory)
            instance.report={'status':'passed','checks':[]}
            with patch.object(child.sql.Audit,'cleanup') as parent_cleanup:
                instance.cleanup()
            parent_cleanup.assert_called_once()
            self.assertFalse(instance.report['connectionLogAttachmentsValidated'])
            self.assertEqual('failed',instance.report['status'])
            self.assertEqual('ConnectionLogAttachmentIntegrityFailed',instance.report['failure']['message'])


if __name__=='__main__':unittest.main()

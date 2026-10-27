import importlib.util
import hashlib
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('workflow_sql_chain_audit_test',ROOT/'scripts/audit-workflow-sql-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

EXPECTED_LOGOUT_CASES=frozenset((
    'grant-cron-approver','cron-actor-logout','cron-actor-old-browser-denied',
    'cron-approver-logout','cron-approver-old-browser-denied','cron-logout-account-versions-preserved',
    'cron-executed-after-both-family-revocations','cron-actor-new-browser-family','cron-approver-new-browser-family',
    'pipeline-owned-writer-blocked','pipeline-actor-logout','pipeline-actor-old-browser-denied',
    'pipeline-approver-logout','pipeline-approver-old-browser-denied','pipeline-logout-account-versions-preserved',
    'pipeline-owned-lock-released','pipeline-downstream-authorized-after-logout','pipeline-original-approval-preserved',
    'pipeline-actor-new-browser-family',
))

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
    def test_all_module_sources_helpers_and_exact_current_migrations_are_bound(self):
        audit.configure(True)
        values=audit.source_hashes();manifest=audit.base.database_contract.source_manifest()
        expected={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in ROOT.glob('services/*/src/main/resources/db/migration/V*__*.sql')}
        self.assertEqual(expected,manifest)
        self.assertEqual(manifest,{name:values[name] for name in manifest})
        self.assertIn('services/auth/src/main/resources/db/migration/V11__browser_session_families.sql',manifest)
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


class WorkflowSqlLogoutHarnessTests(unittest.TestCase):
    """Validator and mocked fixture safety only; never real SQL-chain acceptance."""

    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix='workflow-sql-harness-',dir=ROOT/'output')
        self.folder=Path(temporary.name).resolve()
        self.addCleanup(temporary.cleanup)
        self.external={}
        # A missed mock must fail instead of starting Docker, Java, Maven or another tool.
        for name in ('Popen','run'):
            blocker=patch.object(audit.subprocess,name,side_effect=AssertionError('External tools forbidden in harness tests'))
            self.external[name]=blocker.start();self.addCleanup(blocker.stop)
        audit.configure(False)

    def tearDown(self):audit.configure(False)

    def candidate(self):
        # An in-memory validator candidate, not an execution result or published producer artifact.
        return {'status':'passed','sourceHashesMatchAtEnd':True,
                'checks':[{'case':name,'passed':True} for name in sorted(audit.REQUIRED_CASES)],
                'cleanup':dict.fromkeys(audit.REQUIRED_CLEANUP,True)}

    def fixture(self):
        instance=audit.Audit.__new__(audit.Audit)
        instance.run_id='b'*32;instance.folder=self.folder
        instance.report=self.candidate();instance.lock_processes=[]
        instance.containers=['owned-postgres','owned-redis'];instance.local_docker_verified=True
        instance.docker=Mock(return_value=json.dumps([{'Config':{'Labels':{audit.LABEL:instance.run_id}}}]))
        instance.sql=Mock(return_value=1)
        def close_logs():
            for lock in instance.lock_processes:lock['log'].close()
        self.addCleanup(close_logs)
        return instance

    def process(self,returncode=None):
        process=Mock();process.pid=43210;process.returncode=returncode
        process.stdin.closed=False
        process.stdin.close.side_effect=lambda:setattr(process.stdin,'closed',True)
        process.poll.side_effect=lambda:process.returncode
        def wait(timeout):
            process.returncode=0
            return 0
        process.wait.side_effect=wait
        return process

    def lock(self,instance,process):
        lock={'process':process,'log':Mock(),'path':self.folder/'mock-lock.log',
              'application':'audit-logout-'+instance.run_id}
        instance.lock_processes.append(lock)
        return lock

    def test_all_nineteen_logout_cases_are_required_in_both_modes(self):
        self.assertEqual(19,len(EXPECTED_LOGOUT_CASES))
        self.assertEqual(EXPECTED_LOGOUT_CASES,audit.LOGOUT_CASES)
        for full_platform in (False,True):
            with self.subTest(full_platform=full_platform):
                audit.configure(full_platform)
                self.assertTrue(EXPECTED_LOGOUT_CASES.issubset(audit.REQUIRED_CASES))
                self.assertEqual(audit.REQUIRED_CASES,audit.identity.REQUIRED_CASES)
                candidate=self.candidate();audit.finalize_status(candidate)
                self.assertEqual('passed',candidate['status'])

    def test_each_missing_logout_case_blocks_finalization(self):
        for full_platform in (False,True):
            audit.configure(full_platform)
            for name in sorted(EXPECTED_LOGOUT_CASES):
                with self.subTest(full_platform=full_platform,case=name):
                    candidate=self.candidate()
                    candidate['checks']=[check for check in candidate['checks'] if check['case']!=name]
                    audit.finalize_status(candidate)
                    self.assertEqual('failed',candidate['status'])

    def test_each_duplicate_logout_case_blocks_finalization(self):
        for full_platform in (False,True):
            audit.configure(full_platform)
            for name in sorted(EXPECTED_LOGOUT_CASES):
                with self.subTest(full_platform=full_platform,case=name):
                    candidate=self.candidate();candidate['checks'].append({'case':name,'passed':True})
                    audit.finalize_status(candidate)
                    self.assertEqual('failed',candidate['status'])

    def test_each_failed_or_non_boolean_logout_case_blocks_finalization(self):
        for full_platform in (False,True):
            audit.configure(full_platform)
            for name in sorted(EXPECTED_LOGOUT_CASES):
                for value in (False,None,0,1,'true'):
                    with self.subTest(full_platform=full_platform,case=name,value=value):
                        candidate=self.candidate()
                        next(check for check in candidate['checks'] if check['case']==name)['passed']=value
                        audit.finalize_status(candidate)
                        self.assertEqual('failed',candidate['status'])

    def test_logout_checks_cannot_override_failed_status_source_drift_or_cleanup(self):
        good=self.candidate()
        variants=[{**copy.deepcopy(good),'status':'failed'},
                  {**copy.deepcopy(good),'sourceHashesMatchAtEnd':False}]
        for name in audit.REQUIRED_CLEANUP:
            missing=copy.deepcopy(good);del missing['cleanup'][name];variants.append(missing)
            failed=copy.deepcopy(good);failed['cleanup'][name]=False;variants.append(failed)
        for candidate in variants:
            with self.subTest(candidate=candidate):
                audit.finalize_status(candidate)
                self.assertEqual('failed',candidate['status'])

    def test_unverified_docker_or_missing_owned_container_never_starts_lock(self):
        for verified,containers in ((False,['owned-postgres']),(False,[]),(True,[])):
            with self.subTest(verified=verified,containers=containers):
                instance=self.fixture();instance.local_docker_verified=verified;instance.containers=containers
                with self.assertRaisesRegex(audit.base.AuditFailure,'UnverifiedFixtureLockTarget'):
                    instance.hold_output_lock()
                instance.docker.assert_not_called();instance.sql.assert_not_called()
                self.assertEqual([],instance.lock_processes)
        instance=self.fixture();del instance.local_docker_verified
        with self.assertRaisesRegex(audit.base.AuditFailure,'UnverifiedFixtureLockTarget'):instance.hold_output_lock()
        instance.docker.assert_not_called();self.external['Popen'].assert_not_called()
        self.assertEqual([],list(self.folder.iterdir()))

    def test_foreign_or_missing_container_label_never_starts_lock(self):
        for labels in ({},{audit.LABEL:'another-run'},{'unrelated-label':'b'*32}):
            with self.subTest(labels=labels):
                instance=self.fixture();instance.docker.return_value=json.dumps([{'Config':{'Labels':labels}}])
                with self.assertRaisesRegex(audit.base.AuditFailure,'FixtureLockOwnershipMismatch'):
                    instance.hold_output_lock()
                instance.docker.assert_called_once_with('inspect','owned-postgres')
                instance.sql.assert_not_called();self.assertEqual([],instance.lock_processes)
        self.external['Popen'].assert_not_called();self.assertEqual([],list(self.folder.iterdir()))

    def test_verified_owned_lock_is_registered_before_waiting_for_real_readiness(self):
        instance=self.fixture();process=self.process()
        with patch.object(audit.subprocess,'Popen',return_value=process) as launch, \
                patch.object(audit.time,'sleep',side_effect=AssertionError('Unexpected readiness sleep')):
            lock=instance.hold_output_lock()
        self.assertIs(lock,instance.lock_processes[0]);self.assertIs(process,lock['process'])
        args,kwargs=launch.call_args
        self.assertEqual(['docker','exec','-i','owned-postgres','psql','-X','-qAt','-U','audit_admin',
                          '-d','aetheris_api_key_audit','-v','ON_ERROR_STOP=1'],args[0])
        self.assertEqual(instance.folder,kwargs['cwd']);self.assertIs(lock['log'],kwargs['stdout'])
        self.assertIs(audit.subprocess.PIPE,kwargs['stdin'])
        process.stdin.write.assert_called_once_with("SET application_name='audit-logout-"+instance.run_id+
            "'; SET idle_in_transaction_session_timeout='20s'; BEGIN; LOCK TABLE business.sql_output IN SHARE MODE;\n")
        process.stdin.flush.assert_called_once()
        query,scalar=instance.sql.call_args.args
        self.assertTrue(scalar);self.assertIn("a.application_name='"+lock['application']+"'",query)
        self.assertIn("l.relation='business.sql_output'::regclass",query)
        self.assertIn("l.mode='ShareLock' AND l.granted",query)

    def test_lock_spawn_failure_closes_unregistered_log(self):
        instance=self.fixture();log=Mock()
        with patch.object(Path,'open',return_value=log), \
                patch.object(audit.subprocess,'Popen',side_effect=OSError('synthetic spawn failure')):
            with self.assertRaisesRegex(OSError,'synthetic spawn failure'):instance.hold_output_lock()
        log.close.assert_called_once()
        self.assertEqual([],instance.lock_processes);instance.sql.assert_not_called()

    def test_lock_early_exit_remains_owned_for_cleanup(self):
        instance=self.fixture();process=self.process(returncode=7)
        with patch.object(audit.subprocess,'Popen',return_value=process):
            with self.assertRaisesRegex(audit.base.AuditFailure,'OwnedLockProcessExited'):instance.hold_output_lock()
        self.assertIs(process,instance.lock_processes[0]['process'])
        instance.sql.assert_not_called()

    def test_lock_readiness_timeout_remains_owned_and_never_claims_acquired(self):
        instance=self.fixture();process=self.process();instance.sql.return_value=0
        with patch.object(audit.subprocess,'Popen',return_value=process), \
                patch.object(audit.time,'monotonic',side_effect=[0,0,5]), \
                patch.object(audit.time,'sleep') as sleep:
            with self.assertRaisesRegex(audit.base.AuditFailure,'OwnedLockReadinessDeadline'):instance.hold_output_lock()
        self.assertIs(process,instance.lock_processes[0]['process'])
        instance.sql.assert_called_once();sleep.assert_called_once_with(.1)

    def test_release_rejects_an_equal_but_unowned_lock_before_process_io(self):
        instance=self.fixture();process=self.process();owned=self.lock(instance,process)
        with self.assertRaisesRegex(audit.base.AuditFailure,'UnknownLockProcess'):instance.release_output_lock(dict(owned))
        process.poll.assert_not_called();process.stdin.write.assert_not_called();process.wait.assert_not_called()

    def test_release_rolls_back_and_waits_for_only_the_owned_process(self):
        instance=self.fixture();process=self.process();lock=self.lock(instance,process)
        instance.release_output_lock(lock)
        process.stdin.write.assert_called_once_with('ROLLBACK;\n\\q\n')
        process.stdin.flush.assert_called_once();process.stdin.close.assert_called_once()
        process.wait.assert_called_once_with(timeout=5);process.terminate.assert_not_called()
        self.assertEqual(0,process.returncode)

    def test_already_exited_release_requires_zero_exit_and_does_not_write(self):
        for code in (0,7):
            with self.subTest(exit_code=code):
                instance=self.fixture();process=self.process(returncode=code);lock=self.lock(instance,process)
                if code==0:instance.release_output_lock(lock)
                else:
                    with self.assertRaisesRegex(audit.base.AuditFailure,'OwnedLockReleaseFailed'):instance.release_output_lock(lock)
                process.stdin.write.assert_not_called();process.wait.assert_not_called()

    def test_normal_cleanup_records_exit_and_normal_transaction_release(self):
        instance=self.fixture();process=self.process();lock=self.lock(instance,process)
        with patch.object(audit.fixed.Audit,'cleanup',autospec=True) as inherited_cleanup:instance.cleanup()
        inherited_cleanup.assert_called_once_with(instance);lock['log'].close.assert_called_once()
        self.assertEqual([{'pid':process.pid,'exited':True,'transactionReleasedNormally':True}],instance.report['fixtureLockCleanup'])
        audit.finalize_status(instance.report);self.assertEqual('passed',instance.report['status'])

    def test_broken_stdin_cleanup_terminates_owned_process_and_preserves_failure(self):
        instance=self.fixture();process=self.process();lock=self.lock(instance,process)
        process.stdin.write.side_effect=BrokenPipeError('synthetic closed pipe')
        process.terminate.side_effect=lambda:setattr(process,'returncode',-15)
        process.wait.side_effect=lambda timeout:process.returncode
        with patch.object(audit.fixed.Audit,'cleanup',autospec=True) as inherited_cleanup:instance.cleanup()
        inherited_cleanup.assert_called_once_with(instance);process.terminate.assert_called_once()
        process.stdin.close.assert_called_once();lock['log'].close.assert_called_once()
        self.assertEqual([{'pid':process.pid,'exited':True,'transactionReleasedNormally':False}],instance.report['fixtureLockCleanup'])
        audit.finalize_status(instance.report);self.assertEqual('failed',instance.report['status'])

    def test_still_running_cleanup_never_reports_exit_or_pass(self):
        instance=self.fixture();process=self.process();lock=self.lock(instance,process)
        process.wait.side_effect=audit.subprocess.TimeoutExpired('synthetic owned process',5)
        with patch.object(audit.fixed.Audit,'cleanup',autospec=True) as inherited_cleanup:instance.cleanup()
        inherited_cleanup.assert_called_once_with(instance);process.terminate.assert_called_once()
        self.assertEqual([{'pid':process.pid,'exited':False,'transactionReleasedNormally':False}],instance.report['fixtureLockCleanup'])
        lock['log'].close.assert_called_once();audit.finalize_status(instance.report)
        self.assertEqual('failed',instance.report['status'])

    def test_empty_cleanup_has_no_phantom_lock_and_calls_inherited_cleanup(self):
        instance=self.fixture()
        with patch.object(audit.fixed.Audit,'cleanup',autospec=True) as inherited_cleanup:instance.cleanup()
        inherited_cleanup.assert_called_once_with(instance);self.assertEqual([],instance.report['fixtureLockCleanup'])
        self.external['Popen'].assert_not_called();self.external['run'].assert_not_called()

if __name__=='__main__':unittest.main()

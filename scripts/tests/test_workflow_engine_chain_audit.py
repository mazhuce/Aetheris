"""Artifact/ownership guards only; no Docker, Java or external HTTP execution."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock,patch
import zipfile
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('engine_chain_guard_tests',ROOT/'scripts/audit-workflow-engine-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

EXPECTED_SQL_CASES=frozenset((
    'actual-applications-healthy','workflow-admin-login','register-sql-target','create-sql-approver','sql-approver-login','create-sql-actor','workflow-operator-login',
    'workflow-calendar','workflow-calendar-date-valid','create-manual-sql','unapproved-sql-denied','unapproved-sql-zero-effects',
    'grant-sql-actor','grant-sql-approver','grant-approval-datasource','sql-approval-state','delegated-sql-approval','operator-sql-success',
    'sql-real-row-and-writer','sql-sealed-actor-and-approver','invalid-manual-date-denied','invalid-date-zero-attempts',
    'revoke-sql-actor','sql-revoked-actor-denied','sql-revocation-zero-new-attempt','create-cron-sql','cron-approval-state','cron-approve',
    'grant-cron-executor','cron-bound-state','cron-bind','cron-real-quartz-success','cron-date-and-writer','cron-generation-and-actor-seal',
    'workflow-no-workload-lookup-denied','workflow-sql-real-service-nonces','sql-owner-no-arbitrary-query',
    'pipeline-create-upstream','pipeline-create-target','pipeline-add-dependency',
    'pipeline-grant-actor-upstream','pipeline-grant-actor-target','pipeline-grant-approver-upstream','pipeline-grant-approver-target',
    'pipeline-unapproved-denied','pipeline-unapproved-zero-effects','pipeline-upstream-approval-state','pipeline-upstream-approve',
    'pipeline-target-approval-state','pipeline-target-approve','pipeline-invalid-date-denied','pipeline-invalid-date-zero-runs',
    'pipeline-start','pipeline-real-success','pipeline-detail','pipeline-real-rows-and-writer','pipeline-sealed-source-and-subject',
    'pipeline-private-snapshot-redacted','pipeline-revoke-target','pipeline-revoked-start-denied','pipeline-revoked-zero-new-effects',
    'grant-cron-approver','cron-actor-logout','cron-actor-old-browser-denied','cron-approver-logout','cron-approver-old-browser-denied',
    'cron-logout-account-versions-preserved','cron-executed-after-both-family-revocations','cron-actor-new-browser-family','cron-approver-new-browser-family',
    'pipeline-owned-writer-blocked','pipeline-actor-logout','pipeline-actor-old-browser-denied','pipeline-approver-logout','pipeline-approver-old-browser-denied',
    'pipeline-logout-account-versions-preserved','pipeline-owned-lock-released','pipeline-downstream-authorized-after-logout',
    'pipeline-original-approval-preserved','pipeline-actor-new-browser-family',
))
EXPECTED_ENGINE_CASES=frozenset((
    'engine-registry-fixture','engine-create-actor','engine-actor-login','engine-create-approver','engine-approver-login',
    'engine-spark-approved-source','engine-actor-cannot-approve','engine-lost-response-one-post','engine-original-origin-after-runner-change',
    'engine-spark-terminal-bound','engine-cancel-holds-capacity','engine-cancel-confirmed-releases','engine-flink-original-source',
    'engine-flink-gateway-finished-is-not-job-completion','engine-flink-job-terminal-bound','engine-flink-revoked-between-session-and-insert',
    'engine-flink-streaming-unexpected-finish-is-failed','engine-streaming-pipeline-zero-sql-downstream',
    'engine-mixed-pipeline-source-and-business-write','engine-no-workload-auth-denied','engine-worker-no-token-denied',
    'engine-spark-cron-exact-source','engine-flink-cron-exact-source','engine-default-origin-no-authenticated-submit',
    'engine-real-auth-denials-persisted','engine-wire-traces-complete',
    'engine-queued-actor-grant-zero-dispatch','engine-queued-task-disabled-zero-dispatch',
    'engine-queued-definition-changed-zero-dispatch','engine-queued-artifact-revoked-zero-dispatch',
    'engine-queued-approver-disabled-zero-dispatch','engine-queued-same-name-recreated-zero-dispatch','engine-queued-auth-unavailable-zero-dispatch',
))
EXPECTED_CLEANUP=frozenset(('processes','containers','credentialsRetired','privateKeysRemoved','volumesAbsent'))
EXPECTED_PEER_CLEANUP=frozenset(('enginePeersStopped','engineTraceRetained'))

class EngineChainProducerGuards(unittest.TestCase):
    def test_missing_timezone_dependency_stops_before_any_docker_operation(self):
        instance=audit.Audit.__new__(audit.Audit);instance.checkpoint=Mock();instance.report={};instance.docker=Mock(side_effect=AssertionError('No Docker operation'))
        with patch.object(audit,'ZoneInfo',side_effect=audit.ZoneInfoNotFoundError('missing fixture tzdata')):
            with self.assertRaisesRegex(audit.base.AuditFailure,'AuditTimezoneDependencyMissing'):instance.setup_containers()
        instance.docker.assert_not_called()
    def test_all_new_and_inherited_checks_source_match_and_peer_cleanup_are_mandatory(self):
        required=EXPECTED_SQL_CASES|EXPECTED_ENGINE_CASES
        self.assertEqual(EXPECTED_SQL_CASES,audit.sql.SQL_CASES)
        self.assertEqual(EXPECTED_ENGINE_CASES,audit.ENGINE_CASES)
        self.assertEqual(required,audit.REQUIRED_CASES)
        for module in (audit.child,audit.sql,audit.fixed,audit.identity):self.assertEqual(required,module.REQUIRED_CASES)
        self.assertEqual(EXPECTED_CLEANUP,audit.identity.REQUIRED_CLEANUP)
        self.assertEqual(EXPECTED_PEER_CLEANUP,audit.PEER_CLEANUP)
        # This synthetic validator input is never written as a real producer result.
        baseline={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'case':c,'passed':True} for c in sorted(required)],'cleanup':dict.fromkeys(EXPECTED_CLEANUP,True),'enginePeerCleanup':dict.fromkeys(EXPECTED_PEER_CLEANUP,True)}
        good=copy.deepcopy(baseline);audit.identity.finalize_status(good);self.assertEqual('passed',good['status'])
        bad=[]
        for i in range(len(baseline['checks'])):
            missing=copy.deepcopy(baseline);del missing['checks'][i];bad.append(missing)
            duplicate=copy.deepcopy(baseline);duplicate['checks'].append(copy.deepcopy(duplicate['checks'][i]));bad.append(duplicate)
            for value in (False,None,0,1,'true'):
                failed=copy.deepcopy(baseline);failed['checks'][i]['passed']=value;bad.append(failed)
        for key in baseline['cleanup']:
            failed=copy.deepcopy(baseline);failed['cleanup'][key]=False;bad.append(failed)
            missing=copy.deepcopy(baseline);del missing['cleanup'][key];bad.append(missing)
        for key in baseline['enginePeerCleanup']:
            failed=copy.deepcopy(baseline);failed['enginePeerCleanup'][key]=False;bad.append(failed)
            missing=copy.deepcopy(baseline);del missing['enginePeerCleanup'][key];bad.append(missing)
        bad.extend([{**baseline,'sourceHashesMatchAtEnd':False},{**baseline,'checks':baseline['checks']+[baseline['checks'][0]]}])
        for report in bad:audit.identity.finalize_status(report);self.assertEqual('failed',report['status'])
    def test_source_manifest_binds_real_auth_workflow_peers_and_cache_verifier(self):
        sources=audit.source_hashes()
        for name in ('scripts/audit-workflow-engine-chain.py','scripts/audit_workflow_engine_peers.py','scripts/security/artifact_bundle.py','scripts/audit-fixed-execution-chain.py',
                     'services/workflow/src/main/java/com/aetheris/workflow/fixedexecution/WorkflowEngineAuthorizationClient.java',
                     'services/auth/src/main/java/com/aetheris/auth/fixedexecution/EngineExecutionAuthorizationService.java'):
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),sources[name])
    def test_compute_override_cannot_replace_database_or_signing_credentials(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'output') as directory:
            instance=audit.Audit.__new__(audit.Audit);instance.checkpoint=Mock();instance.folder=Path(directory);instance.run_id='1'*32
            instance.report={'ports':{'postgres':12001,'redis':12002}};instance.reservations=[];instance.processes=[];instance.secrets=[]
            instance.private_keys=Path(directory)/'keys';instance.java='fixture-java';instance.secret=Mock(return_value='fixture-only-secret')
            instance.key_manifest={'services':{'workflow':{'workloadKid':'workflow-kid','workloadPrivateFile':'workflow.pem'}}}
            instance.base={'credentials':{'workflow':{'runtime':'workflow-fixture-password'}}};instance.workflow_engine_environment=Mock(return_value={'DB_PASSWORD':'must-not-override'})
            with patch.object(audit.fixed,'MODULES',{'workflow':'services/workflow'}),patch.object(audit.base,'reserve_port',side_effect=[(Mock(),20001),(Mock(),20002)]),patch.object(audit.fixed.subprocess,'Popen',side_effect=AssertionError('No external process')) as launch:
                with self.assertRaises(audit.base.AuditFailure):audit.fixed.Audit.start_apps(instance,{'workflow':Path(directory)/'workflow.jar'},'fixture-redis')
                launch.assert_not_called()


class EngineScenarioContractTests(unittest.TestCase):
    """Exercise producer decisions with synthetic transports, never real engine acceptance."""

    def setUp(self):
        for name in ('Popen','run'):
            blocker=patch.object(audit.fixed.subprocess,name,side_effect=AssertionError('External tools forbidden'))
            blocker.start();self.addCleanup(blocker.stop)
        blocker=patch.object(audit.peers,'Peer',side_effect=AssertionError('No HTTP peer server in contract tests'))
        blocker.start();self.addCleanup(blocker.stop)

    def instance(self):
        instance=audit.Audit.__new__(audit.Audit)
        instance.report={};instance.operations=[];instance.admin=object();instance.user=object();instance.actor_id=42
        instance.flink=SimpleNamespace(origin='http://127.0.0.1:19020',finish=Mock())
        instance.grant=Mock(return_value={'id':9});instance.check=Mock()
        return instance

    def approved_state(self):return {'executionRevision':2,'definitionSha256':'b'*64,'approved':True}

    def public_calls(self,instance,returned_mode=None):
        def call(client,method,path,expected,body=None):
            if method=='POST' and path=='/api/workflow/tasks':
                return {'task':{'id':7,'flinkMode':returned_mode or body.get('flinkMode')}}
            if method=='GET' and path=='/api/workflow/tasks/7/approval':
                return {'executionRevision':1,'definitionSha256':'a'*64,'approved':False}
            if method=='POST' and path=='/api/workflow/tasks/7/approve':return self.approved_state()
            raise AssertionError('Unexpected synthetic HTTP call: '+method+' '+path)
        instance.call=Mock(side_effect=call)

    def source(self,mode='bounded',kind='manual'):
        return {'instance':11,'task':7,'attempt':22,'fence':3,'kind':kind,'uid':42,'av':4,'approvalUid':43,
                'revision':2,'approvalRevision':2,'definitionHash':'b'*64,'approvalDefinitionHash':'b'*64,
                'token':True,'origin':'http://127.0.0.1:19020','handle':'1'*32+'/'+('2'*32),'receipt':'c'*64,
                'receiptHashValid':True,'sourceHashValid':True,'jobId':'3'*32,
                'material':{'mode':mode,'jobManagerOrigin':'http://127.0.0.1:19020'},'result':None}

    def done(self,source):
        mode=source['material']['mode'];done=copy.deepcopy(source)
        done.update(state='failed' if mode=='streaming' else 'success',result='d'*64,resultHashValid=True,
                    resultState='failed' if mode=='streaming' else 'succeeded',resultMode=mode,remoteJobState='FINISHED',
                    resultErrorCode='FLINK_UNEXPECTED_FINISH' if mode=='streaming' else None,
                    resultJobId=source['jobId'],resultJobManagerOrigin=source['material']['jobManagerOrigin'])
        return done

    def test_default_flink_fixture_uses_public_bounded_and_retains_the_actual_approval(self):
        instance=self.instance();self.public_calls(instance)
        task,grant=instance.task('flink')
        create=instance.call.call_args_list[0]
        self.assertEqual('POST',create.args[1]);self.assertEqual('/api/workflow/tasks',create.args[2])
        self.assertEqual('bounded',create.args[4]['flinkMode'])
        self.assertEqual(7,task);self.assertEqual({'id':9},grant)
        self.assertEqual([{'taskId':7,'mode':'bounded',**self.approved_state()}],instance.report['flinkTaskApprovals'])

    def test_streaming_negative_fixture_is_explicit_and_public_batch_is_not_a_success_fixture(self):
        instance=self.instance();self.public_calls(instance)
        instance.task('flink',flink_mode='streaming')
        self.assertEqual('streaming',instance.call.call_args_list[0].args[4]['flinkMode'])
        self.assertEqual('streaming',instance.report['flinkTaskApprovals'][0]['mode'])
        for mode in ('batch','STREAMING','',None):
            instance=self.instance();self.public_calls(instance)
            with self.subTest(mode=mode),self.assertRaises(ValueError):instance.task('flink',flink_mode=mode)
            instance.call.assert_not_called()

    def test_wrong_public_mode_or_missing_approval_never_becomes_accepted_fixture(self):
        instance=self.instance();self.public_calls(instance,returned_mode='streaming')
        with self.assertRaises(audit.base.AuditFailure):instance.task('flink')
        self.assertFalse(any(call.args[2].endswith('/approve') for call in instance.call.call_args_list))
        instance=self.instance();self.public_calls(instance)
        original=instance.call.side_effect
        instance.call.side_effect=lambda *args:({**self.approved_state(),'approved':False} if args[2].endswith('/approve') else original(*args))
        with self.assertRaises(audit.base.AuditFailure):instance.task('flink')
        self.assertNotIn('flinkTaskApprovals',instance.report)

    def test_finished_result_requires_exact_source_mode_receipt_job_and_approval(self):
        for mode,expected in (('bounded','success'),('streaming','failed')):
            source=self.source(mode);done=self.done(source)
            self.assertTrue(audit.flink_result_bound(source,done,mode,expected))
            variants=[]
            for key in ('instance','task','attempt','fence','kind','uid','av','approvalUid','revision','approvalRevision',
                        'definitionHash','approvalDefinitionHash','origin','handle','receipt','jobId'):
                changed=copy.deepcopy(done);changed[key]='another-binding';variants.append(changed)
            for key in ('receiptHashValid','sourceHashValid','resultHashValid'):
                changed=copy.deepcopy(done);changed[key]=False;variants.append(changed)
            for key in ('result','resultState','resultMode','remoteJobState','resultJobId','resultJobManagerOrigin'):
                changed=copy.deepcopy(done);changed[key]=None;variants.append(changed)
            changed=copy.deepcopy(done);changed['material']['mode']='streaming' if mode=='bounded' else 'bounded';variants.append(changed)
            changed=copy.deepcopy(done);changed['state']='success' if expected=='failed' else 'failed';variants.append(changed)
            changed=copy.deepcopy(done);changed['resultErrorCode']=None if mode=='streaming' else 'FLINK_UNEXPECTED_FINISH';variants.append(changed)
            for changed in variants:
                with self.subTest(mode=mode,changed=changed):self.assertFalse(audit.flink_result_bound(source,changed,mode,expected))
        source=self.source();source['approvalRevision']=99
        self.assertFalse(audit.flink_result_bound(source,self.done(source),'bounded','success'))
        source=self.source();source['approvalDefinitionHash']='e'*64
        self.assertFalse(audit.flink_result_bound(source,self.done(source),'bounded','success'))

    def test_job_source_must_match_the_recorded_public_mode_and_approval_not_just_any_sealed_material(self):
        good={'taskId':7,'mode':'bounded',**self.approved_state()}
        variants=[(good,True),({**good,'mode':'streaming'},False),({**good,'executionRevision':3},False),
                  ({**good,'definitionSha256':'e'*64},False),({**good,'approved':False},False),(None,False)]
        for record,expected in variants:
            with self.subTest(record=record):
                instance=self.instance();source=self.source();instance.wait=Mock(return_value=source['jobId'])
                instance.state=Mock(return_value=source);instance.report['flinkTaskApprovals']=[record] if record else []
                if expected:self.assertEqual(source,instance.flink_job_source(11))
                else:
                    with self.assertRaisesRegex(audit.base.AuditFailure,'FlinkPublicApprovalSealMismatch'):instance.flink_job_source(11)

    def nullable_sql_fixture(self,instance,proof=None):
        # In-memory SQL NULL/COALESCE evaluation plus a psql-output adapter, not PostgreSQL acceptance.
        database=sqlite3.connect(':memory:');self.addCleanup(database.close)
        database.create_function('to_json',1,lambda value:None if value is None else json.dumps(value))
        database.executescript("""ATTACH DATABASE ':memory:' AS workflow;
            ATTACH DATABASE ':memory:' AS metadata;
            CREATE TABLE workflow.execution_attempt(id INTEGER,flink_job_id TEXT);
            CREATE TABLE workflow.task_instance(id INTEGER,active_attempt_id INTEGER);
            CREATE TABLE workflow.pipeline_node_run(pipeline_run_id INTEGER,task_id INTEGER,instance_id INTEGER);
            CREATE TABLE workflow.pipeline_run(id INTEGER,status TEXT);
            CREATE TABLE metadata.data_source(id INTEGER,name TEXT);
            INSERT INTO workflow.execution_attempt VALUES(22,NULL);
            INSERT INTO workflow.task_instance VALUES(11,22);
            INSERT INTO workflow.pipeline_node_run VALUES(90,7,NULL);
            INSERT INTO workflow.pipeline_run VALUES(90,'failed');
            INSERT INTO metadata.data_source VALUES(3,'workflow-target-writer');""")
        def psql(*args,stdin):
            if stdin.lstrip().startswith('SELECT json_build_object') and proof is not None:return json.dumps(proof)
            # Only translate the PostgreSQL literal cast; SQLite evaluates the actual NULL expression.
            row=database.execute(stdin.replace("'null'::json","'null'")).fetchone()
            return '' if row is None or row[0] is None else str(row[0])+'\n'
        instance.containers=['synthetic-never-started-postgres'];instance.docker=Mock(side_effect=psql)
        return database

    def test_unbound_job_json_null_waits_before_source_readback_or_finish(self):
        instance=self.instance();source=self.source();database=self.nullable_sql_fixture(instance)
        instance.state=Mock(return_value=source)
        instance.report['flinkTaskApprovals']=[{'taskId':7,'mode':'bounded',**self.approved_state()}]
        def bind_after_null(_seconds):
            instance.state.assert_not_called();instance.flink.finish.assert_not_called()
            database.execute('UPDATE workflow.execution_attempt SET flink_job_id=?',(source['jobId'],))
        with patch.object(audit.time,'sleep',side_effect=bind_after_null) as sleep:
            self.assertEqual(source,instance.flink_job_source(11))
        sleep.assert_called_once_with(.2);self.assertEqual(2,instance.docker.call_count)
        instance.state.assert_called_once_with(11);instance.flink.finish.assert_not_called()

    def test_unbound_pipeline_instance_json_null_waits_without_advancing_or_claiming_success(self):
        instance=self.pipeline_fixture();proof=instance.sql.side_effect
        # Keep the existing synthetic final oracle; use the inherited strict SQL parser for both polls.
        proof=list(proof)[1];del instance.sql;del instance.wait
        database=self.nullable_sql_fixture(instance,proof)
        def bind_after_null(_seconds):
            instance.flink_job_source.assert_not_called();instance.flink.finish.assert_not_called()
            instance.terminal.assert_not_called();instance.check.assert_not_called()
            database.execute('UPDATE workflow.pipeline_node_run SET instance_id=11')
        with patch.object(audit.time,'sleep',side_effect=bind_after_null) as sleep:instance.streaming_pipeline()
        sleep.assert_called_once_with(.2);instance.flink_job_source.assert_called_once_with(11)
        self.assertIs(True,instance.check.call_args.args[1])

    def test_binding_that_remains_json_null_reaches_deadline_without_evidence_or_finish(self):
        instance=self.instance();self.nullable_sql_fixture(instance);instance.state=Mock()
        with patch.object(audit.time,'monotonic',side_effect=[0,0,36]),patch.object(audit.time,'sleep') as sleep:
            with self.assertRaisesRegex(audit.base.AuditFailure,'EngineScenarioStateDeadline:None'):instance.flink_job_source(11)
        sleep.assert_called_once_with(.2);instance.state.assert_not_called();instance.flink.finish.assert_not_called()

    def test_absent_rows_empty_output_and_malformed_json_are_never_treated_as_unbound_success(self):
        for transport in ('missing-row','','not-json'):
            with self.subTest(transport=transport):
                instance=self.instance();database=self.nullable_sql_fixture(instance);instance.state=Mock()
                if transport=='missing-row':database.execute('DELETE FROM workflow.task_instance')
                else:instance.docker=Mock(return_value=transport)
                with patch.object(audit.time,'sleep') as sleep:
                    with self.assertRaises(json.JSONDecodeError):instance.flink_job_source(11)
                sleep.assert_not_called();instance.state.assert_not_called();instance.flink.finish.assert_not_called()

    def test_streaming_manual_finish_uses_original_job_and_requires_failed_result(self):
        for mutate in (False,True):
            instance=self.instance();source=self.source('streaming');done=self.done(source)
            if mutate:done['state']='success'
            instance.task=Mock(return_value=(7,{'id':9}));instance.run_task=Mock(return_value=({'id':11},source))
            instance.flink_job_source=Mock(return_value=source);instance.terminal=Mock(return_value=done)
            instance.flink_unexpected_finish()
            instance.task.assert_called_once_with('flink',flink_mode='streaming')
            instance.flink.finish.assert_called_once_with(source['jobId']);instance.terminal.assert_called_once_with(11,'failed')
            self.assertEqual('engine-flink-streaming-unexpected-finish-is-failed',instance.check.call_args.args[0])
            self.assertIs(not mutate,instance.check.call_args.args[1])

    def pipeline_fixture(self,proof=None,terminal=None):
        instance=self.instance();source=self.source('streaming',kind='pipeline')
        instance.task=Mock(return_value=(7,{'id':9}));instance.flink_job_source=Mock(return_value=source)
        instance.terminal=Mock(return_value=terminal or self.done(source));instance.wait=Mock(side_effect=[11,'failed'])
        proof=proof if proof is not None else {'state':'failed','nodes':2,'flinkState':'failed','sqlState':'skipped',
            'sqlInstance':None,'sqlAttempt':None,'sqlAttempts':0,'sqlInstances':0,'businessRows':0,'flinkSuccessEvidence':False}
        instance.sql=Mock(side_effect=[3,proof])
        def call(client,method,path,expected,body=None):
            if method=='POST' and path=='/api/workflow/tasks':return {'task':{'id':8}}
            if method=='GET' and path=='/api/workflow/tasks/8/approval':return {'executionRevision':1}
            if method=='POST' and path=='/api/workflow/tasks/8/approve':return self.approved_state()
            if method=='POST' and path=='/api/workflow/tasks/8/dependencies?upstreamTaskId=7':return {}
            if method=='POST' and path=='/api/workflow/pipelines':return {'id':90}
            raise AssertionError('Unexpected synthetic pipeline HTTP call: '+method+' '+path)
        instance.call=Mock(side_effect=call)
        return instance

    def test_streaming_pipeline_requires_failed_upstream_and_zero_sql_attempts_instances_and_rows(self):
        good={'state':'failed','nodes':2,'flinkState':'failed','sqlState':'skipped','sqlInstance':None,
              'sqlAttempt':None,'sqlAttempts':0,'sqlInstances':0,'businessRows':0,'flinkSuccessEvidence':False}
        variants=[(good,True)]
        for key,value in (('state','succeeded'),('nodes',3),('flinkState','succeeded'),('sqlState','succeeded'),
                          ('sqlInstance',31),('sqlAttempt',32),('sqlAttempts',1),('sqlInstances',1),('businessRows',1),('flinkSuccessEvidence',True)):
            variants.append(({**good,key:value},False))
        for proof,expected in variants:
            with self.subTest(proof=proof):
                instance=self.pipeline_fixture(proof);instance.streaming_pipeline()
                instance.task.assert_called_once_with('flink',flink_mode='streaming')
                instance.flink.finish.assert_called_once_with('3'*32);instance.terminal.assert_called_once_with(11,'failed')
                self.assertEqual('engine-streaming-pipeline-zero-sql-downstream',instance.check.call_args.args[0])
                self.assertIs(expected,instance.check.call_args.args[1])
                self.assertTrue(any(call.args[1:3]==('POST','/api/workflow/pipelines') and call.args[4]['targetTaskId']==8
                                    for call in instance.call.call_args_list))
        source=self.source('streaming',kind='pipeline');done=self.done(source);done['resultErrorCode']=None
        instance=self.pipeline_fixture(terminal=done);instance.streaming_pipeline()
        self.assertIs(False,instance.check.call_args.args[1])

    def test_fresh_build_delegates_only_current_five_modules_without_touching_old_bundle(self):
        instance=self.instance();instance.manifest=None;instance.anchor=None;instance.checkpoint=Mock()
        expected={'workflow':Path('synthetic-workflow.jar')}
        with patch.object(audit.sql.Audit,'build',autospec=True,return_value=expected) as build,patch.object(audit.bundle,'verify') as verify:
            self.assertEqual(expected,instance.build())
        build.assert_called_once_with(instance);verify.assert_not_called()
        self.assertEqual({'auth','system','metadata','workflow','gateway'},set(audit.identity.MODULES))
        self.assertEqual('fresh-current-five-service-build',instance.report['artifactBuild']['mode'])

    def test_bad_explicit_bundle_never_falls_back_to_fresh_build(self):
        instance=self.instance();instance.manifest='synthetic-manifest';instance.anchor='f'*64;instance.checkpoint=Mock()
        with patch.object(audit.bundle,'verify',side_effect=audit.bundle.BundleError('synthetic stale source')) as verify, \
                patch.object(audit.sql.Audit,'build',autospec=True) as build:
            with self.assertRaisesRegex(audit.base.AuditFailure,'CachedArtifactVerificationFailed'):instance.build()
        verify.assert_called_once_with(ROOT,instance.manifest,instance.anchor,audit.CACHE_MODULES);build.assert_not_called()

    def test_partial_bundle_arguments_are_rejected_before_base_initialization_or_run(self):
        for manifest,anchor in (('synthetic-manifest',None),(None,'f'*64)):
            with self.subTest(manifest=manifest,anchor=anchor),patch.object(audit.sql.Audit,'__init__',return_value=None) as initialize:
                with self.assertRaisesRegex(ValueError,'BothArtifactReuseArgumentsRequired'):audit.Audit('synthetic-output',manifest,anchor)
                initialize.assert_not_called()
        for argument in (['--artifacts-manifest','synthetic-manifest'],['--expected-manifest-sha256','f'*64]):
            with self.subTest(argument=argument),patch.object(audit.sys,'argv',['engine-audit','--output','synthetic-output',*argument]), \
                    patch.object(audit,'Audit') as constructor,patch.object(audit.sys,'stderr',io.StringIO()):
                with self.assertRaises(SystemExit) as failure:audit.main()
                self.assertEqual(2,failure.exception.code);constructor.assert_not_called()

    def test_cli_default_and_explicit_strict_reuse_are_distinct(self):
        for extra,manifest,anchor in (([],None,None),(['--artifacts-manifest','synthetic-manifest','--expected-manifest-sha256','f'*64],'synthetic-manifest','f'*64)):
            with self.subTest(extra=extra),patch.object(audit.sys,'argv',['engine-audit','--output','synthetic-output',*extra]), \
                    patch.object(audit,'Audit') as constructor,patch('builtins.print'):
                constructor.return_value.run.return_value={'status':'passed','checks':[],'cleanup':{}}
                self.assertEqual(0,audit.main());constructor.assert_called_once_with('synthetic-output',manifest,anchor)

class ArtifactBundleGuards(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT/'output');self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve();self.modules={'auth':'services/auth','gateway':'apps/gateway'}
        self.put('pom.xml',b'root-pom');self.put('configs/database/services.json',b'{}')
        for folder in [*self.modules.values(),'libs/security-protocol','libs/security-servlet']:
            self.put(folder+'/pom.xml',folder.encode());self.put(folder+'/src/main/X.java',b'class X {}')
            self.put(folder+'/target/classes/X.class',('compiled-'+folder).encode())
        self.run='2'*32;self.owner=self.root/'output/fixture'/self.run;self.owner.mkdir(parents=True)
        self.sources={p.relative_to(self.root).as_posix():audit.bundle.digest(p) for p in audit.bundle.source_files(self.root,self.modules)}
        self.entries={};artifacts={}
        for name,folder in self.modules.items():
            dest=self.owner/'retained-jars'/(name+'.jar');dest.parent.mkdir(exist_ok=True)
            with zipfile.ZipFile(dest,'w') as jar:
                jar.writestr('BOOT-INF/classes/X.class',(self.root/folder/'target/classes/X.class').read_bytes())
                for library in (['security-protocol'] if name=='gateway' else ['security-protocol','security-servlet']):
                    raw=io.BytesIO()
                    with zipfile.ZipFile(raw,'w') as nested:nested.writestr('X.class',(self.root/'libs'/library/'target/classes/X.class').read_bytes())
                    jar.writestr('BOOT-INF/lib/'+library+'-fixture.jar',raw.getvalue())
            item={'sha256':audit.bundle.digest(dest),'bytes':dest.stat().st_size};artifacts[name]=item;self.entries[name]={'path':dest.relative_to(self.root).as_posix(),**item}
        self.report=self.owner.parent/'report.json';self.report.write_text(json.dumps({'runId':self.run,'status':'passed','sourceHashes':self.sources,'artifacts':artifacts}),encoding='utf-8')
        self.manifest=self.owner/'retained-artifacts.json';self.manifest.write_text(json.dumps({'producerReport':self.report.relative_to(self.root).as_posix(),'producerReportSha256':audit.bundle.digest(self.report),'artifacts':self.entries}),encoding='utf-8');self.anchor=audit.bundle.digest(self.manifest)
    def put(self,name,data):
        path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path
    def verify(self):return audit.bundle.verify(self.root,self.manifest,self.anchor,self.modules)
    def test_exact_artifact_bytes_and_current_sources_are_reused_without_old_runtime_checks(self):
        jars,proof=self.verify();self.assertEqual(set(self.modules),set(jars));self.assertFalse(proof['historicalRuntimeChecksCopied']);self.assertTrue(proof['currentClassesAndResourcesVerified'])
    def test_manifest_anchor_and_original_report_cannot_be_replaced(self):
        with self.assertRaises(audit.bundle.BundleError):audit.bundle.verify(self.root,self.manifest,'a'*64,self.modules)
        self.report.write_text('{}',encoding='utf-8')
        with self.assertRaises(audit.bundle.BundleError):self.verify()
    def test_changed_or_removed_source_cannot_reuse_a_stale_compiled_class(self):
        source=self.root/'services/auth/src/main/X.java';old=source.read_bytes();source.write_bytes(b'changed source')
        with self.assertRaises(audit.bundle.BundleError):self.verify()
        source.write_bytes(old);source.unlink()
        with self.assertRaises(audit.bundle.BundleError):self.verify()
    def test_tampered_jar_or_missing_current_class_cannot_pass(self):
        jar=self.root/self.entries['auth']['path'];raw=jar.read_bytes();jar.write_bytes(raw+b'tamper')
        with self.assertRaises(audit.bundle.BundleError):self.verify()
        jar.write_bytes(raw);(self.root/'services/auth/target/classes/X.class').unlink()
        with self.assertRaises(audit.bundle.BundleError):self.verify()
    def test_source_report_cannot_omit_a_required_parent_build_input(self):
        report=json.loads(self.report.read_text(encoding='utf-8'));del report['sourceHashes']['pom.xml'];self.report.write_text(json.dumps(report),encoding='utf-8')
        manifest=json.loads(self.manifest.read_text(encoding='utf-8'));manifest['producerReportSha256']=audit.bundle.digest(self.report);self.manifest.write_text(json.dumps(manifest),encoding='utf-8');self.anchor=audit.bundle.digest(self.manifest)
        with self.assertRaises(audit.bundle.BundleError):self.verify()

if __name__=='__main__':unittest.main()

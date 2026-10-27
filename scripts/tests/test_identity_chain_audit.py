"""Tool-level safety checks only. Never start Java, Docker, HTTP or a platform service."""
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('identity_chain_under_test',ROOT/'scripts/audit-identity-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class StopBeforeExternalOperation(Exception):pass


class IdentityAuditSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'output').mkdir(exist_ok=True)

    def setUp(self):
        # An unexpected change in the harness must fail this test, never start a real tool.
        for name in ('Popen','run'):
            blocker=patch.object(audit.subprocess,name,side_effect=AssertionError('External processes are forbidden in tool safety tests'))
            blocker.start();self.addCleanup(blocker.stop)

    def test_report_must_be_fresh_json_inside_own_output_directory(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            allowed=Path(folder)/'allowed';allowed.mkdir()
            with patch.object(audit,'OUTPUT',allowed):
                self.assertEqual((allowed/'new.json').resolve(),audit.validate_output(allowed/'new.json'))
                for path in (Path(folder)/'outside.json',allowed/'bad.txt',allowed):
                    with self.assertRaises(ValueError):audit.validate_output(path)
                existing=allowed/'old.json';existing.write_text('preserved',encoding='utf-8')
                with self.assertRaises(ValueError):audit.validate_output(existing)
                self.assertEqual('preserved',existing.read_text(encoding='utf-8'))

    def test_environment_removes_inherited_credentials_routes_and_jvm_injection(self):
        unsafe={'DB_USER':'fixture','JWT_SECRET':'fixture','SPRING_CONFIG_IMPORT':'fixture','AETHERIS_INTERNAL_TOKEN':'fixture',
                'REDIS_PASSWORD':'fixture','AUTH_SERVICE_HOST':'external','SERVER_ADDRESS':'0.0.0.0','JAVA_TOOL_OPTIONS':'fixture',
                '_JAVA_OPTIONS':'fixture','JDK_JAVA_OPTIONS':'fixture','JAVA_OPTS':'fixture','MAVEN_OPTS':'fixture',
                'MAVEN_ARGS':'fixture','CLASSPATH':'fixture'}
        with patch.dict(os.environ,{**unsafe,'PATH':'known-system-path','JAVA_HOME':'reviewed-jdk'},clear=True):
            result=audit.clean_environment()
        self.assertFalse(set(unsafe)&set(result));self.assertEqual('known-system-path',result['PATH'])
        self.assertEqual('reviewed-jdk',result['JAVA_HOME'])

    def test_http_client_never_accepts_non_loopback_or_credential_origins(self):
        for origin in ('https://127.0.0.1:8080','http://example.com:8080','http://127.0.0.2:8080',
                       'http://user:pass@127.0.0.1:8080','http://127.0.0.1','http://127.0.0.1:8080/path'):
            with self.subTest(origin=origin),self.assertRaises(ValueError):audit.base.HttpClient(origin)
        audit.base.HttpClient('http://127.0.0.1:49152') # opener creation only, no request

    def test_remote_or_overridden_docker_endpoint_never_reaches_fixture_or_cleanup_engine(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            for index,(overrides,endpoint) in enumerate((({'DOCKER_HOST':'tcp://unapproved:2375'},'npipe:////./pipe/docker_engine'),
                                       ({},'ssh://unapproved'),({},'tcp://127.0.0.1:2375'))):
                scenario=Path(folder)/str(index);scenario.mkdir()
                instance=self.cleanup_fixture(scenario,None,'7'*32);instance.checkpoint=Mock();calls=[]
                def docker(*args,**kwargs):
                    calls.append(args)
                    if args[:2]==('context','inspect'):return json.dumps([{'Endpoints':{'docker':{'Host':endpoint}}}])
                    return ''
                instance.docker=docker
                with patch.dict(os.environ,overrides,clear=True):
                    with self.assertRaises(audit.base.AuditFailure):instance.setup_containers()
                    instance.cleanup()
                self.assertFalse(any(c[0] in ('ps','run','image','inspect','rm') for c in calls),calls)

    def test_fixture_start_pins_cached_image_id_no_pull_loopback_and_secret_environment(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            instance=self.cleanup_fixture(Path(folder),None,'8'*32);instance.checkpoint=Mock();captured=[]
            image_id='sha256:'+'a'*64
            def docker(*args,**kwargs):
                if args[:2]==('context','inspect'):return json.dumps([{'Endpoints':{'docker':{'Host':'npipe:////./pipe/docker_engine'}}}])
                if args[:2]==('image','inspect'):return json.dumps([{'Id':image_id}])
                if args[0]=='run':captured.append((args,kwargs));raise StopBeforeExternalOperation()
                raise AssertionError('Unexpected tool command')
            instance.docker=docker
            with patch.dict(os.environ,{},clear=True),self.assertRaises(StopBeforeExternalOperation):instance.setup_containers()
            args,kwargs=captured[0];self.assertIn('--pull=never',args);self.assertEqual(image_id,args[-1])
            self.assertIn('127.0.0.1::5432',args);self.assertIn('/var/lib/postgresql/data:rw,nosuid,size=256m',args)
            self.assertIn('512m',args);self.assertIn(audit.LABEL+'='+instance.run_id,args)
            self.assertNotIn(kwargs['env']['POSTGRES_PASSWORD'],args)

    def test_failed_check_is_recorded_and_raises_without_manufacturing_acceptance(self):
        instance=audit.Audit.__new__(audit.Audit);instance.report={'status':'failed','checks':[]}
        with self.assertRaises(audit.base.AuditFailure):instance.check('fixture-negative',False)
        self.assertEqual([{'case':'fixture-negative','passed':False}],instance.report['checks'])
        self.assertEqual('failed',instance.report['status'])

    def test_http_500_cannot_count_as_security_rejection(self):
        instance=audit.Audit.__new__(audit.Audit);instance.report={'status':'failed','checks':[]}
        client=Mock();client.request.return_value=(500,{'message':'fixture'})
        with self.assertRaises(audit.base.AuditFailure):instance.http('invalid-proof',client,'GET','/api/auth/me',{401,403})
        self.assertFalse(instance.report['checks'][0]['passed'])

    def test_finalization_rejects_empty_checks_source_drift_and_each_cleanup_failure(self):
        cases=getattr(audit,'REQUIRED_CASES',{'fixture-case'})
        base={'status':'failed','sourceHashesMatchAtEnd':True,'checks':[{'case':name,'passed':True} for name in cases],
              'cleanup':dict.fromkeys(getattr(audit,'REQUIRED_CLEANUP',('processes','containers','credentialsRetired','privateKeysRemoved')),True)}
        candidates=[{**base,'checks':[]},{**base,'sourceHashesMatchAtEnd':False},{**base,'checks':[{'passed':False}]},
                    {**base,'cleanup':{}},{**base,'checks':base['checks'][:-1]},
                    {**base,'checks':base['checks']+[base['checks'][0]]}]
        candidates += [{**base,'cleanup':{**base['cleanup'],name:False}} for name in base['cleanup']]
        candidates += [{**base,'cleanup':{k:v for k,v in base['cleanup'].items() if k!=name}} for name in base['cleanup']]
        for candidate in candidates:
            # Set a candidate state only to verify the real finalizer rejects it; never emit a success report.
            candidate['status']='passed';audit.finalize_status(candidate);self.assertEqual('failed',candidate['status'])

    def test_source_binding_covers_all_current_migrations_helper_and_runner_pom(self):
        hashes=audit.source_hashes();manifest=audit.base.database_contract.source_manifest()
        actual={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in ROOT.glob('services/*/src/main/resources/db/migration/*.sql')}
        self.assertEqual(actual,manifest)
        self.assertIn('services/auth/src/main/resources/db/migration/V11__browser_session_families.sql',manifest)
        for relative in ['scripts/security/SignIsolatedUser.java','scripts/security/GenerateKeys.java',
                         'scripts/security/installation.py','scripts/database/runner/pom.xml',*manifest]:
            self.assertTrue(relative in hashes,relative)
            self.assertEqual(hashlib.sha256((ROOT/relative).read_bytes()).hexdigest(),hashes[relative])

    def test_archived_old_it_properties_do_not_publish_expired_fixture_credentials(self):
        with tempfile.TemporaryDirectory(prefix='identity-archive-',dir=ROOT/'output') as directory:
            source=Path(directory)/'TEST-fixture.xml'
            source.write_text('<testsuite tests="1"><properties><property name="AETHERIS_TEST_PASSWORD" value="expired-fixture-password"/>'
                '<property name="aetheris.datasource.master-key" value="expired-fixture-key"/><property name="java.version" value="21"/>'
                '</properties><testcase name="fixture"/></testsuite>',encoding='utf-8')
            archived=audit.archive_evidence(source,[])
            self.assertNotIn('expired-fixture-password',archived);self.assertNotIn('expired-fixture-key',archived)
            self.assertIn('java.version',archived);self.assertIn('value="21"',archived);self.assertIn('testcase',archived)

    def test_fresh_build_removes_stale_classes_before_packaging_without_launching_maven(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            instance=self.cleanup_fixture(Path(folder),None,'5'*32);instance.checkpoint=Mock();captured=[]
            def stop(args,**kwargs):captured.append(args);raise StopBeforeExternalOperation()
            instance.command=stop
            # Do not copy or mutate another task's real target evidence in this tool-only test.
            with patch.object(Path,'glob',return_value=[]),patch.object(audit.shutil,'which',return_value='fixture-mvn.cmd'):
                with self.assertRaises(StopBeforeExternalOperation):instance.build()
            self.assertEqual(1,len(captured));self.assertIn('-o',captured[0]);self.assertIn('clean',captured[0]);self.assertIn('package',captured[0])
            self.assertLess(captured[0].index('clean'),captured[0].index('package'))

    def test_app_launch_is_owned_direct_java_loopback_runtime_role_and_bounded_heap(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            instance=self.cleanup_fixture(Path(folder),Path(folder)/'unused-keys','4'*32)
            instance.java='reviewed-direct-jdk21';instance.report['ports']={'postgres':49100,'redis':49101}
            instance.base={'credentials':{'auth':{'runtime':'runtime-only-fixture','migration':'never-in-app-fixture'}}}
            instance.key_manifest={'services':{'auth':{'workloadKid':'auth-fixture','workloadPrivateFile':'auth.workload.pem'}}}
            instance.checkpoint=Mock();captured=[];ports=iter(range(49110,49114))
            def reserve():return Mock(),next(ports)
            def stop(args,**kwargs):captured.append((args,kwargs));raise StopBeforeExternalOperation()
            with patch.object(audit.base,'reserve_port',side_effect=reserve),patch.object(audit.subprocess,'Popen',side_effect=stop):
                with self.assertRaises(StopBeforeExternalOperation):instance.start_apps({'auth':Path(folder)/'auth.jar'},'redis-fixture')
            args,kwargs=captured[0];env=kwargs['env']
            self.assertEqual(instance.java,args[0]);self.assertIn('-Xmx256m',args)
            self.assertTrue(set(audit.FLAGS).issubset(args));self.assertEqual('127.0.0.1',env['SERVER_ADDRESS'])
            self.assertEqual('127.0.0.1',env['DB_HOST']);self.assertEqual('aetheris_auth',env['DB_USER'])
            self.assertEqual('runtime-only-fixture',env['DB_PASSWORD']);self.assertNotIn('never-in-app-fixture',env.values())
            self.assertEqual('false',env['SPRING_FLYWAY_ENABLED']);self.assertNotIn('REDIS_PASSWORD',env)
            self.assertEqual(instance.folder,kwargs['cwd']);self.assertEqual(getattr(audit.subprocess,'CREATE_NO_WINDOW',0),kwargs['creationflags'])

    def test_durable_database_predicate_rejects_empty_missing_and_zero_counts(self):
        tree=ast.parse((ROOT/'scripts/audit-identity-chain.py').read_text(encoding='utf-8'))
        check=next(node for node in ast.walk(tree) if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute)
                   and node.func.attr=='check' and node.args and isinstance(node.args[0],ast.Constant)
                   and node.args[0].value=='durable-real-caller-nonces-and-both-audit-streams')
        predicate=compile(ast.Expression(check.args[1]),'<actual-durable-check>','eval')
        names=['authGatewayNonce','authSystemNonce','systemGatewayNonce','systemAuthNonce','auditRows','permissionEvents']
        good=dict.fromkeys(names,1)
        for counts in ({},{names[0]:1},{**good,names[1]:0}):
            self.assertFalse(eval(predicate,audit.__dict__,{'counts':counts,'expected':set(names)}),counts)

    def test_private_cleanup_removes_only_exact_owned_known_files(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            root=Path(folder);private=root/'private';private.mkdir();run='1'*32;owned=private/('audit-identity-'+run);owned.mkdir()
            keydir=owned/'keys';keydir.mkdir();(keydir/'gateway.user.pem').write_text('synthetic fixture',encoding='utf-8')
            instance=self.cleanup_fixture(root,keydir,run)
            with patch.object(audit.keys_module,'PRIVATE',private):
                instance.cleanup()
            self.assertFalse(owned.exists());self.assertTrue(instance.report['cleanup']['privateKeysRemoved'])
            self.assertEqual('failed',instance.report['status'])

    def test_private_cleanup_preserves_unknown_files_and_outside_directory(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            run='2'*32
            for index,outside in enumerate((False,True)):
                root=Path(folder)/str(index);root.mkdir();private=root/'private';private.mkdir()
                directory=(root if outside else private)/('audit-identity-'+run)
                directory.mkdir();keydir=directory/'keys';keydir.mkdir();unknown=keydir/'operator-file.txt';unknown.write_text('preserved',encoding='utf-8')
                instance=self.cleanup_fixture(root,keydir,run)
                with patch.object(audit.keys_module,'PRIVATE',private):instance.cleanup()
                self.assertEqual('preserved',unknown.read_text(encoding='utf-8'))
                self.assertFalse(instance.report['cleanup']['privateKeysRemoved']);self.assertEqual('failed',instance.report['status'])

    def test_existing_same_run_private_directory_is_never_claimed_or_deleted(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            root=Path(folder);private=root/'private';private.mkdir();run='6'*32
            existing=private/('audit-identity-'+run);existing.mkdir();keydir=existing/'keys';keydir.mkdir()
            sentinel=keydir/'gateway.user.pem';sentinel.write_text('operator synthetic fixture',encoding='utf-8')
            instance=self.cleanup_fixture(root,None,run);instance.java='reviewed-java'
            with patch.object(audit.keys_module,'PRIVATE',private):
                with self.assertRaises((FileExistsError,audit.base.AuditFailure,ValueError)):instance.generate_keys()
                instance.command.assert_not_called();self.assertIsNone(instance.private_root);instance.cleanup()
            self.assertEqual('operator synthetic fixture',sentinel.read_text(encoding='utf-8'))

    def test_cleanup_discovers_owned_container_even_if_start_returned_no_id(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            instance=self.cleanup_fixture(Path(folder),None,'3'*32);instance.processes=[];instance.reservations=[];instance.base=None;instance.containers=[]
            instance.local_docker_verified=True
            calls=[]
            def docker(*args,**kwargs):
                calls.append(args)
                if args[0]=='ps':return 'owned-fixture-container\n' if not any(c[0]=='rm' for c in calls) else ''
                if args[0]=='inspect':return json.dumps([{'Config':{'Labels':{audit.LABEL:instance.run_id}},'Mounts':[]}])
                return ''
            instance.docker=docker;instance.cleanup()
            self.assertTrue(any(c[0]=='ps' and any('label=' in str(x) for x in c) for c in calls),'Missing run-label discovery')
            self.assertTrue(any(c[0]=='rm' and c[-1]=='owned-fixture-container' for c in calls),'Owned unknown-ID container was not removed')
            self.assertEqual('failed',instance.report['status'])

    def test_owned_command_timeout_kills_only_the_created_process_tree(self):
        instance=audit.Audit.__new__(audit.Audit);instance.java='reviewed-java'
        process=Mock();process.pid=123456
        process.communicate.side_effect=[audit.subprocess.TimeoutExpired(['fixture'],1),('','')]
        with patch.object(audit.subprocess,'Popen',return_value=process),patch.object(audit.subprocess,'run') as run,patch.object(audit.os,'killpg',create=True) as killpg:
            with self.assertRaises(audit.base.AuditFailure):instance.command(['reviewed-java','fixture'],timeout=1)
            if os.name=='nt':
                args=run.call_args.args[0]
                self.assertEqual(['taskkill.exe','/PID','123456','/T','/F'],args)
            else:self.assertEqual(123456,killpg.call_args.args[0])

    def test_migration_prepare_uses_the_same_owned_command_and_clean_environment(self):
        instance=audit.Audit.__new__(audit.Audit);instance.command=Mock(side_effect=StopBeforeExternalOperation())
        with patch.object(audit.shutil,'which',return_value='fixture-mvn.cmd'),patch.dict(os.environ,{'JAVA_TOOL_OPTIONS':'blocked'},clear=True):
            with self.assertRaises(StopBeforeExternalOperation):instance.prepare_database()
        args=instance.command.call_args.args[0];options=instance.command.call_args.kwargs
        self.assertIn('-o',args);self.assertIn('clean',args);self.assertIn('compile',args)
        self.assertNotIn('JAVA_TOOL_OPTIONS',options['env']);self.assertEqual(180,options['timeout'])

    def test_migration_runner_uses_fixed_java_stdin_credentials_and_current_inventory_binding(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            runner=Path(folder)/'runner';(runner/'target').mkdir(parents=True)
            (runner/'target/classpath.txt').write_text('synthetic-dependency.jar',encoding='utf-8')
            instance=audit.Audit.__new__(audit.Audit);instance.java='reviewed-direct-jdk'
            instance.base={'admin':{'url':'jdbc:postgresql://127.0.0.1:49100/fixture','user':'fixture','password':'synthetic-private'}}
            instance.command=Mock(side_effect=StopBeforeExternalOperation())
            with patch.object(audit.base.database,'RUNNER',runner),patch.dict(os.environ,{'JAVA_TOOL_OPTIONS':'blocked'},clear=True):
                with self.assertRaises(StopBeforeExternalOperation):instance.runner({**instance.base,'command':'inventory'})
            args=instance.command.call_args.args[0];options=instance.command.call_args.kwargs
            self.assertEqual(instance.java,args[0]);self.assertEqual('com.aetheris.database.DatabaseRunner',args[-1])
            self.assertNotIn('synthetic-private',args);self.assertNotIn('JAVA_TOOL_OPTIONS',options['env'])
            request=json.loads(options['stdin']);self.assertEqual(instance.base['admin'],request['admin'])
            self.assertEqual(audit.base.database.directories(),request['directories'])
            self.assertEqual(audit.base.database_contract.source_manifest(),request['sourceHashes'])

    def test_runner_rejects_foreign_fixture_unknown_command_and_ambiguous_error_output(self):
        with tempfile.TemporaryDirectory(prefix='identity-audit-test-',dir=ROOT/'output') as folder:
            runner=Path(folder)/'runner';(runner/'target').mkdir(parents=True)
            (runner/'target/classpath.txt').write_text('synthetic-dependency.jar',encoding='utf-8')
            instance=audit.Audit.__new__(audit.Audit);instance.java='reviewed-direct-jdk'
            instance.base={'admin':{'url':'fixture-owned','password':'synthetic'}};instance.command=Mock()
            with self.assertRaises(audit.base.AuditFailure):instance.runner({'admin':{'url':'foreign'},'command':'inventory'})
            with self.assertRaises(audit.base.AuditFailure):instance.runner({**instance.base,'command':'apply-plan'})
            instance.command.assert_not_called()
            with patch.object(audit.base.database,'RUNNER',runner):
                for output in ('','AETHERIS_RESULT=[]','AETHERIS_RESULT={}\nAETHERIS_RESULT={}',
                               'AETHERIS_ERROR={}\nAETHERIS_RESULT={}','AETHERIS_RESULT=malformed'):
                    instance.command.return_value=output
                    with self.assertRaises((audit.base.AuditFailure,json.JSONDecodeError)):instance.runner({**instance.base,'command':'retire'})

    @staticmethod
    def cleanup_fixture(root,private,run):
        instance=audit.Audit.__new__(audit.Audit);instance.private_keys=private;instance.run_id=run
        instance.private_root=private.parent if private is not None else None
        instance.folder=root/'run';instance.folder.mkdir(exist_ok=True);instance.output=root/'report.json'
        instance.report={'status':'failed','checks':[],'cleanup':{}}
        instance.processes=[];instance.reservations=[];instance.base=None;instance.containers=[];instance.secrets=[]
        instance.local_docker_verified=False
        instance.command=Mock(return_value='');instance.docker=Mock(return_value='')
        return instance


if __name__=='__main__':unittest.main()

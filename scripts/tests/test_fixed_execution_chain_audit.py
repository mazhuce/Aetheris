"""Tool-only guards: no Java, Docker, HTTP or existing database operations."""
import base64,hashlib,importlib.util,json,os,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('fixed_chain_under_test',ROOT/'scripts/audit-fixed-execution-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class StopBeforeExternalOperation(Exception):pass

class FixedExecutionChainSafetyTest(unittest.TestCase):
    def setUp(self):
        for name in ('Popen','run'):
            blocker=patch.object(audit.subprocess,name,side_effect=AssertionError('External operations forbidden'))
            blocker.start();self.addCleanup(blocker.stop)
    def test_output_is_fresh_and_owned(self):
        with tempfile.TemporaryDirectory(prefix='fixed-chain-tool-',dir=ROOT/'output') as directory:
            folder=Path(directory);allowed=folder/'allowed';allowed.mkdir()
            with patch.object(audit.identity,'OUTPUT',allowed):
                self.assertEqual((allowed/'new.json').resolve(),audit.validate_output(allowed/'new.json'))
                for path in (folder/'outside.json',allowed/'bad.txt',allowed):
                    with self.assertRaises(ValueError):audit.validate_output(path)
                old=allowed/'old.json';old.write_text('preserve',encoding='utf-8')
                with self.assertRaises(ValueError):audit.validate_output(old)
                self.assertEqual('preserve',old.read_text(encoding='utf-8'))
    def test_missing_duplicate_failed_and_dirty_evidence_is_rejected(self):
        baseline={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'case':name,'passed':True} for name in audit.REQUIRED_CASES],
                  'cleanup':dict.fromkeys(audit.REQUIRED_CLEANUP,True)}
        candidates=[{**baseline,'checks':[]},{**baseline,'checks':baseline['checks'][:-1]},
                    {**baseline,'checks':baseline['checks']+[baseline['checks'][0]]},{**baseline,'sourceHashesMatchAtEnd':False},
                    {**baseline,'checks':[{**item,'passed':False} for item in baseline['checks']]}]
        candidates += [{**baseline,'cleanup':{**baseline['cleanup'],key:False}} for key in audit.REQUIRED_CLEANUP]
        candidates += [{**baseline,'cleanup':{key:True for key in audit.REQUIRED_CLEANUP if key!=missing}} for missing in audit.REQUIRED_CLEANUP]
        for candidate in candidates:audit.finalize_status(candidate);self.assertEqual('failed',candidate['status'])
    def test_all_six_applications_transitive_helpers_and_114_sources_are_bound(self):
        sources=audit.source_hashes();manifest=audit.base.database_contract.source_manifest();self.assertEqual(116,len(manifest))
        required=['scripts/audit-fixed-execution-chain.py','scripts/audit-identity-chain.py','scripts/audit-api-key-chain.py','scripts/security/GenerateKeys.java',
                  'scripts/database/runner/pom.xml',*[module+'/pom.xml' for module in audit.MODULES.values()],*manifest]
        for name in required:
            self.assertIn(name,sources);self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),sources[name])
    def test_environment_is_sanitized_and_no_external_origin_is_accepted(self):
        unsafe={'DB_USER':'old','SPRING_CONFIG_IMPORT':'external','AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE':'old','JWT_SECRET':'old','JAVA_TOOL_OPTIONS':'old','MAVEN_ARGS':'old'}
        with patch.dict(os.environ,{**unsafe,'PATH':'reviewed'},clear=True):self.assertFalse(set(unsafe)&set(audit.clean_environment()))
        for origin in ('http://example.com:80','http://127.0.0.2:12345','http://user:pass@127.0.0.1:12345','https://127.0.0.1:12345'):
            with self.assertRaises(ValueError):audit.base.HttpClient(origin)
    def test_business_fixture_never_uses_existing_roles_or_grants_global_table_write(self):
        instance=audit.Audit.__new__(audit.Audit);instance.secret=Mock(return_value='fixture-only-secret');instance.sql=Mock()
        with patch.object(audit.identity.Audit,'setup_database') as parent:instance.setup_database()
        parent.assert_called_once();sql=instance.sql.call_args.args[0]
        self.assertIn('CREATE SCHEMA business',sql);self.assertIn('CREATE ROLE audit_source_reader',sql)
        self.assertIn('GRANT INSERT,DELETE ON business.copied_orders TO audit_target_writer',sql)
        self.assertNotIn('GRANT ALL',sql);self.assertNotIn('DROP ',sql);self.assertNotIn('UPDATE auth.',sql)
    def test_500_never_counts_as_an_authorization_rejection(self):
        instance=audit.Audit.__new__(audit.Audit);instance.report={'checks':[]};client=Mock();client.request.return_value=(500,{'message':'fixture'})
        with self.assertRaises(audit.base.AuditFailure):instance.http('fixture-rejection',client,'POST','/internal/v2/executions/status',{401,403},{})
        self.assertFalse(instance.report['checks'][0]['passed'])
    def test_application_launches_only_receive_their_own_workload_and_required_secrets(self):
        with tempfile.TemporaryDirectory(prefix='fixed-chain-tool-',dir=ROOT/'output') as directory:
            instance=audit.Audit.__new__(audit.Audit);instance.checkpoint=Mock();instance.folder=Path(directory);instance.run_id='1'*32
            instance.report={'ports':{'postgres':12345,'redis':12346}};instance.reservations=[];instance.processes=[];instance.secrets=[]
            instance.private_keys=Path(directory)/'keys';instance.java='fixture-java';instance.secret=Mock(return_value='fixture-only-secret')
            instance.key_manifest={'services':{name:{'workloadKid':name+'-kid','workloadPrivateFile':name+'.pem','userKid':'gateway-user','userPrivateFile':'gateway-user.pem'} for name in audit.MODULES}}
            instance.base={'credentials':{name:{'runtime':name+'-fixture-password'} for name in audit.MODULES if name!='gateway'}}
            slots=iter(range(20001,20008));launches=[]
            def fake_start(args,**kwargs):
                pid=8000+len(launches);kwargs['stdout'].write(('Started application with PID '+str(pid)+'\n').encode());kwargs['stdout'].flush()
                process=Mock();process.pid=pid;process.poll.return_value=None;launches.append((args,kwargs));return process
            client=Mock();client.request.return_value=(200,{'status':'UP'})
            try:
                with patch.object(audit.base,'reserve_port',side_effect=lambda:(Mock(),next(slots))),patch.object(audit.base,'HttpClient',return_value=client),patch.object(audit.subprocess,'Popen',side_effect=fake_start):
                    result=instance.start_apps({name:Path(directory)/(name+'.jar') for name in audit.MODULES},'fixture-redis')
                self.assertEqual(set(audit.MODULES)|{'unused'},set(result));self.assertEqual(6,len(launches))
                for (name,_),(args,options) in zip(audit.MODULES.items(),launches):
                    env=options['env'];self.assertEqual('127.0.0.1',env['SERVER_ADDRESS']);self.assertEqual(name+'-kid',env['AETHERIS_SECURITY_WORKLOAD_KID'])
                    self.assertIn('-Djdk.httpclient.disableRetryConnect=true',args);self.assertIn('-Djdk.httpclient.redirects.retrylimit=1',args)
                    self.assertNotIn(env['AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE'],args)
                    self.assertEqual(name=='gateway','AETHERIS_SECURITY_USER_PRIVATE_FILE' in env)
                    self.assertEqual(name=='metadata','AETHERIS_DATASOURCE_MASTER_KEY' in env)
                    if name=='metadata':self.assertEqual(32,len(base64.b64decode(env['AETHERIS_DATASOURCE_MASTER_KEY'],validate=True)))
                    self.assertEqual(name in ('auth','gateway'),'JWT_SECRET' in env)
                    self.assertEqual(name=='gateway','REDIS_PASSWORD' in env)
                    if name!='gateway':self.assertEqual(name+'-fixture-password',env['DB_PASSWORD'])
            finally:
                for _,_,log,_ in instance.processes:log.close()

if __name__=='__main__':unittest.main()

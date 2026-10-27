import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('api_chain',ROOT/'scripts/audit-api-key-chain.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


class ApiKeyAuditContractTests(unittest.TestCase):
    def test_server_reservation_is_loopback_and_not_an_os_chosen_ephemeral_port(self):
        busy=Mock();busy.bind.side_effect=OSError('occupied fixture port')
        available=Mock();available.getsockname.return_value=('127.0.0.1',20007)
        with patch.object(audit.socket,'socket',side_effect=[busy,available]),patch.object(audit.secrets,'randbelow',return_value=7):
            sock,port=audit.reserve_port()
        self.assertIs(available,sock);self.assertEqual(20007,port)
        busy.close.assert_called_once();available.bind.assert_called_once_with(('127.0.0.1',20007))

    def test_occupied_server_ports_are_bounded_and_every_reservation_is_closed(self):
        sockets=[]
        def occupied():
            sock=Mock();sock.bind.side_effect=OSError('occupied fixture port');sockets.append(sock);return sock
        with patch.object(audit.socket,'socket',side_effect=occupied),self.assertRaises(audit.AuditFailure):audit.reserve_port()
        self.assertEqual(100,len(sockets))
        for sock in sockets:sock.close.assert_called_once()

    def test_pure_key_never_contains_jwt_or_cookie(self):
        headers=audit.pure_key_headers('ak_unit_fixture_secret')
        self.assertEqual('ak_unit_fixture_secret',headers['X-API-Key'])
        self.assertFalse(any(k.lower() in ('authorization','cookie') for k in headers))
        for extra in ({'Cookie':'a=b'},{'Authorization':'Bearer fixture'}):
            with self.assertRaises(ValueError):audit.pure_key_headers('fixture',extra)

    def test_server_error_cannot_count_as_authorization_rejection(self):
        audit.require_status(403,{401,403},'forbidden-fixture')
        with self.assertRaises(audit.AuditFailure):audit.require_status(500,{401,403},'forbidden-fixture')

    def test_true_data_rows_required_not_just_http_200(self):
        good={'rowCount':3,'columns':['id','name','amount'],'rows':[[1,'A',10],[2,'B','20.00'],[3,'C',30.0]]}
        audit.require_business_rows(good)
        for bad in ({'error':'query failed'}, {'rowCount':3,'columns':['id','name','amount'],'rows':[[1,'A',0],[2,'B',20],[3,'C',30]]}):
            with self.assertRaises(audit.AuditFailure):audit.require_business_rows(bad)

    def test_logs_redact_known_secrets_keys_and_jwts(self):
        result=audit.redact('password=private-fixture ak_123456789012_abcdefghijklmnopqrstuvwxyz eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.signature',['private-fixture'])
        self.assertNotIn('private-fixture',result)
        self.assertNotIn('ak_123456789012',result)
        self.assertNotIn('eyJhbGci',result)

    def test_outside_output_and_non_loopback_rejected(self):
        with self.assertRaises(ValueError):audit.validate_output(ROOT.parent/'escape.json')
        with self.assertRaises(ValueError):audit.HttpClient('http://example.com:8080')

    def test_failed_cleanup_makes_final_report_failed(self):
        for component in audit.REQUIRED_CLEANUP:
            cleanup=dict.fromkeys(audit.REQUIRED_CLEANUP,True);cleanup[component]=False
            report={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'passed':True}],'cleanup':cleanup}
            audit.finalize_status(report)
            self.assertEqual('failed',report['status'])

    def test_missing_cleanup_evidence_makes_final_report_failed(self):
        for missing in audit.REQUIRED_CLEANUP:
            cleanup={key:True for key in audit.REQUIRED_CLEANUP if key!=missing}
            report={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'passed':True}],'cleanup':cleanup}
            audit.finalize_status(report)
            self.assertEqual('failed',report['status'])

    def test_failed_http_check_makes_final_report_failed_with_successful_cleanup(self):
        report={'status':'passed','sourceHashesMatchAtEnd':True,'checks':[{'passed':False}],'cleanup':{'processes':True,'containers':True,'credentialsRetired':True}}
        audit.finalize_status(report)
        self.assertEqual('failed',report['status'])

    def test_missing_source_consistency_evidence_cannot_pass(self):
        report={'status':'passed','checks':[{'passed':True}],'cleanup':{'processes':True,'containers':True,'credentialsRetired':True}}
        audit.finalize_status(report)
        self.assertEqual('failed',report['status'])

    def test_launches_receive_v2_registry_and_only_their_own_private_key(self):
        with tempfile.TemporaryDirectory(prefix='api-key-chain-tool-',dir=ROOT/'output') as directory:
            instance=audit.Audit.__new__(audit.Audit);instance.checkpoint=Mock();instance.folder=Path(directory);instance.run_id='1'*32
            instance.report={'ports':{'postgres':12345,'redis':12346}};instance.reservations=[];instance.processes=[];instance.secrets=[]
            instance.private_keys=Path(directory)/'keys';instance.java='fixture-java';instance.secret=Mock(return_value='fixture-only-secret')
            instance.key_manifest={'services':{name:{'workloadKid':name+'-kid','workloadPrivateFile':name+'.workload.pem',
                'userKid':'gateway-user','userPrivateFile':'gateway.user.pem'} for name in audit.MODULES}}
            instance.base={'credentials':{name:{'runtime':name+'-fixture-password'} for name in audit.MODULES if name!='gateway'}}
            ports=iter(range(21001,21006));launches=[]
            def fake_start(args,**kwargs):
                pid=9000+len(launches);kwargs['stdout'].write(('Started application with PID '+str(pid)+'\n').encode());kwargs['stdout'].flush()
                process=Mock();process.pid=pid;process.poll.return_value=None;launches.append((args,kwargs));return process
            client=Mock();client.request.return_value=(200,{'status':'UP'})
            try:
                with patch.object(audit,'reserve_port',side_effect=lambda:(Mock(),next(ports))),patch.object(audit,'HttpClient',return_value=client),patch.object(audit.subprocess,'Popen',side_effect=fake_start):
                    result=instance.start_apps({name:Path(directory)/(name+'.jar') for name in audit.MODULES},'fixture-redis')
                self.assertEqual(set(audit.MODULES)|{'unused'},set(result));self.assertEqual(4,len(launches))
                for name,(args,options) in zip(('auth','metadata','serving','gateway'),launches):
                    env=options['env'];self.assertEqual(name+'-kid',env['AETHERIS_SECURITY_WORKLOAD_KID'])
                    self.assertEqual(str(instance.private_keys/'public-registry.json'),env['AETHERIS_SECURITY_REGISTRY_FILE'])
                    self.assertEqual(str(instance.private_keys/(name+'.workload.pem')),env['AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE'])
                    self.assertNotIn(env['AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE'],args)
                    self.assertTrue(all(flag in args for flag in audit.FLAGS))
                    self.assertEqual(name=='gateway','AETHERIS_SECURITY_USER_PRIVATE_FILE' in env)
                    if name!='gateway':self.assertEqual(name+'-fixture-password',env['DB_PASSWORD'])
            finally:
                for _,_,log,_ in instance.processes:log.close()


if __name__=='__main__':unittest.main()

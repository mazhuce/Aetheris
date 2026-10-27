import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/database'))
import contract
import database_roles as roles
import deploy_gate as gate


class DatabaseDeployGateTests(unittest.TestCase):
    def setUp(self):
        private=ROOT/'configs/database/private';private.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='gate-fixture-',dir=private)
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)
        self.identity={'systemIdentifier':'111','databaseOid':'123','database':'gate_fixture'}
        password='a'*48;admin=self.path/'admin.password';admin.write_text(password,encoding='utf-8')
        self.document={'admin':{'url':'jdbc:postgresql://127.0.0.1:5433/gate_fixture','user':'fixture_admin','passwordFile':str(admin)},'credentials':{}}
        self.model=json.loads((ROOT/'docker-compose.database-roles.yml').read_text(encoding='utf-8').split('\n',1)[1])
        self.model['secrets']={}
        for service,schema in contract.services().items():
            secret=self.path/(schema+'.password');secret.write_text(password,encoding='utf-8')
            self.document['credentials'][schema]={'runtimeFile':str(secret)}
            self.model['secrets']['db_'+schema+'_runtime']={'file':str(secret)}
            item=self.model['services'][gate.release.COMPOSE_SERVICES[service]]
            item['environment'].update(DB_HOST='postgres',DB_PORT='5432',DB_NAME='gate_fixture')
        self.model['services']['postgres']={'environment':{'POSTGRES_DB':'gate_fixture'}}
        images={name:f'ghcr.io/mazhuce/aetheris-{name}@sha256:'+'b'*64 for name in gate.release.SERVICES}
        for name,image in images.items():self.model['services'].setdefault(gate.release.COMPOSE_SERVICES[name],{})['image']=image
        self.manifest={'schemaVersion':2,'repository':'mazhuce/Aetheris','commit':'a'*40,
                       'ci':{'runId':12,'attempt':1,'requiredJobs':sorted(gate.release.ci.REQUIRED_JOBS)},
                       'build':{'runId':14,'attempt':1,'ref':'refs/heads/main'},'images':images,'databaseSources':contract.source_manifest()}
        bindings={'databaseUrl':self.document['admin']['url'],'sourceHashes':contract.source_manifest(),'serviceSchemas':contract.services(),
                  'runtimeRoles':{s:contract.role(s) for s in contract.services().values()},
                  'runtimeSecretFingerprints':{s:hashlib.sha256(password.encode()).hexdigest() for s in contract.services().values()}}
        self.receipt={'schemaVersion':1,'purpose':'aetheris-database-runtime-readiness','status':'passed','runId':'fixture',
                      'verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'bindings':bindings,'bindingsSha256':contract.digest(bindings),
                      'inventorySha256':'c'*64}
        self.manifest_path=self.path/'manifest.json';self.config_path=self.path/'operator.json';self.receipt_path=self.path/'receipt.json';self.record=self.path/'binding.json'
        self.flush()

    def flush(self):
        for path,value in ((self.manifest_path,self.manifest),(self.config_path,self.document),(self.receipt_path,self.receipt)):
            path.write_text(json.dumps(value),encoding='utf-8')

    def invoke(self,identity=None):
        with patch.object(roles,'prepare'),patch.object(roles,'readiness_receipt',return_value=self.receipt),\
                patch.object(roles,'runner',return_value=self.identity),patch.object(gate,'compose_identity',return_value=identity or self.identity):
            return gate.verify(self.manifest_path,self.config_path,self.receipt_path,self.model,self.record)

    def test_valid_binding_records_images_sources_and_live_identity_without_secrets(self):
        record=self.invoke()
        self.assertEqual(record['status'],'verified-not-deployed')
        self.assertEqual(record['images'],self.manifest['images'])
        self.assertEqual(record['databaseIdentity'],self.identity)
        self.assertEqual(record['releaseManifestSha256'],hashlib.sha256(self.manifest_path.read_bytes()).hexdigest())
        raw=self.record.read_text(encoding='utf-8')
        self.assertNotIn('a'*48,raw);self.assertNotIn('runtimeSecretFingerprints',raw)

    def test_missing_legacy_or_changed_signed_sources_stop_before_live_access(self):
        for schema,sources in ((1,None),(2,{}),(2,{**self.manifest['databaseSources'],'services/auth/src/main/resources/db/migration/V999__fake.sql':'1'*64})):
            body=dict(self.manifest,schemaVersion=schema)
            if sources is not None:body['databaseSources']=sources
            self.manifest_path.write_text(json.dumps(body),encoding='utf-8')
            with patch.object(roles,'prepare') as live,patch.object(roles,'runner',side_effect=AssertionError('Unexpected database access')),self.assertRaises(ValueError):
                gate.verify(self.manifest_path,self.config_path,self.receipt_path,self.model,self.record)
            live.assert_not_called();self.assertFalse(self.record.exists())

    def test_expired_wrong_secret_and_different_actual_database_are_rejected(self):
        self.receipt['verifiedAtUtc']=(datetime.now(timezone.utc)-timedelta(days=2)).isoformat();self.flush()
        with self.assertRaises(ValueError):self.invoke()
        self.receipt['verifiedAtUtc']=datetime.now(timezone.utc).isoformat();self.flush()
        changed=Path(self.document['credentials']['auth']['runtimeFile']);changed.write_text('z'*48,encoding='utf-8')
        with self.assertRaises(ValueError):self.invoke()
        changed.write_text('a'*48,encoding='utf-8')
        with self.assertRaises(ValueError):self.invoke({**self.identity,'systemIdentifier':'another-cluster'})
        self.assertFalse(self.record.exists())

    def test_wrong_compose_image_host_secret_and_privileged_fallback_are_rejected(self):
        original=copy.deepcopy(self.model)
        mutations=(('image','some:tag'),('DB_HOST','external-unverified'),('DB_USER','aetheris'),
                   ('SPRING_FLYWAY_ENABLED','true'),('SPRING_DATASOURCE_URL','jdbc:postgresql://other/aetheris'),
                   ('SPRING_DATASOURCE_HIKARI_JDBC_URL','jdbc:postgresql://other/aetheris'),
                   ('SPRING_DATASOURCE_HIKARI_USERNAME','admin'),('SPRING_DATASOURCE_HIKARI_PASSWORD','unverified'),
                   ('SPRING_CONFIG_LOCATION','file:/unreviewed/application.yml'),
                   ('SPRING_CONFIG_ADDITIONAL_LOCATION','file:/unreviewed/application.yml'),
                   ('SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE','1'),
                   ('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE','4'),
                   ('JDK_JAVA_OPTIONS','-DDB_HOST=unverified'))
        for key,value in mutations:
            self.model=copy.deepcopy(original);item=self.model['services']['auth-service']
            if key=='image':item[key]=value
            else:item['environment'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.invoke()
        self.model=copy.deepcopy(original)
        self.model['secrets']['db_auth_runtime']['file']=self.document['credentials']['metadata']['runtimeFile']
        with self.assertRaises(ValueError):self.invoke()
        self.assertFalse(self.record.exists())

    def test_receipt_swap_during_live_check_is_not_recorded(self):
        def swap():
            self.receipt_path.write_text('{}',encoding='utf-8');return self.identity
        with patch.object(roles,'prepare'),patch.object(roles,'readiness_receipt',return_value=self.receipt),\
                patch.object(roles,'runner',return_value=self.identity),patch.object(gate,'compose_identity',side_effect=swap),self.assertRaises(ValueError):
            gate.verify(self.manifest_path,self.config_path,self.receipt_path,self.model,self.record)
        self.assertFalse(self.record.exists())

    def test_manifest_swap_during_live_check_is_not_bound_to_previous_images(self):
        def swap():
            body=copy.deepcopy(self.manifest);body['images']['auth']=body['images']['auth'].replace('b'*64,'d'*64)
            self.manifest_path.write_text(json.dumps(body),encoding='utf-8');return self.identity
        with patch.object(roles,'prepare'),patch.object(roles,'readiness_receipt',return_value=self.receipt),\
                patch.object(roles,'runner',return_value=self.identity),patch.object(gate,'compose_identity',side_effect=swap),self.assertRaises(ValueError):
            gate.verify(self.manifest_path,self.config_path,self.receipt_path,self.model,self.record)
        self.assertFalse(self.record.exists())


if __name__=='__main__':unittest.main()

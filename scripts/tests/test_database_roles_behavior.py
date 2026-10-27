import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import os
import re
import shutil
import unittest
from datetime import datetime,timezone,timedelta
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/database'))
import contract
import database_roles as tool
import compose_contract


class DatabaseRoleBehaviorTests(unittest.TestCase):
    def test_acceptance_rejects_remote_or_overridden_engine_before_creating_resources(self):
        with patch.dict(os.environ,{'DOCKER_HOST':'tcp://remote.invalid:2375'}),patch.object(tool,'docker') as docker:
            with self.assertRaises(ValueError):tool.acceptance('unused')
            docker.assert_not_called()
        clean={k:v for k,v in os.environ.items() if k not in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','MAVEN_OPTS')}
        checked=subprocess.CompletedProcess([],0,stdout='',stderr='    java.specification.version = 21\n    java.home = '+str(Path(clean.get('JAVA_HOME') or 'C:/Program Files/Java/jdk-21').resolve())+'\n')
        clean['JAVA_HOME']=str(Path(clean.get('JAVA_HOME') or 'C:/Program Files/Java/jdk-21').resolve())
        with patch.dict(os.environ,clean,clear=True),patch.object(tool.subprocess,'run',return_value=checked),patch.object(tool,'docker',return_value=json.dumps([{'Endpoints':{'docker':{'Host':'ssh://other-host'}}}])) as docker,patch.object(tool,'prepare') as prepare:
            with self.assertRaises(ValueError):tool.acceptance('unused')
            docker.assert_called_once_with(['context','inspect']);prepare.assert_not_called()

    def owned_snapshot(self):
        return {'schemas':[{'name':s,'owner':contract.role(s,'_owner')} for s in contract.services().values()],
            'relations':[],'functions':[{'schema':s,'name':name,'arguments':args,'resultType':result,'owner':contract.role(s,'_owner'),'securityDefiner':False}
                for s in ('workflow','ingestion','quality') for name,(args,types,result) in {**contract.FIXED_EXECUTION_HELPERS,**(contract.WORKFLOW_PIPELINE_HELPERS if s=='workflow' else {})}.items()],
            'defaultAcls':[],'sourceValidation':{s:{'validationSuccessful':True,'scriptsMatch':True,'pending':0} for s in contract.services().values()}}

    def test_owned_inventory_accepts_only_exact_helper_contract(self):
        snapshot=self.owned_snapshot()
        helper={'schema':'quality','name':'execution_approval_valid','arguments':'value jsonb, revision bigint, definition_hash text','resultType':'boolean',
            'owner':contract.role('quality','_owner'),'securityDefiner':False}
        snapshot['functions']=[f for f in snapshot['functions'] if not (f['schema']=='quality' and f['name']=='execution_approval_valid')]+[helper];tool.check_owned_inventory(snapshot)
        for key,value in [('schema','metadata'),('arguments','value jsonb, revision integer, definition_hash text'),('name','execution_approval_valid"()'),('securityDefiner',True),('resultType','trigger')]:
            bad=json.loads(json.dumps(snapshot));bad['functions'][-1][key]=value
            if key=='schema':bad['functions'][-1]['owner']=contract.role(value,'_owner')
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):tool.check_owned_inventory(bad)
        missing=json.loads(json.dumps(snapshot));missing['functions'].pop()
        with self.assertRaises(ValueError):tool.check_owned_inventory(missing)

    def test_retirement_does_not_need_runtime_or_migration_secret_files(self):
        document={'admin':{'url':'jdbc:postgresql://127.0.0.1:5433/aetheris','user':'aetheris','passwordFile':'admin.local'},
                  'credentials':{'auth':{'runtimeFile':'missing-runtime.local','migrationFile':'missing-migration.local'}}}
        with patch.object(tool,'read_json',return_value=document),patch.object(tool,'secret_value',return_value='admin-fixture') as secret:
            parsed=tool.config('fixture',command='retire')
            secret.assert_called_once_with('admin.local')
            self.assertEqual({},parsed['credentials']['auth'])

    def test_backup_rejects_wrong_cluster_inventory_invalid_time_and_digest(self):
        (ROOT/'tmp').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as temp:
            archive=Path(temp)/'test.dump';archive.write_bytes(b'PGDMP-unit-test-bytes')
            snapshot={'database':'aetheris','systemIdentifier':'111','databaseOid':'123'}
            now=datetime.now(timezone.utc).isoformat()
            receipt={'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'database':'aetheris',
                'sourceInventorySha256':contract.digest(snapshot),'sourceSystemIdentifier':'111','sourceDatabaseOid':'123',
                'backupCreatedAtUtc':now,'verifiedRestoreAtUtc':now}
            tool.verify_backup(receipt,snapshot)
            for field,value in [('sourceSystemIdentifier','different-cluster'),('sourceDatabaseOid','456'),('sourceInventorySha256','0'*64),
                                ('verifiedRestoreAtUtc','not-a-date'),('verifiedRestoreAtUtc','2026-10-18T00:00:00'),
                                ('verifiedRestoreAtUtc',(datetime.now(timezone.utc)+timedelta(days=1)).isoformat()),
                                ('backupCreatedAtUtc',(datetime.now(timezone.utc)-timedelta(days=2)).isoformat()),('sha256','0'*64)]:
                bad={**receipt,field:value}
                with self.subTest(field=field,value=value),self.assertRaises(ValueError):tool.verify_backup(bad,snapshot)

    def model(self):
        return json.loads((ROOT/'docker-compose.database-roles.yml').read_text(encoding='utf-8').split('\n',1)[1])

    def test_runtime_override_rejects_shared_fallback_flyway_and_admin_mounts(self):
        model=self.model();self.assertTrue(compose_contract.validate(model))
        for key,value in [('DB_USER','aetheris'),('DB_PASSWORD','shared-password'),('SPRING_FLYWAY_ENABLED','true'),('SPRING_DATASOURCE_USERNAME','aetheris_auth_owner')]:
            bad=self.model();bad['services']['auth-service']['environment'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):compose_contract.validate(bad)
        bad=self.model();bad['services']['auth-service']['secrets'].append({'source':'db_admin_password','target':'admin'})
        with self.assertRaises(ValueError):compose_contract.validate(bad)

    @unittest.skipUnless(shutil.which('docker'),'Docker Compose CLI unavailable (no daemon is required)')
    def test_actual_compose_merge_has_no_shared_database_password(self):
        content=''.join((ROOT/name).read_text(encoding='utf-8') for name in ['docker-compose.yml','docker-compose.database-roles.yml','docker-compose.database-migrate.yml'])
        env=os.environ.copy()
        for key in re.findall(r'\$\{([A-Z][A-Z0-9_]*)',content):env.pop(key,None)
        for key in re.findall(r'\$\{([A-Z][A-Z0-9_]*):\?',(ROOT/'docker-compose.yml').read_text(encoding='utf-8')):env[key]='nonsecret-render-fixture'
        for schema in contract.services().values():
            env['AETHERIS_'+schema.upper()+'_DB_PASSWORD_FILE']='./configs/database/private/'+schema+'.runtime.password.local'
            env['AETHERIS_'+schema.upper()+'_DB_MIGRATION_PASSWORD_FILE']='./configs/database/private/'+schema+'.migration.password.local'
        env['AETHERIS_DB_ADMIN_PASSWORD_FILE']='./configs/database/private/admin.password.local'
        env['AETHERIS_DB_MIGRATION_IMAGE']='ghcr.io/example/unit-fixture@sha256:'+'a'*64
        (ROOT/'tmp').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as temp:
            empty=Path(temp)/'empty.env';empty.write_text('',encoding='utf-8')
            proc=subprocess.run(['docker','compose','--env-file',str(empty),'-f','docker-compose.yml','-f','docker-compose.database-roles.yml','-f','docker-compose.database-migrate.yml','--profile','db-migrate','config','--format','json'],cwd=ROOT,env=env,capture_output=True,text=True,encoding='utf-8',timeout=30)
            self.assertEqual(0,proc.returncode,'Compose model failed validation (raw environment output withheld)')
            self.assertTrue(compose_contract.validate(json.loads(proc.stdout)))

    def test_migration_job_rejects_floating_images(self):
        model=self.model();model['services']['db-migrate-auth']={'image':'example:latest','secrets':[{'source':'db_admin_password'},{'source':'db_auth_migration'}]}
        with self.assertRaises(ValueError):compose_contract.validate(model)

    def test_receipt_missing_expired_wrong_secret_or_source_cannot_pass(self):
        request={'admin':{'url':'jdbc:postgresql://postgres:5432/aetheris'},'credentials':{s:{'runtime':'a'*48} for s in contract.services().values()}}
        bindings={'databaseUrl':request['admin']['url'],'sourceHashes':contract.source_manifest(),'serviceSchemas':contract.services(),
            'runtimeRoles':{s:contract.role(s) for s in contract.services().values()},
            'runtimeSecretFingerprints':{s:hashlib.sha256(b'a'*48).hexdigest() for s in contract.services().values()}}
        receipt={'schemaVersion':1,'purpose':'aetheris-database-runtime-readiness','status':'passed','verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'bindings':bindings,'bindingsSha256':contract.digest(bindings)}
        self.assertTrue(tool.verify_receipt(receipt,request,live=False))
        for key,value in [('status','failed'),('verifiedAtUtc',(datetime.now(timezone.utc)-timedelta(days=2)).isoformat()),('bindingsSha256','0'*64)]:
            with self.subTest(key=key),self.assertRaises(ValueError):tool.verify_receipt({**receipt,key:value},request,live=False)
        changed=json.loads(json.dumps(request));changed['credentials']['auth']['runtime']='b'*48
        with self.assertRaises(ValueError):tool.verify_receipt(receipt,changed,live=False)
        with patch.object(contract,'source_manifest',return_value={'different.sql':'1'*64}),self.assertRaises(ValueError):tool.verify_receipt(receipt,request,live=False)

    def test_secrets_never_part_of_build_context_or_example_values(self):
        dockerfile=(ROOT/'scripts/database/runner/Dockerfile').read_text(encoding='utf-8')
        self.assertNotIn('COPY configs',dockerfile)
        for file in (ROOT/'configs/database').glob('*.example.json'):
            value=json.loads(file.read_text(encoding='utf-8'))
            self.assertNotIn('password',value['admin'])
            for credentials in value['credentials'].values():
                self.assertNotIn('runtime',credentials);self.assertNotIn('migration',credentials)


if __name__=='__main__':unittest.main()

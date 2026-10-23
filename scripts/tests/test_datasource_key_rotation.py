import base64
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('datasource_key_rotation',ROOT/'scripts/security/datasource_key_rotation.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)


class DataSourceKeyRotationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT/'output',prefix='rekey-unit-');self.directory=Path(self.temp.name)
        self.old=os.urandom(32);self.new=os.urandom(32)
        self.inventory={'database':'test_db','databaseOid':42,'systemIdentifier':'1234567890','schemas':[]}
        archive=self.directory/'unit-fixture.dump';archive.write_bytes(b'PGDMP-unit-fixture-not-a-real-restored-backup')
        now=datetime.now(timezone.utc).isoformat()
        self.receipt={'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'database':'test_db',
                      'verifiedRestoreAtUtc':now,'backupCreatedAtUtc':now,'sourceInventorySha256':tool.contract.digest(self.inventory),
                      'sourceSystemIdentifier':'1234567890','sourceDatabaseOid':42}
        self.rows=[{'id':1,'connectionRevision':3,'password':self.encrypted('读账号密码'),'writePassword':self.encrypted('写账号密码')},
                   {'id':2,'connectionRevision':0,'password':None,'writePassword':''}]
    def tearDown(self):self.temp.cleanup()
    def encrypted(self,secret):
        nonce=os.urandom(12);return 'v1:'+base64.b64encode(nonce+AESGCM(self.old).encrypt(nonce,secret.encode(),None)).decode()
    def snapshot(self,rows=None):return {'schemaVersion':1,'inventorySha256':tool.contract.digest(self.inventory),'rows':self.rows if rows is None else rows}
    def test_reencrypts_utf8_and_keeps_null_empty(self):
        result=tool.rotate_value(self.rows[0]['password'],self.old,self.new);payload=base64.b64decode(result[3:])
        self.assertEqual('读账号密码',AESGCM(self.new).decrypt(payload[:12],payload[12:],None).decode())
        self.assertNotEqual(result,self.rows[0]['password'])
        self.assertIsNone(tool.rotate_value(None,self.old,self.new));self.assertEqual('',tool.rotate_value('',self.old,self.new))
    def test_wrong_key_corruption_plaintext_and_bad_envelopes_are_refused(self):
        for value,key in [(self.rows[0]['password'],os.urandom(32)),('plain-password',self.old),('v1:not base64',self.old),('v1:'+base64.b64encode(b'0'*28).decode(),self.old)]:
            with self.subTest(valueType=value[:3]),self.assertRaises(ValueError):tool.rotate_value(value,key,self.new)
    def test_preparation_is_offline_and_emits_no_plaintexts_or_keys(self):
        with patch.object(subprocess,'run',side_effect=AssertionError('No subprocess allowed')):
            report,apply,rollback=tool.prepare(self.inventory,self.snapshot(),self.receipt,self.old,self.new)
        self.assertEqual('prepared-not-applied',report['status']);self.assertEqual(2,report['rows']);self.assertEqual(1,report['changedRows'])
        public=json.dumps(report,ensure_ascii=False)+apply+rollback
        for value in ['读账号密码','写账号密码',base64.b64encode(self.old).decode(),base64.b64encode(self.new).decode()]:self.assertNotIn(value,public)
        self.assertEqual(hashlib.sha256(apply.encode()).hexdigest(),report['applySha256'])
    def test_same_key_is_not_a_rotation(self):
        with self.assertRaises(ValueError):tool.prepare(self.inventory,self.snapshot(),self.receipt,self.old,self.old)
    def test_database_runner_text_oid_is_accepted_without_changing_backup_binding(self):
        self.inventory['databaseOid']='42';self.receipt['sourceDatabaseOid']='42'
        self.receipt['sourceInventorySha256']=tool.contract.digest(self.inventory)
        report,_,_=tool.prepare(self.inventory,self.snapshot(),self.receipt,self.old,self.new)
        self.assertEqual('42',report['databaseOid'])
    def test_legacy_plaintext_anywhere_refuses_the_entire_plan(self):
        rows=[dict(row) for row in self.rows];rows[-1]['writePassword']='do-not-print-legacy-plaintext'
        with self.assertRaises(ValueError):tool.prepare(self.inventory,self.snapshot(rows),self.receipt,self.old,self.new)
    def test_backup_must_match_current_inventory_and_file(self):
        for change in [{'sourceDatabaseOid':43},{'sourceSystemIdentifier':'other'},{'sha256':'0'*64},{'sourceInventorySha256':'0'*64},
                       {'verifiedRestoreAtUtc':(datetime.now(timezone.utc)-timedelta(days=2)).isoformat()}]:
            with self.subTest(change=next(iter(change))),self.assertRaises(ValueError):tool.prepare(self.inventory,self.snapshot(),{**self.receipt,**change},self.old,self.new)
    def test_duplicate_ids_wrong_types_and_revision_overflow_are_refused(self):
        for rows in [[self.rows[0],self.rows[0]],[{**self.rows[0],'id':True}],[{**self.rows[0],'connectionRevision':2**63-2}],[{**self.rows[0],'unexpected':None}]]:
            with self.subTest(rows=len(rows)),self.assertRaises(ValueError):tool.rows_from(self.snapshot(rows),self.inventory)
    def test_snapshot_from_another_inventory_is_refused(self):
        with self.assertRaises(ValueError):tool.rows_from({**self.snapshot(),'inventorySha256':'0'*64},self.inventory)
    def test_json_duplicate_fields_never_choose_an_owner_or_snapshot_silently(self):
        p=self.directory/'duplicate.json';p.write_text('{"rows":[],"rows":[1]}',encoding='utf-8')
        with self.assertRaises(ValueError):tool.strict_json(p)
    def test_key_files_are_32_byte_base64_and_bounded(self):
        p=self.directory/'key';p.write_text(base64.b64encode(self.old).decode(),encoding='ascii');self.assertEqual(self.old,tool.key_file(p))
        for text in ['plain','A'*257,base64.b64encode(b'short').decode()]:
            p.write_text(text,encoding='ascii')
            with self.assertRaises(ValueError):tool.key_file(p)
    def test_new_output_never_overwrites_or_escapes_private_child(self):
        private=self.directory/'private';private.mkdir();name='master-key-'+'a'*32
        with patch.object(tool,'PRIVATE',private):
            for p in [self.directory/'outside',private,private/'wrong-name']:
                with self.subTest(name=p.name),self.assertRaises(ValueError):tool.write_new(p,{},'apply','rollback')
            result=tool.write_new(private/name,{},'apply','rollback')
            with self.assertRaises(ValueError):tool.write_new(result,{},'replace','replace')
            self.assertEqual('apply',(result/'apply.sql').read_text())
    def test_cli_failure_is_nonzero_and_does_not_disclose_inputs(self):
        p=self.directory/'secret.json';p.write_text('{"private":"do-not-print-cli"}',encoding='utf-8')
        command=[sys.executable,str(ROOT/'scripts/security/datasource_key_rotation.py')]
        for option in ['inventory','snapshot','backup-receipt','old-key-file','new-key-file']:command.extend(['--'+option,str(p)])
        r=subprocess.run(command,capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,r.returncode);self.assertNotIn('do-not-print-cli',r.stdout+r.stderr)


if __name__=='__main__':unittest.main()

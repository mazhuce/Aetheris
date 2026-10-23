#!/usr/bin/env python3
"""Offline preparation only: verify a restored backup and emit reviewable, fenced rekey/rollback SQL.

This program has no database connection or apply command. Stop credential writers, retain both keys,
review the generated private files, and obtain approval before an operator executes either SQL file.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sys
import uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / 'configs/database/private'
sys.path.insert(0, str(ROOT / 'scripts/database'))
import contract
import database_roles


def invalid():
    return ValueError('Invalid rotation input; sensitive details withheld')


def strict_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result: raise invalid()
            result[key] = value
        return result
    path = Path(path)
    if path.stat().st_size > 32 * 1024 * 1024: raise invalid()
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(invalid()))


def key_file(path):
    path = Path(path)
    if path.stat().st_size > 256: raise invalid()
    try: value = base64.b64decode(path.read_text(encoding='ascii').strip(), validate=True)
    except Exception: raise invalid() from None
    if len(value) != 32: raise invalid()
    return value


def rotate_value(value, old_key, new_key):
    if value is None or value == '': return value
    if not isinstance(value, str) or len(value) > 1024 * 1024 or not value.startswith('v1:'): raise invalid()
    try:
        payload = base64.b64decode(value[3:], validate=True)
        if len(payload) < 29 or base64.b64encode(payload).decode('ascii') != value[3:]: raise invalid()
        plaintext = AESGCM(old_key).decrypt(payload[:12], payload[12:], None)
        plaintext.decode('utf-8')
        nonce = os.urandom(12)
        encrypted = nonce + AESGCM(new_key).encrypt(nonce, plaintext, None)
        if not hmac.compare_digest(AESGCM(new_key).decrypt(encrypted[:12], encrypted[12:], None), plaintext): raise invalid()
        return 'v1:' + base64.b64encode(encrypted).decode('ascii')
    except Exception: raise invalid() from None


def rows_from(snapshot, inventory):
    if not isinstance(snapshot, dict) or set(snapshot) != {'schemaVersion', 'inventorySha256', 'rows'}:
        raise invalid()
    if type(snapshot['schemaVersion']) is not int or snapshot['schemaVersion'] != 1 or snapshot['inventorySha256'] != contract.digest(inventory): raise invalid()
    rows = snapshot['rows']
    if not isinstance(rows, list) or len(rows) > 100000: raise invalid()
    ids = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {'id', 'connectionRevision', 'password', 'writePassword'}: raise invalid()
        if type(row['id']) is not int or not 0 < row['id'] <= 2**63 - 1 or row['id'] in ids: raise invalid()
        if type(row['connectionRevision']) is not int or not 0 <= row['connectionRevision'] <= 2**63 - 3: raise invalid()
        if any(row[field] is not None and not isinstance(row[field], str) for field in ('password', 'writePassword')): raise invalid()
        ids.add(row['id'])
    return sorted(rows, key=lambda row: row['id'])


def literal(value):
    if value is None: return 'NULL'
    return "'" + value.replace("'", "''") + "'"


def fenced_sql(inventory, before, after):
    database = inventory.get('database'); system = inventory.get('systemIdentifier'); oid = inventory.get('databaseOid')
    if not isinstance(database, str) or not database or len(database.encode('utf-8')) > 63 or '\0' in database: raise invalid()
    if not isinstance(system, str) or not re.fullmatch('[0-9]{1,20}', system): raise invalid()
    # Existing DatabaseRunner inventory emits oid::text, not a JSON number.
    if isinstance(oid, str) and re.fullmatch('[1-9][0-9]{0,9}', oid): oid = int(oid)
    if type(oid) is not int or not 0 < oid < 2**32: raise invalid()
    lines = ['-- PRIVATE: contains encrypted credentials. Stop all credential writers and retain both keys.',
             '-- Run with psql -X -v ON_ERROR_STOP=1. Approval and a verified restore are prerequisites.',
             'BEGIN;', "SET LOCAL lock_timeout = '5s';", "SET LOCAL statement_timeout = '60s';",
             'LOCK TABLE metadata.data_source IN EXCLUSIVE MODE;', 'DO $master_key_rotation$', 'DECLARE affected integer;', 'BEGIN',
             'IF current_database() <> ' + literal(database) + ' OR (SELECT system_identifier::text FROM pg_control_system()) <> ' + literal(system)
             + ' OR (SELECT oid FROM pg_database WHERE datname=current_database()) <> ' + str(oid) + " THEN RAISE EXCEPTION 'Rotation database identity mismatch'; END IF;",
             'IF (SELECT count(*) FROM metadata.data_source) <> ' + str(len(before)) + " THEN RAISE EXCEPTION 'Rotation inventory changed'; END IF;"]
    for row in before:
        condition = 'id=' + str(row['id']) + ' AND connection_revision=' + str(row['connectionRevision'])
        condition += ' AND password IS NOT DISTINCT FROM ' + literal(row['password'])
        condition += ' AND write_password IS NOT DISTINCT FROM ' + literal(row['writePassword'])
        lines.append('IF NOT EXISTS (SELECT 1 FROM metadata.data_source WHERE ' + condition + ") THEN RAISE EXCEPTION 'Rotation snapshot changed'; END IF;")
    for old, new in zip(before, after, strict=True):
        if old['id'] != new['id']: raise invalid()
        if old['password'] == new['password'] and old['writePassword'] == new['writePassword']: continue
        lines.append('UPDATE metadata.data_source SET password=' + literal(new['password']) + ',write_password=' + literal(new['writePassword'])
                     + ' WHERE id=' + str(old['id']) + ' AND connection_revision=' + str(old['connectionRevision']) + ';')
        lines.append("GET DIAGNOSTICS affected = ROW_COUNT; IF affected <> 1 THEN RAISE EXCEPTION 'Rotation CAS failed'; END IF;")
        lines.append('IF NOT EXISTS (SELECT 1 FROM metadata.data_source WHERE id=' + str(old['id'])
                     + ' AND connection_revision=' + str(new['connectionRevision']) + ' AND password IS NOT DISTINCT FROM ' + literal(new['password'])
                     + ' AND write_password IS NOT DISTINCT FROM ' + literal(new['writePassword']) + ") THEN RAISE EXCEPTION 'Rotation revision or ciphertext mismatch'; END IF;")
    lines.extend(['END;', '$master_key_rotation$;', 'COMMIT;', ''])
    return '\n'.join(lines)


def prepare(inventory, snapshot, backup_receipt, old_key, new_key):
    if len(old_key) != 32 or len(new_key) != 32 or hmac.compare_digest(old_key, new_key): raise invalid()
    database_roles.verify_backup(backup_receipt, inventory)
    before = rows_from(snapshot, inventory); after = []
    for row in before:
        new = {**row, 'password': rotate_value(row['password'], old_key, new_key),
               'writePassword': rotate_value(row['writePassword'], old_key, new_key)}
        if new['password'] != row['password'] or new['writePassword'] != row['writePassword']:
            new['connectionRevision'] += 1
        after.append(new)
    rollback_result = [{**row, 'connectionRevision': old['connectionRevision'] + (2 if row['connectionRevision'] != old['connectionRevision'] else 0)}
                       for old, row in zip(before, after, strict=True)]
    # Restore old ciphertext while advancing the revision; never resurrect an earlier fence.
    for old, row in zip(before, rollback_result, strict=True):
        row['password'], row['writePassword'] = old['password'], old['writePassword']
    apply_sql = fenced_sql(inventory, before, after)
    rollback_sql = fenced_sql(inventory, after, rollback_result)
    report = {'schemaVersion': 1, 'status': 'prepared-not-applied', 'database': inventory['database'],
              'systemIdentifier': inventory['systemIdentifier'], 'databaseOid': inventory['databaseOid'],
              'inventorySha256': contract.digest(inventory), 'snapshotSha256': contract.digest(snapshot),
              'backupSha256': backup_receipt['sha256'], 'verifiedRestoreAtUtc': backup_receipt['verifiedRestoreAtUtc'],
              'rows': len(before), 'changedRows': sum(a != b for a, b in zip(before, after, strict=True)),
              'oldKeySha256': hashlib.sha256(old_key).hexdigest(), 'newKeySha256': hashlib.sha256(new_key).hexdigest(),
              'applySha256': hashlib.sha256(apply_sql.encode('utf-8')).hexdigest(),
              'rollbackSha256': hashlib.sha256(rollback_sql.encode('utf-8')).hexdigest()}
    return report, apply_sql, rollback_sql


def write_new(directory, report, apply_sql, rollback_sql):
    PRIVATE.mkdir(parents=True, exist_ok=True)
    path = Path(directory).absolute()
    if path.parent.resolve() != PRIVATE.resolve() or path.exists() or path.is_symlink(): raise invalid()
    if not re.fullmatch('master-key-[a-f0-9]{32}', path.name): raise invalid()
    path.mkdir(mode=0o700)
    written = []
    try:
        for name, value in [('apply.sql', apply_sql), ('rollback.sql', rollback_sql), ('plan.json', json.dumps(report, indent=2) + '\n')]:
            target = path / name
            with target.open('x', encoding='utf-8', newline='\n') as output:
                written.append(target);os.chmod(target, 0o600);output.write(value)
    except Exception:
        for target in written: target.unlink(missing_ok=True)
        path.rmdir()
        raise
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('inventory', 'snapshot', 'backup-receipt', 'old-key-file', 'new-key-file'): parser.add_argument('--' + option, required=True)
    parser.add_argument('--directory')
    args = parser.parse_args()
    report, apply_sql, rollback_sql = prepare(strict_json(args.inventory), strict_json(args.snapshot), strict_json(args.backup_receipt),
                                             key_file(args.old_key_file), key_file(args.new_key_file))
    directory = write_new(args.directory or PRIVATE / ('master-key-' + uuid.uuid4().hex), report, apply_sql, rollback_sql)
    print('Prepared ' + str(report['changedRows']) + ' credential rows; no database or installed key changed; review ' + str(directory))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print('Offline key rotation preparation failed: ' + type(error).__name__ + '; sensitive details withheld', file=sys.stderr)
        sys.exit(1)

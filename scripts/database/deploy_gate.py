#!/usr/bin/env python3
"""Read-only deployment admission: signed images/source, live DB receipt, actual Compose wiring.

Resolved Compose contains secrets. Consume it from stdin, never print or save it.
Only a private binding record is written; no SQL migration or role mutation runs.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
from datetime import datetime, timezone
from urllib.parse import urlsplit
import contract
import database_roles as roles
import compose_contract

ROOT=contract.ROOT
spec=importlib.util.spec_from_file_location('aetheris_release_manifest',ROOT/'scripts/release-manifest.py')
release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)


def private_file(value,exists=True):
    path=Path(value)
    path=(path if path.is_absolute() else ROOT/path).resolve(strict=exists)
    if (ROOT/'configs/database/private').resolve() not in path.parents or (exists and not path.is_file()):
        raise ValueError('Database deployment inputs/records must stay in the private directory')
    return path


def compose_identity():
    # Read the actual service PostgreSQL, not merely the host endpoint named in
    # an operator JSON. The fixed SQL returns no password or business data.
    query="SELECT json_build_object('systemIdentifier',system_identifier::text,'databaseOid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),'database',current_database()) FROM pg_control_system()"
    command='exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc '+"'"+query.replace("'","'\"'\"'")+"'"
    result=subprocess.run(['docker','compose','exec','-T','postgres','sh','-c',command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
    if result.returncode:raise ValueError('Cannot verify the actual Compose database identity')
    value=json.loads(result.stdout)
    if set(value)!={'systemIdentifier','databaseOid','database'}:raise ValueError('Invalid Compose database identity')
    return value


def verify_wiring(model,document,manifest):
    compose_contract.validate(model)
    for name,image in manifest['images'].items():
        actual=release.COMPOSE_SERVICES[name]
        if model['services'][actual].get('image')!=image:raise ValueError('Compose image differs from signed release')
        if actual!=name and name in model['services']:raise ValueError('Unexpected application service alias')
    url=document['admin']['url']
    if not url.startswith('jdbc:postgresql://'):raise ValueError('PostgreSQL operator URL required')
    target=urlsplit(url[5:])
    database=target.path.removeprefix('/')
    if not re.fullmatch('[A-Za-z0-9_-]+',database) or target.query or target.fragment:raise ValueError('Ambiguous database URL')
    if model['services']['postgres']['environment'].get('POSTGRES_DB')!=database:raise ValueError('Compose/operator database names differ')
    for service,schema in contract.services().items():
        item=model['services'][release.COMPOSE_SERVICES[service]];env=item.get('environment',{})
        if any(str(env.get(k))!=v for k,v in {'DB_HOST':'postgres','DB_PORT':'5432','DB_NAME':database}.items()):
            raise ValueError('Runtime points outside the verified Compose database')
        if item.get('command') or item.get('entrypoint'):raise ValueError('Unreviewed runtime entrypoint override')
        spring_allowed={'SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE',
                        'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE','SPRING_CONFIG_IMPORT','SPRING_FLYWAY_ENABLED'}
        for key,value in env.items():
            canonical=key.upper().replace('.','_').replace('-','_')
            if canonical.startswith('SPRING_') and (key!=canonical or canonical not in spring_allowed):
                raise ValueError('Unreviewed Spring environment can override verified database/configuration')
            if key in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS'} and value:
                for argument in shlex.split(str(value)):
                    name=argument.removeprefix('-D').split('=',1)[0].lower().replace('_','.').replace('-','.')
                    if argument.startswith('@') or (argument.startswith('-D') and name.startswith(('spring.','db.'))):
                        raise ValueError('JVM options override verified runtime configuration')
        expected=private_file(document['credentials'][schema]['runtimeFile'])
        mounted=model.get('secrets',{}).get('db_'+schema+'_runtime',{}).get('file')
        if not mounted or private_file(mounted)!=expected:raise ValueError('Compose mounts a different runtime secret')


def verify(manifest_path,config_path,receipt_path,model,record_path):
    manifest_bytes=Path(manifest_path).read_bytes()
    manifest=release.validate(json.loads(manifest_bytes))
    if manifest['schemaVersion']!=2 or manifest.get('databaseSources')!=contract.source_manifest():
        raise ValueError('Signed release migration sources differ from this checkout')
    config_path=private_file(config_path);receipt_path=private_file(receipt_path);record_path=private_file(record_path,False)
    document=roles.read_json(config_path)
    verify_wiring(model,document,manifest)
    receipt=roles.read_json(receipt_path)
    request=roles.config(config_path,'verify-receipt')
    roles.prepare()
    roles.verify_receipt(receipt,request,live=True)
    snapshot=roles.runner({**request,'command':'inventory'})
    identity=compose_identity()
    if any(str(identity.get(key))!=str(snapshot.get(key)) for key in ('systemIdentifier','databaseOid','database')):
        raise ValueError('Receipt belongs to a different database than the Compose service')
    # Recheck files/source after live I/O. Record no password fingerprints or
    # resolved environment; this record is private and is not a deployment claim.
    if manifest['databaseSources']!=contract.source_manifest() or roles.read_json(receipt_path)!=receipt or Path(manifest_path).read_bytes()!=manifest_bytes:
        raise ValueError('Source or receipt changed during deployment verification')
    roles.verify_receipt(receipt,roles.config(config_path,'verify-receipt'),live=False)
    record={'schemaVersion':1,'purpose':'aetheris-deployment-admission','status':'verified-not-deployed',
            'verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'commit':manifest['commit'],
            'releaseManifestSha256':hashlib.sha256(manifest_bytes).hexdigest(),
            'readinessReceiptSha256':hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
            'images':manifest['images'],'sourceHashes':manifest['databaseSources'],'databaseIdentity':identity}
    roles.write_json(record_path,record)
    return record


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('manifest','config','receipt','record'):parser.add_argument('--'+name,required=True)
    args=parser.parse_args()
    raw=sys.stdin.buffer.read(2*1024*1024+1)
    if len(raw)>2*1024*1024:raise ValueError('Resolved Compose is too large')
    verify(args.manifest,args.config,args.receipt,json.loads(raw),args.record)
    print('Deployment database/source/image admission verified; no database changes performed')


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Deployment admission failed: '+type(error).__name__+'; sensitive configuration/output withheld',file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env python3
"""Database role plan/tooling. Production actions require explicit subcommands."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone
import contract

ROOT = contract.ROOT
RUNNER = Path(__file__).resolve().parent / 'runner'


def call(args, *, stdin=None, env=None, timeout=180):
    options={'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}
    result = subprocess.run(args, input=stdin, capture_output=True, text=True, encoding='utf-8', errors='replace', env=env, timeout=timeout,**options)
    if result.returncode:
        raise RuntimeError(f'{Path(str(args[0])).name} operation failed (exit {result.returncode}); sensitive command output withheld')
    return result.stdout


def prepare():
    mvn = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if not mvn: raise RuntimeError('Maven is required; use existing offline cache or reviewed migration image')
    call([mvn, '-q', '-o', '-f', str(RUNNER/'pom.xml'), 'compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/classpath.txt'], timeout=180)


def runner(request):
    if request.get('command') in ('inventory','apply-plan'):
        request={**request,'directories':directories(),'sourceHashes':contract.source_manifest()}
    cp = str(RUNNER/'target/classes') + os.pathsep + (RUNNER/'target/classpath.txt').read_text(encoding='utf-8').strip()
    options={'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}
    java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if os.environ.get('JAVA_HOME') else 'java'
    proc=subprocess.run([java,'-cp',cp,'com.aetheris.database.DatabaseRunner'],input=json.dumps(request),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180,**options)
    output=proc.stdout
    if proc.returncode:
        failures=[json.loads(line[len('AETHERIS_ERROR='):]) for line in output.splitlines() if line.startswith('AETHERIS_ERROR=')]
        raise RunnerError(failures[0] if len(failures)==1 else {'errorClass':'UnclassifiedRunnerFailure'})
    records = [line[len('AETHERIS_RESULT='):] for line in output.splitlines() if line.startswith('AETHERIS_RESULT=')]
    if len(records) != 1: raise RuntimeError('Runner did not return exactly one structured result')
    return json.loads(records[0])


class RunnerError(RuntimeError):
    def __init__(self, details):
        self.details=details
        super().__init__('Runner operation failed: '+str(details.get('errorClass')))


def read_json(path): return json.loads(Path(path).read_text(encoding='utf-8'))


def write_json(path, value):
    path=Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def secret_value(path):
    private=(ROOT/'configs/database/private').resolve()
    value=Path(path)
    value=(value if value.is_absolute() else ROOT/value).resolve(strict=True)
    if private not in value.parents or not value.is_file():raise ValueError('Secret files must reside inside ignored configs/database/private')
    return value.read_text(encoding='utf-8').strip()


def config(path,command=None,service=None):
    value=read_json(path)
    if 'password' in value.get('admin', {}): raise ValueError('Use passwordFile, not inline passwords')
    value['admin']['password']=secret_value(value['admin']['passwordFile'])
    for schema,credential in value.get('credentials',{}).items():
        for kind in ('runtime','migration'):
            if kind in credential: raise ValueError('Use password files, not inline credentials')
            needed=command is None or (kind=='migration' and command=='migrate' and schema==contract.services()[service]) or (kind=='runtime' and command in ('receipt','sync-grants','verify-receipt','provision-empty','apply-plan'))
            if needed and kind+'File' in credential: credential[kind]=secret_value(credential[kind+'File'])
            # File arguments are consumed here. In particular, recovery/readonly
            # commands must not ask the Java process to open unrelated secrets.
            credential.pop(kind+'File',None)
    return value


def directories():
    return {s:str(ROOT/'services'/s/'src/main/resources/db/migration') for s in contract.services()}


def check_owned_inventory(snapshot):
    if {x['name'] for x in snapshot['schemas']}!=set(contract.services().values()):raise ValueError('All nine schemas must exist')
    if any(snapshot.get(k) for k in ('dependencies','extensions','types')):raise ValueError('Unreviewed database objects/dependencies')
    for obj in [*snapshot['schemas'],*snapshot['relations'],*snapshot['functions']]:
        schema=obj.get('schema',obj.get('name'))
        if obj['owner']!=contract.role(schema,'_owner'):raise ValueError('Use a reviewed inventory/owner migration plan first')
    contract.check_function_inventory(snapshot['functions'])
    for acl in snapshot['defaultAcls']:
        if acl['owner'] not in {contract.role(s,'_owner') for s in contract.services().values()} or acl['schema'] is not None or acl['type']!='f' or acl['acl']!='{'+acl['owner']+'=X/'+acl['owner']+'}':
            raise ValueError('Unreviewed default privileges')
    for schema in contract.services().values():
        checked=snapshot.get('sourceValidation',{}).get(schema,{})
        if not checked.get('validationSuccessful') or not checked.get('scriptsMatch') or checked.get('pending')!=0:raise ValueError('Current Flyway source/history does not match')


def readiness_receipt(request):
    sources=contract.source_manifest()
    snapshot=runner({**request,'command':'inventory'});check_owned_inventory(snapshot)
    verification=runner({**request,'command':'verify','directories':directories()})
    after=runner({**request,'command':'inventory'})
    if after!=snapshot or contract.source_manifest()!=sources:raise ValueError('Database or source changed during verification')
    bindings={'databaseUrl':request['admin']['url'],'sourceHashes':sources,'serviceSchemas':contract.services(),
              'runtimeRoles':{s:contract.role(s) for s in contract.services().values()},
              'runtimeSecretFingerprints':{s:hashlib.sha256(request['credentials'][s]['runtime'].encode()).hexdigest() for s in contract.services().values()}}
    return {'schemaVersion':1,'purpose':'aetheris-database-runtime-readiness','runId':uuid.uuid4().hex,
            'verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'status':'passed','bindings':bindings,
            'bindingsSha256':contract.digest(bindings),'inventorySha256':contract.digest(snapshot),'verification':verification}


def verify_receipt(receipt,request,live=True):
    if receipt.get('schemaVersion')!=1 or receipt.get('purpose')!='aetheris-database-runtime-readiness' or receipt.get('status')!='passed':raise ValueError('Wrong readiness receipt')
    age=(datetime.now(timezone.utc)-datetime.fromisoformat(receipt['verifiedAtUtc'])).total_seconds()
    if age<0 or age>86400:raise ValueError('Readiness receipt expired or future-dated')
    bindings={'databaseUrl':request['admin']['url'],'sourceHashes':contract.source_manifest(),'serviceSchemas':contract.services(),
              'runtimeRoles':{s:contract.role(s) for s in contract.services().values()},
              'runtimeSecretFingerprints':{s:hashlib.sha256(request['credentials'][s]['runtime'].encode()).hexdigest() for s in contract.services().values()}}
    if bindings!=receipt['bindings'] or contract.digest(bindings)!=receipt['bindingsSha256']:raise ValueError('Runtime config, source or secret fingerprint mismatch')
    if live:
        fresh=readiness_receipt(request)
        if fresh['bindings']!=bindings or fresh['inventorySha256']!=receipt['inventorySha256']:raise ValueError('Database changed since readiness verification')
    return True


def verify_backup(receipt, inventory):
    required=('archive','sha256','database','verifiedRestoreAtUtc','backupCreatedAtUtc','sourceInventorySha256','sourceSystemIdentifier','sourceDatabaseOid')
    if any(not receipt.get(k) for k in required) or receipt['database']!=inventory['database']:
        raise ValueError('Backup receipt must identify a verified restore and the same database')
    if receipt['sourceInventorySha256']!=contract.digest(inventory) or receipt['sourceSystemIdentifier']!=inventory.get('systemIdentifier') or receipt['sourceDatabaseOid']!=inventory.get('databaseOid'):
        raise ValueError('Backup receipt belongs to a different database cluster, OID, or inventory')
    times=[]
    for field in ('backupCreatedAtUtc','verifiedRestoreAtUtc'):
        instant=datetime.fromisoformat(receipt[field])
        if instant.tzinfo is None:raise ValueError('Backup timestamps require an explicit timezone')
        age=(datetime.now(timezone.utc)-instant).total_seconds()
        if age<0 or age>86400:raise ValueError('Backup receipt expired or future-dated')
        times.append(instant)
    if times[1]<times[0]:raise ValueError('Restore verification predates backup')
    p=Path(receipt['archive'])
    if not p.is_file():raise ValueError('Backup archive is missing')
    with p.open('rb') as stream:
        header=stream.read(5);stream.seek(0);sha=hashlib.file_digest(stream,'sha256').hexdigest()
    if header!=b'PGDMP' or sha!=receipt['sha256']:
        raise ValueError('Backup archive missing, not custom format, or digest mismatch')


def strip_transaction(sql):
    lines=sql.splitlines()
    if lines[0]!='BEGIN;' or lines[-1]!='COMMIT;': raise ValueError('Expected bounded transaction SQL')
    return '\n'.join(lines[1:-1])


def bootstrap(): return '\n'.join(contract.bootstrap_sql(s) for s in contract.services().values())


def docker(args, **kwargs): return call(['docker',*args],**kwargs)


def tool_hashes():
    paths=[Path(__file__),Path(contract.__file__),RUNNER/'pom.xml',ROOT/'configs/database/services.json']
    paths.extend((RUNNER/'src/main/java').rglob('*.java'))
    return {p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}


def acceptance(output, image='postgres:16-alpine'):
    if image!='postgres:16-alpine': raise ValueError('This audit only permits the already approved PostgreSQL 16 image')
    if any(os.environ.get(key) for key in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')):raise ValueError('Docker endpoint overrides are not permitted')
    if any(os.environ.get(key) for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','MAVEN_OPTS')):raise ValueError('Audit JVM overrides are not permitted')
    home=Path(os.environ.get('JAVA_HOME',''))
    if not home.is_absolute() or not home.is_dir():raise ValueError('Explicit JDK21 JAVA_HOME required')
    java=home/'bin'/('java.exe' if os.name=='nt' else 'java')
    options={'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}
    checked=subprocess.run([str(java),'-XshowSettings:properties','-version'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15,**options)
    import re
    if checked.returncode or not re.search(r'java\.specification\.version\s*=\s*21\s',checked.stderr):raise ValueError('Actual JDK21 required')
    actual=re.search(r'java\.home\s*=\s*(.+)',checked.stderr)
    if not actual or Path(actual.group(1).strip()).resolve()!=home.resolve():raise ValueError('JDK home mismatch')
    context=json.loads(docker(['context','inspect']))
    if len(context)!=1:raise ValueError('Unambiguous local Docker context required')
    endpoint=context[0].get('Endpoints',{}).get('docker',{}).get('Host','')
    if not (re.fullmatch(r'npipe:/{2,}\./pipe/[A-Za-z0-9_.-]+',endpoint) or re.fullmatch(r'unix:///[^\s]+',endpoint)):
        raise ValueError('Local Docker context required')
    prepare()
    run_id=uuid.uuid4().hex; name='aetheris-roles-audit-'+run_id
    report={'schemaVersion':1,'runId':run_id,'startedAtUtc':datetime.now(timezone.utc).isoformat(),'status':'failed','javaHome':str(home.resolve()),'javaSpecificationVersion':21,'sourceHashes':contract.source_manifest(),'toolHashes':tool_hashes(), 'cleanup':{'status':'not_started'}}
    admin_password=secrets.token_urlsafe(36)
    credentials={s:{'runtime':secrets.token_urlsafe(36),'migration':secrets.token_urlsafe(36)} for s in contract.services().values()}
    cid=None; base=None; failure=None
    try:
        report['imageId']=docker(['image','inspect',image,'--format','{{.Id}}']).strip()
        env=dict(os.environ,POSTGRES_PASSWORD=admin_password,POSTGRES_USER='audit_admin',POSTGRES_DB='aetheris_roles_audit')
        if not re.fullmatch(r'sha256:[a-f0-9]{64}',report['imageId']):raise ValueError('Invalid cached PostgreSQL image ID')
        cid=docker(['run','-d','--rm','--pull=never','--name',name,'--label','aetheris.database.roles.audit='+run_id,
                    '--memory','512m','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true',
                    '--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m','-p','127.0.0.1::5432',
                    '--env','POSTGRES_PASSWORD','--env','POSTGRES_USER','--env','POSTGRES_DB',report['imageId']],env=env).strip()
        if not re.fullmatch('[a-f0-9]{64}',cid):raise ValueError('Invalid owned container ID')
        info=json.loads(docker(['inspect',cid]))[0]
        if any(m.get('Type')!='tmpfs' for m in info.get('Mounts',[])):raise ValueError('Only tmpfs fixture storage permitted')
        report['containerId']=cid
        port=docker(['port',cid,'5432/tcp']).strip()
        if not port.startswith('127.0.0.1:') or not port.rsplit(':',1)[1].isdigit(): raise RuntimeError('Unexpected published address')
        base={'admin':{'url':'jdbc:postgresql://'+port+'/aetheris_roles_audit','user':'audit_admin','password':admin_password},'credentials':credentials}
        deadline=time.monotonic()+60
        while True:
            probe=subprocess.run(['docker','exec',cid,'pg_isready','-U','audit_admin','-d','aetheris_roles_audit'],capture_output=True,timeout=10)
            if probe.returncode==0:break
            if time.monotonic()>deadline:raise RuntimeError('Disposable database readiness deadline')
            time.sleep(.5)
        report['legacyFixture']=runner({**base,'command':'legacy-fixture','directories':directories()})
        before=runner({**base,'command':'inventory'})
        upgrade=contract.plan(before)
        report['existingPlanInventorySha256']=upgrade['inventorySha256']
        write_json(Path(output).parent/'existing-plan.json',upgrade)
        # Exercise a real custom-format backup, restore, and the same receipt
        # validator used by the explicit existing-database apply command.
        backup_created=datetime.now(timezone.utc).isoformat()
        docker(['exec',cid,'pg_dump','-U','audit_admin','-d','aetheris_roles_audit','-Fc','-f','/tmp/roles-before.dump'])
        docker(['exec',cid,'createdb','-U','audit_admin','aetheris_backup_restore_check'])
        docker(['exec',cid,'pg_restore','-U','audit_admin','-d','aetheris_backup_restore_check','--exit-on-error','/tmp/roles-before.dump'])
        with tempfile.TemporaryDirectory(prefix='roles-backup-',dir=Path(output).parent) as backupdir:
            archive=Path(backupdir)/'before.dump';docker(['cp',cid+':/tmp/roles-before.dump',str(archive)])
            backup={'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'database':'aetheris_roles_audit',
                    'backupCreatedAtUtc':backup_created,'verifiedRestoreAtUtc':datetime.now(timezone.utc).isoformat(),
                    'sourceInventorySha256':contract.digest(before),'sourceSystemIdentifier':before['systemIdentifier'],'sourceDatabaseOid':before['databaseOid']}
            verify_backup(backup,before)
            report['existingBackup']={k:v for k,v in backup.items() if k!='archive'}
            # A changed inventory must fail before any role/object mutation.
            changed=json.loads(json.dumps(before));changed['databaseOwner']='incorrect_expected_owner'
            try:runner({**base,'command':'apply-plan','expectedInventory':changed,'sql':strip_transaction(upgrade['applySql']),'bootstrapSql':bootstrap()})
            except RunnerError as exc:
                if exc.details.get('errorClass')!='IllegalStateException':raise
                report['changedInventoryRejected']=True
            else:raise RuntimeError('Changed database inventory was accepted')
            if runner({**base,'command':'inventory'})!=before:raise RuntimeError('Rejected plan mutated the database')
            runner({**base,'command':'apply-plan','expectedInventory':before,'sql':strip_transaction(upgrade['applySql']),'bootstrapSql':bootstrap()})
        # Separate empty database proves source installation without relying on
        # the preceding legacy-owner fixture/history tables.
        runner({**base,'command':'sql','sql':'CREATE DATABASE aetheris_roles_clean'})
        clean={**base,'admin':{**base['admin'],'url':base['admin']['url'].rsplit('/',1)[0]+'/aetheris_roles_clean'}}
        runner({**clean,'command':'provision','sql':bootstrap()})
        migrations=[]
        for service,schema in contract.services().items():
            directory=ROOT/'services'/service/'src/main/resources/db/migration'
            migrations.append(runner({**clean,'command':'migrate','schema':schema,'directory':str(directory)}))
        snapshot=runner({**base,'command':'inventory'})
        report['functions']=[{k:item[k] for k in ('schema','name','arguments','resultType','securityDefiner')} for item in snapshot['functions']]
        runner({**base,'command':'sql','sql':f'REVOKE CREATE, TEMPORARY ON DATABASE aetheris_roles_audit FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC; '+contract.grants_sql(snapshot)})
        report['runtime']=runner({**base,'command':'acceptance','directories':directories()})
        ready=readiness_receipt(base);verify_receipt(ready,base)
        report['readinessReceiptVerified']=True
        # Audit report intentionally omits secret fingerprints; the opt-in
        # production readiness receipt is a separate private deployment input.
        report['readinessInventorySha256']=ready['inventorySha256']
        report['historyIntegrityNegatives']=history_integrity_negatives(base)
        restarts=[]
        for service,schema in contract.services().items():
            restarts.append(runner({**base,'command':'migrate','schema':schema,'directory':str(ROOT/'services'/service/'src/main/resources/db/migration')}))
        if any(r['migrationsExecuted'] for r in restarts):raise RuntimeError('Restart unexpectedly applied migrations')
        report['migrations']=migrations;report['restarts']=restarts
        report['history']=history_acceptance(base,Path(output).parent)
        if contract.source_manifest()!=report['sourceHashes']:raise RuntimeError('Source migrations changed during acceptance')
        if tool_hashes()!=report['toolHashes']:raise RuntimeError('Role tool source changed during acceptance')
        report['status']='passed'
    except Exception as exc:
        failure=exc;report['failureType']=type(exc).__name__
        if isinstance(exc,RunnerError):report['runnerFailure']=exc.details
    finally:
        retired=False
        if base:
            try: runner({**base,'command':'retire'});retired=True
            except Exception: report['retirementFailure']=True
        report['migrationCredentialsRetired']=retired
        try:
                owned=docker(['ps','-a','--no-trunc','--filter','label=aetheris.database.roles.audit='+run_id,'--format','{{.ID}}']).split()
                volumes_absent=True
                for identity in owned:
                    info=json.loads(docker(['inspect',identity]))[0]
                    if info.get('Config',{}).get('Labels',{}).get('aetheris.database.roles.audit')!=run_id:raise RuntimeError('Container ownership label changed')
                    if any(m.get('Type')=='volume' for m in info.get('Mounts',[])):volumes_absent=False
                    docker(['rm','-f',identity])
                remaining=docker(['ps','-a','--filter','label=aetheris.database.roles.audit='+run_id,'--format','{{.ID}}']).strip()
                if remaining or not volumes_absent:raise RuntimeError('Disposable audit resources remain')
                report['cleanup']={'status':'passed','containerRemoved':True,'volumesCreated':False,'runLabel':run_id}
        except Exception:report['cleanup']={'status':'failed'}
        if not retired or report['cleanup']['status']!='passed':report['status']='failed'
        report['completedAtUtc']=datetime.now(timezone.utc).isoformat();write_json(output,report)
    if failure or report['status']!='passed':raise RuntimeError('Database role acceptance failed; inspect the sanitized report') from failure
    return report


def history_acceptance(base,temp_root=None):
    # History is tested in separate disposable databases; no history row repair.
    result=[]
    with tempfile.TemporaryDirectory(prefix='aetheris-db-history-',dir=temp_root or RUNNER/'target') as temp:
        temp=Path(temp)
        source=ROOT/'services/serving/src/main/resources/db/migration'
        historical=call(['git','show','c570203:services/serving/src/main/resources/db/migration/V3__service_api_keys.sql'])
        old_tree=call(['git','rev-parse','c570203']).strip()
        for kind in ('semantic','apikey','duplicate'):
            db='aetheris_history_'+kind
            runner({**base,'command':'sql','sql':'CREATE DATABASE '+contract.ident(db)})
            child={**base,'admin':{**base['admin'],'url':base['admin']['url'].rsplit('/',1)[0]+'/'+db}}
            runner({**child,'command':'provision','sql':bootstrap()})
            fixture=temp/kind;fixture.mkdir()
            for p in source.glob('V[12]__*.sql'):shutil.copyfile(p,fixture/p.name)
            if kind in ('semantic','duplicate'):shutil.copyfile(source/'V3__semantic_dataset.sql',fixture/'V3__semantic_dataset.sql')
            if kind in ('apikey','duplicate'):(fixture/'V3__service_api_keys.sql').write_text(historical,encoding='utf-8')
            initial={'command':'migrate','schema':'serving','directory':str(fixture)}
            if kind=='duplicate':
                try:runner({**child,**initial})
                except RunnerError as exc:
                    if not exc.details.get('duplicateVersion'):raise
                    result.append({'fixture':kind,'expectedFailure':True,'failure':exc.details,'historicalCommit':old_tree,'oldV3Sha256':hashlib.sha256(historical.encode()).hexdigest()})
                else:raise RuntimeError('Duplicate V3 unexpectedly accepted')
            else:
                runner({**child,**initial})
                before=runner({**child,'command':'inventory'})['history']['serving']
                try: upgraded=runner({**child,'command':'migrate','schema':'serving','directory':str(source)})
                except RunnerError as exc:
                    if kind!='apikey' or not exc.details.get('v3HistoryMismatch'):raise
                    after=runner({**child,'command':'inventory'})['history']['serving']
                    if before!=after:raise RuntimeError('Failed history validation changed history')
                    result.append({'fixture':kind,'expectedFailure':True,'failure':exc.details,'historyUnchanged':True,'historicalCommit':old_tree,'oldV3Sha256':hashlib.sha256(historical.encode()).hexdigest()})
                else:
                    if kind!='semantic':raise RuntimeError('Renumbered V3 unexpectedly validated')
                    result.append({'fixture':kind,'upgrade':upgraded,'historicalCommit':old_tree})
            runner({**child,'command':'retire'})
    return result


def history_integrity_negatives(base):
    before=runner({**base,'command':'inventory'})
    row=next(r for r in before['history']['serving'] if r['version']=='9')
    rank=int(row['installed_rank']);result=[]
    # Deliberate fixture corruption only in this disposable database. Production
    # CLI never edits history. Restore original values in every negative case.
    original='version='+contract_literal(row['version'])+', script='+contract_literal(row['script'])+', checksum='+str(row['checksum'])
    for name,change in [('unknown-future-version',"version='999'"),('unknown-script',"script='V9__not_in_source.sql'"),('wrong-checksum','checksum=CASE WHEN checksum=0 THEN 1 ELSE 0 END')]:
        try:
            runner({**base,'command':'sql','sql':'UPDATE serving.flyway_schema_history SET '+change+' WHERE installed_rank='+str(rank)})
            corrupt=runner({**base,'command':'inventory'})
            if corrupt['sourceValidation']['serving']['validationSuccessful']:raise RuntimeError('Unknown history passed source validation')
            try:readiness_receipt(base)
            except ValueError:result.append({'case':name,'rejected':True})
            else:raise RuntimeError('Unknown history got a readiness receipt')
        finally:runner({**base,'command':'sql','sql':'UPDATE serving.flyway_schema_history SET '+original+' WHERE installed_rank='+str(rank)})
    if runner({**base,'command':'inventory'})!=before:raise RuntimeError('History fixture was not restored exactly')
    return result


def contract_literal(value):return "'"+str(value).replace("'","''")+"'"


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    for name in ('inventory','migrate','retire','apply-plan','provision-empty','sync-grants','receipt','verify-receipt'):
        p=sub.add_parser(name);p.add_argument('--config',required=True)
        if name=='inventory':p.add_argument('--output',required=True)
        if name in ('receipt','sync-grants'):p.add_argument('--output',required=True)
        if name=='sync-grants':p.add_argument('--acknowledge-quiesced',action='store_true',required=True)
        if name=='verify-receipt':p.add_argument('--receipt',required=True)
        if name=='migrate':p.add_argument('--service',choices=list(contract.services()),required=True)
        if name=='apply-plan':
            p.add_argument('--plan',required=True);p.add_argument('--backup-receipt',required=True);p.add_argument('--acknowledge-quiesced',action='store_true',required=True)
    p=sub.add_parser('plan');p.add_argument('--inventory',required=True);p.add_argument('--output',required=True)
    p=sub.add_parser('acceptance');p.add_argument('--output',default=str(ROOT/'output/audit-database-roles/report.json'))
    args=parser.parse_args(argv)
    if args.command=='plan':write_json(args.output,contract.plan(read_json(args.inventory)));return
    if args.command=='acceptance':
        (RUNNER/'target').mkdir(exist_ok=True);r=acceptance(args.output);print(f"Database role acceptance {r['status']}: {len(r['sourceHashes'])} migrations, {len(r['runtime']['checks'])} checks");return
    request=config(args.config,args.command,getattr(args,'service',None))
    if args.command=='verify-receipt':prepare();verify_receipt(read_json(args.receipt),request);print('Database readiness receipt verified against live database/config/source');return
    if args.command=='apply-plan':
        plan=read_json(args.plan);expected=contract.plan(plan['before'])
        if plan!=expected:raise ValueError('Plan differs from deterministic policy; create a new plan')
        verify_backup(read_json(args.backup_receipt),plan['before'])
        request.update(command='apply-plan',expectedInventory=plan['before'],sql=strip_transaction(plan['applySql']),bootstrapSql=bootstrap())
    elif args.command=='migrate':
        schema=contract.services()[args.service];request.update(command='migrate',schema=schema,directory=str(ROOT/'services'/args.service/'src/main/resources/db/migration'))
    elif args.command=='provision-empty':request.update(command='provision-empty',sql=bootstrap())
    elif args.command in ('sync-grants','receipt'):
        prepare()
        snapshot=runner({**request,'command':'inventory'})
        check_owned_inventory(snapshot)
        if args.command=='sync-grants':
            runner({**request,'command':'sql','sql':'BEGIN;\n'+contract.grants_sql(snapshot)+'\nCOMMIT;'})
        receipt=readiness_receipt(request)
        write_json(args.output,receipt);print('Database runtime readiness receipt written');return
    else:request['command']=args.command
    prepare();result=runner(request)
    if args.command=='inventory':write_json(args.output,result)
    else:print(json.dumps(result))


if __name__=='__main__':
    try:main()
    except Exception as exc:
        print('Database role operation failed: '+type(exc).__name__+'. No sensitive command output was printed.',file=sys.stderr)
        sys.exit(1)

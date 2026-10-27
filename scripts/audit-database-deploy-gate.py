#!/usr/bin/env python3
"""Disposable real-PG/real-Compose deployment admission acceptance; never deploys applications."""
from __future__ import annotations
import argparse
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / 'configs/database/private'
OUTPUT = ROOT / 'output/audit-database-deploy-gate'
IMAGE = 'postgres:16-alpine'
LABEL = 'aetheris.database.deploy-gate.audit'
DATABASE = 'aetheris_deploy_gate_audit'
sys.path.insert(0, str(ROOT / 'scripts/database'))
import contract
import database_roles as roles


class AuditFailure(RuntimeError): pass


def require_frozen_sources(sources):
    if not sources or sources != contract.source_manifest():
        raise AuditFailure('ExpectedExactCurrentMigrationSources')


def utc(): return datetime.now(timezone.utc).isoformat()


def run_command(args, *, env=None, stdin=None, timeout=180, allow_failure=False):
    """Never expose captured command/model output. Terminate our own process tree on deadline."""
    options = {'cwd': ROOT, 'env': env, 'stdin': subprocess.PIPE, 'stdout': subprocess.PIPE,
               'stderr': subprocess.PIPE, 'text': True, 'encoding': 'utf-8', 'errors': 'replace'}
    if os.name == 'nt': options['creationflags'] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    else: options['start_new_session'] = True
    process = subprocess.Popen([str(x) for x in args], **options)
    try: stdout, stderr = process.communicate(stdin, timeout=timeout)
    except subprocess.TimeoutExpired:
        if os.name == 'nt': subprocess.run(['taskkill.exe', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=15)
        else: os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise AuditFailure('OwnedProcessTimeout') from None
    result = subprocess.CompletedProcess(args, process.returncode, stdout, stderr)
    if result.returncode and not allow_failure: raise AuditFailure('CommandFailed')
    return result


def clean_environment(source=None):
    source = os.environ if source is None else source
    prefixes = ('SPRING_', 'DB_', 'JWT_', 'AETHERIS_', 'POSTGRES_', 'REDIS_', 'COMPOSE_', 'DEEPSEEK_', 'INITIAL_ADMIN_', 'MINIO_')
    removed = {'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_OPTS'}
    return {k: v for k, v in source.items() if not k.upper().startswith(prefixes) and k.upper() not in removed}


def java_environment(source=None):
    source = os.environ if source is None else source
    home = Path(source.get('JAVA_HOME') or ('C:/Program Files/Java/jdk-21' if os.name == 'nt' else '/missing-jdk21'))
    if not home.is_absolute() or not home.is_dir(): raise AuditFailure('AbsoluteJdk21Required')
    home = home.resolve()
    executable = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    if not executable.is_file(): raise AuditFailure('AbsoluteJdk21Required')
    env = clean_environment(source)
    # No Oracle javapath entry is retained; every descendant java resolves the verified JDK first.
    path = [p for p in env.get('PATH', env.get('Path', '')).split(os.pathsep) if 'javapath' not in p.lower()]
    for key in list(env):
        if key.upper() == 'PATH': del env[key]
    env.update(JAVA_HOME=str(home), PATH=str(home / 'bin') + os.pathsep + os.pathsep.join(path), PYTHONDONTWRITEBYTECODE='1')
    result = run_command([executable, '-XshowSettings:properties', '-version'], env=env, timeout=15)
    if not re.search(r'java\.specification\.version\s*=\s*21\s', result.stderr): raise AuditFailure('Jdk21Required')
    return env


def checked_run_id(run_id):
    if not re.fullmatch('[0-9a-f]{32}', run_id): raise ValueError('Invalid audit run id')
    return run_id


def private_directory(run_id):
    checked_run_id(run_id)
    base = PRIVATE.resolve()
    path = base / ('deploy-gate-' + run_id)
    if path.resolve().parent != base or path.exists(): raise ValueError('Private fixture must be new and owned')
    return path


def validate_output(path):
    path = Path(path).resolve()
    if OUTPUT.resolve() not in path.parents or path.exists(): raise ValueError('New report path inside audit output required')
    return path


def owned_cleanup_path(path, run_id):
    checked_run_id(run_id)
    expected = PRIVATE.resolve() / ('deploy-gate-' + run_id)
    path = Path(path)
    if path.resolve() != expected or path.name != expected.name or path.is_symlink(): raise ValueError('Unowned cleanup path')
    return path


def synthetic_manifest(release, sources, commit):
    return {'schemaVersion': 2, 'fixturePurpose': 'UNSIGNED gate acceptance fixture; NOT a release',
            'repository': 'mazhuce/aetheris', 'commit': commit,
            'ci': {'runId': 1, 'attempt': 1, 'requiredJobs': sorted(release.ci.REQUIRED_JOBS)},
            'build': {'runId': 2, 'attempt': 1, 'ref': 'refs/heads/main'},
            'images': {name: release.PREFIX + '-' + name + '@sha256:' + hashlib.sha256(('gate-fixture:' + name).encode()).hexdigest()
                       for name in release.SERVICES}, 'databaseSources': sources}


def fixture_override(project, run_id):
    checked_run_id(run_id)
    if not re.fullmatch('dg-[0-9a-f]{32}-(primary|other)', project): raise ValueError('Invalid audit Compose project')
    return f'''services:
  postgres:
    image: {IMAGE}
    pull_policy: never
    restart: "no"
    volumes: !reset []
    tmpfs:
      - /var/lib/postgresql/data:rw,nosuid,size=256m
    mem_limit: 512m
    cpus: 1.0
    pids_limit: 128
    labels:
      {LABEL}: "{run_id}"
  redis:
    volumes: !reset []
volumes: !reset {{}}
networks:
  default:
    name: {project}_default
    ipam: !reset {{}}
    labels:
      {LABEL}: "{run_id}"
'''


def compose_environment(base, files, project, admin_secret, runtime_paths, database=DATABASE):
    env = dict(base)
    # Satisfy interpolation using only new random fixture values, never the repository .env.
    content = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
    for name in set(re.findall(r'\$\{([A-Z][A-Z0-9_]*):\?', content)): env[name] = secrets.token_urlsafe(36)
    env.update(COMPOSE_DISABLE_ENV_FILE='1', COMPOSE_FILE=os.pathsep.join(str(p) for p in files),
               COMPOSE_PATH_SEPARATOR=os.pathsep, COMPOSE_PROJECT_NAME=project,
               POSTGRES_DB=database, POSTGRES_USER='audit_admin', POSTGRES_PASSWORD=admin_secret,
               POSTGRES_PORT='0', DB_NAME=DATABASE, DB_USER='aetheris', DB_PASSWORD=secrets.token_urlsafe(36))
    for schema, path in runtime_paths.items(): env['AETHERIS_' + schema.upper() + '_DB_PASSWORD_FILE'] = str(path)
    return env


def compose_model(env):
    result = run_command(['docker', 'compose', 'config', '--format', 'json'], env=env, timeout=30)
    model = json.loads(result.stdout)
    pg = model['services']['postgres']
    if pg.get('volumes') or model.get('volumes'): raise AuditFailure('PersistentVolumeInFixture')
    if any(p.get('host_ip') != '127.0.0.1' for p in pg.get('ports', [])): raise AuditFailure('NonLoopbackPublish')
    if not pg.get('tmpfs') or env['COMPOSE_PROJECT_NAME'] + '_default' != model['networks']['default']['name']:
        raise AuditFailure('UnsafeComposeFixture')
    return model


def start_postgres(env, run_id):
    project = env['COMPOSE_PROJECT_NAME']
    if run_command(['docker', 'ps', '-a', '--filter', 'label=com.docker.compose.project=' + project, '--format', '{{.ID}}'], env=env).stdout.strip():
        raise AuditFailure('ComposeProjectAlreadyExists')
    compose_model(env)  # Proves this installed Compose supports !reset before any up.
    run_command(['docker', 'compose', 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'postgres'], env=env, timeout=60)
    cid = run_command(['docker', 'compose', 'ps', '-q', 'postgres'], env=env).stdout.strip()
    if not re.fullmatch('[0-9a-f]{64}', cid): raise AuditFailure('MissingOwnedContainer')
    info = json.loads(run_command(['docker', 'inspect', cid], env=env).stdout)[0]
    if info['Config']['Labels'].get(LABEL) != run_id or info['Config']['Labels'].get('com.docker.compose.project') != project:
        raise AuditFailure('ContainerOwnershipMismatch')
    if any(m.get('Type') != 'tmpfs' for m in info.get('Mounts', [])): raise AuditFailure('UnexpectedContainerMount')
    ports = info['NetworkSettings']['Ports'].get('5432/tcp')
    if not ports or len(ports) != 1 or ports[0]['HostIp'] != '127.0.0.1': raise AuditFailure('NonLoopbackPublish')
    port = int(ports[0]['HostPort'])
    if not 1 <= port <= 65535: raise AuditFailure('InvalidPublishedPort')
    deadline = time.monotonic() + 60
    while True:
        result = run_command(['docker', 'exec', cid, 'pg_isready', '-U', 'audit_admin', '-d', env['POSTGRES_DB']], env=env, timeout=10, allow_failure=True)
        if result.returncode == 0: break
        if time.monotonic() >= deadline: raise AuditFailure('PostgresReadinessTimeout')
        time.sleep(.5)
    return cid, port


def psql(cid, sql, env):
    return run_command(['docker', 'exec', '-i', cid, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'audit_admin', '-d', DATABASE, '-At'], env=env, stdin=sql, timeout=30).stdout.strip()


def fingerprint(base, cid, env):
    inventory = roles.runner({**base, 'command': 'inventory'})
    row_text = psql(cid, "SELECT row_to_json(s)::text FROM system.system_config s ORDER BY config_key;", env)
    # Only password verifier digests for this new isolated cluster; never expose them in the report.
    role_text = psql(cid, "SELECT rolname,md5(coalesce(rolpassword,'')) FROM pg_authid WHERE rolname LIKE 'aetheris_%' ORDER BY rolname;", env)
    return {'inventory': contract.digest(inventory), 'fixtureRows': hashlib.sha256(row_text.encode()).hexdigest(),
            'roleCredentials': hashlib.sha256(role_text.encode()).hexdigest()}


def configuration_mutations():
    """All values are synthetic, never deployment secrets or actual external targets."""
    return (
        ('hikari-jdbc-url', 'SPRING_DATASOURCE_HIKARI_JDBC_URL', 'jdbc:postgresql://unverified/other'),
        ('hikari-username', 'SPRING_DATASOURCE_HIKARI_USERNAME', 'unverified_admin'),
        ('hikari-password', 'SPRING_DATASOURCE_HIKARI_PASSWORD', 'synthetic-unverified-fixture-password'),
        ('spring-config-location', 'SPRING_CONFIG_LOCATION', 'file:/unreviewed/application.yml'),
        ('spring-config-additional-location', 'SPRING_CONFIG_ADDITIONAL_LOCATION', 'file:/unreviewed/application.yml'),
        ('jdk-db-host', 'JDK_JAVA_OPTIONS', '-DDB_HOST=unverified'),
        ('spring-lowercase-alias', 'spring_datasource_hikari_jdbc_url', 'jdbc:postgresql://unverified/other'),
        ('spring-dot-alias', 'spring.datasource.hikari.jdbcUrl', 'jdbc:postgresql://unverified/other'),
        ('spring-hyphen-alias', 'SPRING-DATASOURCE-HIKARI-USERNAME', 'unverified_admin'),
        ('jvm-argfile', 'JAVA_TOOL_OPTIONS', '@/unreviewed/overrides.args'),
    )


GATE_BOOTSTRAP = '''import json,os,sys
from pathlib import Path
sys.dont_write_bytecode=True
sys.path.insert(0,os.environ['AETHERIS_AUDIT_DATABASE_TOOLS'])
import database_roles
database_roles.RUNNER=Path(os.environ['AETHERIS_AUDIT_RUNNER'])
import deploy_gate
observed={'prepare':0,'runner':0,'verify_receipt':0,'compose_identity':0}
def observe(frame,event,arg):
    if event=='call':
        module=frame.f_globals.get('__name__')
        name=frame.f_code.co_name
        if (module=='database_roles' and name in ('prepare','runner','verify_receipt')) or (module=='deploy_gate' and name=='compose_identity'):
            observed[name]+=1
sys.setprofile(observe)
try: deploy_gate.main()
except Exception as error:
    trace=error.__traceback__
    while trace.tb_next is not None: trace=trace.tb_next
    print('GATE_FAILURE_FUNCTION='+trace.tb_frame.f_code.co_name,file=sys.stderr)
    print('GATE_ERROR='+type(error).__name__,file=sys.stderr)
    sys.exit(1)
finally:
    sys.setprofile(None)
    print('GATE_OBSERVED_CALLS='+json.dumps(observed,sort_keys=True),file=sys.stderr)
'''


def invoke_gate(env, runner, manifest, config, receipt, model, record):
    if record.exists(): raise AuditFailure('GateRecordAlreadyExists')
    env = dict(env, AETHERIS_AUDIT_DATABASE_TOOLS=str(ROOT / 'scripts/database'), AETHERIS_AUDIT_RUNNER=str(runner))
    result = run_command([sys.executable, '-B', '-c', GATE_BOOTSTRAP, '--manifest', manifest, '--config', config,
                          '--receipt', receipt, '--record', record], env=env, stdin=json.dumps(model), timeout=240, allow_failure=True)
    category = re.findall(r'^GATE_ERROR=([A-Za-z][A-Za-z0-9_]*)$', result.stderr, re.M)
    counts = re.findall(r'^GATE_OBSERVED_CALLS=(\{[^\n]+\})$', result.stderr, re.M)
    failed = re.findall(r'^GATE_FAILURE_FUNCTION=([A-Za-z_][A-Za-z0-9_]*)$', result.stderr, re.M)
    if len(counts) != 1: raise AuditFailure('MissingGateObservation')
    observation = json.loads(counts[0])
    if set(observation) != {'prepare', 'runner', 'verify_receipt', 'compose_identity'} or any(type(value) is not int or value < 0 for value in observation.values()):
        raise AuditFailure('InvalidGateObservation')
    return result.returncode, category[-1] if category else None, observation, failed[-1] if failed else None


def source_hashes():
    paths = [ROOT / 'scripts/database/deploy_gate.py', ROOT / 'scripts/database/database_roles.py', ROOT / 'scripts/database/contract.py',
             ROOT / 'scripts/database/compose_contract.py', ROOT / 'scripts/release-manifest.py', ROOT / 'scripts/deploy-remote.sh',
             ROOT / 'docker-compose.yml', ROOT / 'docker-compose.database-roles.yml', ROOT / 'configs/database/services.json',
             ROOT / 'scripts/database/runner/pom.xml', Path(__file__).resolve()]
    paths.extend((ROOT / 'scripts/database/runner/src').rglob('*.java'))
    paths.extend(ROOT.glob('services/*/src/main/resources/db/migration/*.sql'))
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}


def cleanup(run_id, directory, env):
    result = {'containersRemoved': False, 'networksRemoved': False, 'volumesAbsent': False, 'privateFixturesRemoved': False}
    # Never enumerate or delete any resource that lacks this exact random audit label.
    ids = run_command(['docker', 'ps', '-aq', '--filter', 'label=' + LABEL + '=' + run_id], env=env).stdout.split()
    for cid in ids:
        info = json.loads(run_command(['docker', 'inspect', cid], env=env).stdout)[0]
        if info['Config']['Labels'].get(LABEL) != run_id or info['Config']['Labels'].get('com.docker.compose.project') not in {'dg-' + run_id + '-primary', 'dg-' + run_id + '-other'}:
            raise AuditFailure('CleanupOwnershipMismatch')
        run_command(['docker', 'rm', '-f', cid], env=env, timeout=30)
    result['containersRemoved'] = not run_command(['docker', 'ps', '-aq', '--filter', 'label=' + LABEL + '=' + run_id], env=env).stdout.strip()
    networks = run_command(['docker', 'network', 'ls', '-q', '--filter', 'label=' + LABEL + '=' + run_id], env=env).stdout.split()
    for network in networks:
        info = json.loads(run_command(['docker', 'network', 'inspect', network], env=env).stdout)[0]
        if info.get('Labels', {}).get(LABEL) != run_id or info.get('Containers'): raise AuditFailure('CleanupNetworkOwnershipMismatch')
        run_command(['docker', 'network', 'rm', network], env=env, timeout=30)
    result['networksRemoved'] = not run_command(['docker', 'network', 'ls', '-q', '--filter', 'label=' + LABEL + '=' + run_id], env=env).stdout.strip()
    result['volumesAbsent'] = all(not run_command(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=dg-' + run_id + '-' + suffix], env=env).stdout.strip() for suffix in ('primary', 'other'))
    if directory is not None and directory.exists(): shutil.rmtree(owned_cleanup_path(directory, run_id))
    result['privateFixturesRemoved'] = directory is None or not directory.exists()
    return result


def acceptance(output=None):
    run_id = uuid.uuid4().hex
    output = validate_output(output or OUTPUT / run_id / 'report.json')
    report = {'schemaVersion': 1, 'runId': run_id, 'purpose': 'disposable-real-database-deploy-gate-acceptance',
              'manifestIsUnsignedSyntheticFixture': True, 'status': 'failed', 'startedAtUtc': utc(), 'checks': [], 'cleanup': {}}
    directory = None; env = None; base = None; cid = None; failure = None
    original_runner = roles.RUNNER
    original_env = dict(os.environ)
    stage = 'preflight'
    def progress(name):
        nonlocal stage
        stage = name; print('Audit stage: ' + name, flush=True)
    try:
        env = java_environment(); os.environ.clear(); os.environ.update(env)
        report['jdk'] = '21'; report['sourceHashes'] = source_hashes()
        sources = contract.source_manifest()
        require_frozen_sources(sources)
        report['migrationSourceCount'] = len(sources)
        version = run_command(['docker', 'compose', 'version', '--short'], env=env).stdout.strip()
        report['composeVersion'] = version
        report['postgresImageId'] = run_command(['docker', 'image', 'inspect', IMAGE, '--format', '{{.Id}}'], env=env).stdout.strip()
        PRIVATE.mkdir(parents=True, exist_ok=True)
        directory = private_directory(run_id); directory.mkdir(mode=0o700)
        # A private independent target avoids writes/races in the shared runner/target.
        runner = directory / 'runner'
        shutil.copytree(original_runner, runner, ignore=shutil.ignore_patterns('target', '.git'))
        roles.RUNNER = runner
        admin_secret = secrets.token_urlsafe(36)
        admin_file = directory / 'admin.password'; admin_file.write_text(admin_secret, encoding='utf-8')
        credential_files = {}; runtime_paths = {}
        for schema in contract.services().values():
            credential_files[schema] = {}
            for kind in ('runtime', 'migration'):
                path = directory / (schema + '.' + kind + '.password'); path.write_text(secrets.token_urlsafe(36), encoding='utf-8')
                credential_files[schema][kind + 'File'] = str(path)
                if kind == 'runtime': runtime_paths[schema] = path
        spec = importlib.util.spec_from_file_location('deploy_acceptance_release', ROOT / 'scripts/release-manifest.py')
        release = importlib.util.module_from_spec(spec); spec.loader.exec_module(release)
        commit = run_command(['git', 'rev-parse', 'HEAD'], env=env).stdout.strip()
        manifest = synthetic_manifest(release, sources, commit); release.validate(manifest)
        manifest_file = directory / 'UNSIGNED-gate-fixture.json'; roles.write_json(manifest_file, manifest)
        images = directory / 'images.fixture.json'; roles.write_json(images, release.compose_override(manifest))
        project = 'dg-' + run_id + '-primary'
        fixture = directory / 'primary.fixture.yml'; fixture.write_text(fixture_override(project, run_id), encoding='utf-8')
        files = [ROOT / 'docker-compose.yml', ROOT / 'docker-compose.database-roles.yml', images, fixture]
        env = compose_environment(env, files, project, admin_secret, runtime_paths)
        progress('start-owned-postgres')
        cid, port = start_postgres(env, run_id)
        report['primaryContainerId'] = cid
        document = {'admin': {'url': 'jdbc:postgresql://127.0.0.1:' + str(port) + '/' + DATABASE,
                              'user': 'audit_admin', 'passwordFile': str(admin_file)}, 'credentials': credential_files}
        config_file = directory / 'operator.json'; roles.write_json(config_file, document)
        base = roles.config(config_file)
        if not re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/' + DATABASE, base['admin']['url']): raise AuditFailure('UnsafeDatabaseTarget')
        progress('prepare-independent-runner'); roles.prepare()
        roles.runner({**base, 'command': 'provision-empty', 'sql': roles.bootstrap()})
        migrations = []
        for service, schema in contract.services().items():
            progress('migrate-' + service)
            migrations.append(roles.runner({**base, 'command': 'migrate', 'schema': schema, 'directory': str(ROOT / 'services' / service / 'src/main/resources/db/migration')}))
        if sum(value['migrationsExecuted'] for value in migrations) != len(sources): raise AuditFailure('MigrationExecutionCountMismatch')
        report['migrations'] = migrations
        roles.runner({**base, 'command': 'sql', 'sql': "INSERT INTO system.system_config(config_key,config_value,description) VALUES ('audit.deploy-gate." + run_id + "','unchanged sentinel','disposable gate acceptance only')"})
        snapshot = roles.runner({**base, 'command': 'inventory'}); roles.check_owned_inventory(snapshot)
        roles.runner({**base, 'command': 'sql', 'sql': 'BEGIN;\n' + contract.grants_sql(snapshot) + '\nCOMMIT;'})
        progress('fresh-real-readiness-receipt')
        receipt = roles.readiness_receipt(base)
        receipt_file = directory / 'readiness.json'; roles.write_json(receipt_file, receipt)
        report['readinessReceiptSha256'] = hashlib.sha256(receipt_file.read_bytes()).hexdigest()
        report['runtimeVerifiedSchemas'] = sorted(receipt['verification']['verified'])
        baseline = fingerprint(base, cid, env); report['beforeFingerprint'] = baseline
        model = compose_model(env)
        progress('positive-live-gate-and-compose-identity')
        record = directory / 'positive.record.json'
        code, category, observation, failed_function = invoke_gate(env, runner, manifest_file, config_file, receipt_file, model, record)
        if code != 0 or not record.exists(): raise AuditFailure('PositiveGateRejected')
        if observation['compose_identity'] != 1 or observation['runner'] < 1 or observation['verify_receipt'] < 1:
            raise AuditFailure('PositiveGateDidNotPerformLiveIdentityChecks')
        admitted = roles.read_json(record)
        identity = {key: snapshot[key] for key in ('systemIdentifier', 'databaseOid', 'database')}
        if admitted['status'] != 'verified-not-deployed' or admitted['databaseIdentity'] != identity or admitted['sourceHashes'] != sources or admitted['images'] != manifest['images']:
            raise AuditFailure('PositiveRecordBindingMismatch')
        report['positiveRecordSha256'] = hashlib.sha256(record.read_bytes()).hexdigest()
        report['databaseIdentity'] = admitted['databaseIdentity']
        report['checks'].append({'case': 'positive-current-sources-11-images-live-receipt-compose-identity', 'exitCode': code, 'passed': True, 'migrationSourceCount': len(sources), 'observedCalls': observation})
        if fingerprint(base, cid, env) != baseline: raise AuditFailure('PositiveGateMutatedDatabase')

        def negative(name, *, case_manifest=manifest_file, case_receipt=receipt_file, case_model=None, case_env=None, before_live=False):
            progress('reject-' + name)
            path = directory / (name + '.record.json')
            code, category, observation, failed_function = invoke_gate(case_env or env, runner, case_manifest, config_file, case_receipt, model if case_model is None else case_model, path)
            unchanged = fingerprint(base, cid, env) == baseline
            item = {'case': name, 'exitCode': code, 'errorClass': category, 'noVerifiedRecord': not path.exists(), 'databaseUnchanged': unchanged,
                    'observedCalls': observation, 'failureFunction': failed_function}
            item['passed'] = code != 0 and not path.exists() and unchanged and category is not None
            if before_live:
                item['rejectedBeforeLiveAccess'] = all(value == 0 for value in observation.values()) and failed_function == 'verify_wiring'
                item['passed'] = item['passed'] and item['rejectedBeforeLiveAccess']
            if name == 'actual-compose-different-database': item['passed'] = item['passed'] and observation['compose_identity'] == 1
            report['checks'].append(item)
            if not item['passed']: raise AuditFailure('NegativeGateAcceptedOrMutatedDatabase')

        bad = copy.deepcopy(manifest); bad['schemaVersion'] = 1; bad.pop('databaseSources')
        bad_file = directory / 'schema1.json'; roles.write_json(bad_file, bad); negative('schema1', case_manifest=bad_file)
        negative('missing-receipt', case_receipt=directory / 'missing.json')
        bad = copy.deepcopy(receipt); bad['verifiedAtUtc'] = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()
        bad_file = directory / 'expired.json'; roles.write_json(bad_file, bad); negative('expired-receipt', case_receipt=bad_file)
        secret = runtime_paths['auth']; original = secret.read_bytes()
        try:
            secret.write_text(secrets.token_urlsafe(36), encoding='utf-8'); negative('changed-runtime-secret')
        finally: secret.write_bytes(original)
        bad = copy.deepcopy(manifest); bad['databaseSources'][next(iter(sources))] = '0' * 64
        bad_file = directory / 'source-mismatch.json'; roles.write_json(bad_file, bad); negative('changed-source-binding', case_manifest=bad_file)
        bad = copy.deepcopy(receipt); bad['bindings']['sourceHashes'][next(iter(sources))] = '0' * 64; bad['bindingsSha256'] = contract.digest(bad['bindings'])
        bad_file = directory / 'receipt-source-mismatch.json'; roles.write_json(bad_file, bad); negative('receipt-source-mismatch', case_receipt=bad_file)

        def altered_model(name, override):
            path = directory / (name + '.override.json'); roles.write_json(path, override)
            changed_env = dict(env, COMPOSE_FILE=os.pathsep.join(str(p) for p in [*files, path]))
            return compose_model(changed_env)
        negative('shared-database-account', case_model=altered_model('shared', {'services': {'auth-service': {'environment': {'DB_USER': 'aetheris', 'SPRING_DATASOURCE_USERNAME': 'aetheris'}}}}))
        no_roles = dict(env, COMPOSE_FILE=os.pathsep.join(str(p) for p in [files[0], images, fixture]))
        negative('missing-roles-override', case_model=compose_model(no_roles))
        negative('wrong-image-digest', case_model=altered_model('digest', {'services': {'auth-service': {'image': release.PREFIX + '-auth@sha256:' + 'f' * 64}}}))
        negative('flyway-enabled', case_model=altered_model('flyway', {'services': {'quality-service': {'environment': {'SPRING_FLYWAY_ENABLED': 'true'}}}}))
        alternate = directory / 'alternate.runtime.password'; alternate.write_bytes(runtime_paths['auth'].read_bytes())
        negative('different-secret-file-same-value', case_model=altered_model('secret', {'secrets': {'db_auth_runtime': {'file': str(alternate)}}}))
        negative('jvm-datasource-override', case_model=altered_model('jvm', {'services': {'auth-service': {'environment': {'JAVA_TOOL_OPTIONS': '-Dspring.datasource.url=jdbc:postgresql://other/other'}}}}))
        for name, key, value in configuration_mutations():
            negative(name, case_model=altered_model(name, {'services': {'auth-service': {'environment': {key: value}}}}), before_live=True)

        progress('start-second-owned-compose-database')
        other_project = 'dg-' + run_id + '-other'
        other_fixture = directory / 'other.fixture.yml'; other_fixture.write_text(fixture_override(other_project, run_id), encoding='utf-8')
        other_env = compose_environment(env, [files[0], files[1], images, other_fixture], other_project, secrets.token_urlsafe(36), runtime_paths, database='aetheris_deploy_other')
        other_cid, unused_port = start_postgres(other_env, run_id); report['otherContainerId'] = other_cid
        # Operator config/receipt/model remain the verified original. The actual
        # Compose context selects a second live cluster: the gate itself must discover this.
        negative('actual-compose-different-database', case_env=other_env)
        final = fingerprint(base, cid, env); report['afterFingerprint'] = final
        report['databaseUnchanged'] = final == baseline
        report['sourceHashesMatchAtEnd'] = source_hashes() == report['sourceHashes']
        if not report['databaseUnchanged'] or not report['sourceHashesMatchAtEnd']: raise AuditFailure('FinalSourceOrDatabaseChanged')
        report['status'] = 'passed'
    except Exception as exc:
        failure = exc; report.update(failureType=type(exc).__name__, failedStage=stage)
        if isinstance(exc, AuditFailure): report['failureCode'] = str(exc)
    finally:
        if base is not None:
            try: report['migrationCredentialsRetired'] = roles.runner({**base, 'command': 'retire'})['retired']
            except Exception: report['migrationCredentialsRetired'] = False
        else: report['migrationCredentialsRetired'] = True
        try: report['cleanup'] = cleanup(run_id, directory, env or original_env)
        except Exception as exc: report['cleanup'] = {'status': 'failed', 'failureType': type(exc).__name__}
        roles.RUNNER = original_runner
        os.environ.clear(); os.environ.update(original_env)
        if not report['cleanup'] or not all(value is True for value in report['cleanup'].values()) or not report['migrationCredentialsRetired']: report['status'] = 'failed'
        report['completedAtUtc'] = utc(); roles.write_json(output, report)
        print('Audit ' + report['status'] + '; sanitized report: ' + str(output), flush=True)
    if failure is not None or report['status'] != 'passed': raise AuditFailure('DeployGateAcceptanceFailed') from None
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--output'); args = parser.parse_args()
    acceptance(args.output)


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print('Acceptance failed: ' + type(error).__name__ + '; sensitive output withheld', file=sys.stderr)
        sys.exit(1)

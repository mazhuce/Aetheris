#!/usr/bin/env python3
"""Owned Auth/System/two-Gateway + Chromium acceptance; buffers real HTTP before browser cookie installation."""
from __future__ import annotations
import argparse
import hashlib
import http.client
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('browser_runtime_identity', ROOT / 'scripts/audit-identity-chain.py')
identity = importlib.util.module_from_spec(spec); spec.loader.exec_module(identity)
base = identity.base
identity.OUTPUT = ROOT / 'output/audit-browser-session-runtime'
identity.LABEL = 'aetheris.browser-runtime.audit'
REQUIRED = frozenset(('ui-login-two-tabs', 'two-tab-refresh-single-rotation', 'consumed-refresh-denied',
    'late-refresh-after-logout-stays-revoked', 'late-active-A-refresh-cannot-replace-B',
    'late-A-logout-cannot-clear-B', 'late-invalid-logout-cannot-clear-B', 'late-unconfirmed-logout-cannot-clear-B',
    'old-family-logout-does-not-revoke-B',
    'revocation-survives-second-gateway-and-restart', 'redis-down-logout',
    'database-down-unconfirmed-ui-and-retry', 'expired-access-and-consumed-refresh-still-logout',
    'account-approval-versions-unchanged'))
identity.REQUIRED_CASES = REQUIRED


def sources():
    result = identity.source_hashes()
    paths = [Path(__file__), ROOT / 'scripts/security/browser-session-runtime.cjs',
             ROOT / 'apps/web/package.json', ROOT / 'apps/web/package-lock.json',
             ROOT / 'apps/web/vite.config.ts', ROOT / 'apps/web/index.html']
    paths.extend((ROOT / 'apps/web/src').rglob('*'))
    result.update({p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in paths if p.is_file()})
    return result


class Audit(identity.Audit):
    def __init__(self, args):
        super().__init__(args.output)
        self.node = str(Path(args.node).resolve()); self.playwright = str(Path(args.playwright).resolve())
        self.browser = str(Path(args.browser).resolve())
        if not all(Path(p).is_file() for p in (self.node, self.browser)) or not Path(self.playwright, 'package.json').is_file():
            raise ValueError('Explicit installed Node, Playwright package and browser required')
        self.report.update(purpose='isolated-real-browser-family', releaseEligible=False,
            limits='Real browser/Auth/System/two Gateway processes and owned PostgreSQL/Redis. No physical job, approved cron/pipeline execution or whole release claim.')
        self.app_envs = {}; self.app_jars = {}; self.active = {}; self.generations = {}
        self.proxy = None; self.proxy_thread = None; self.proxy_lock = threading.Lock()
        self.held = None; self.hold_next = None; self.selected_gateway = 'gateway'
        self.paused = set(); self.control_secret = self.secret(); self.requests = {}

    def start_apps(self, jars, redis_password):
        self.checkpoint('start-real-auth-system-and-two-gateways')
        slots = {name: base.reserve_port() for name in ('auth', 'system', 'gateway', 'gateway2', 'unused')}
        self.reservations.extend(sock for sock, _ in slots.values())
        ports = {name: port for name, (_, port) in slots.items()}; self.report['ports'].update(ports)
        self.admin_username = 'browser_admin_' + self.run_id[:8]; self.admin_password = self.secret()
        common = identity.clean_environment()
        common.update(SERVER_ADDRESS='127.0.0.1', DB_HOST='127.0.0.1', DB_PORT=str(ports.get('postgres', self.report['ports']['postgres'])),
            DB_NAME='aetheris_api_key_audit', JWT_SECRET=self.secret(), JWT_EXPIRATION_MS='30000', JWT_REFRESH_EXPIRATION_MS='900000',
            REDIS_HOST='127.0.0.1', REDIS_PORT=str(self.report['ports']['redis']), REDIS_PASSWORD=redis_password,
            SPRING_DATA_REDIS_TIMEOUT='2s', SPRING_DATA_REDIS_CONNECT_TIMEOUT='2s',
            AUTH_SERVICE_HOST='127.0.0.1', AUTH_SERVICE_PORT=str(ports['auth']),
            SYSTEM_SERVICE_HOST='127.0.0.1', SYSTEM_SERVICE_PORT=str(ports['system']),
            SPRING_FLYWAY_ENABLED='false', SPRING_DATASOURCE_HIKARI_MINIMUMIDLE='1',
            SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE='4', SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT='2000',
            SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT='1000', SERVER_TOMCAT_THREADS_MAX='20',
            SERVER_TOMCAT_THREADS_MINSPARE='2', AETHERIS_RATE_LIMIT_CAPACITY='1000',
            AETHERIS_SECURITY_REGISTRY_FILE=str(self.private_keys / 'public-registry.json'),
            AETHERIS_CORS_ALLOWED_ORIGINS='http://127.0.0.1:' + str(self.report['ports']['browser']))
        for service in ('METADATA_SERVICE', 'SERVING_SERVICE', 'WORKFLOW_SERVICE', 'QUALITY_SERVICE', 'INGESTION_SERVICE', 'GOVERNANCE_SERVICE', 'AI_AGENT'):
            common[service + '_HOST'] = '127.0.0.1'; common[service + '_PORT'] = str(ports['unused'])
        for name in ('auth', 'system', 'gateway', 'gateway2'):
            service = 'gateway' if name == 'gateway2' else name
            env = common.copy(); env['SERVER_PORT'] = str(ports[name]); key = self.key_manifest['services'][service]
            env.update(AETHERIS_SECURITY_WORKLOAD_KID=key['workloadKid'],
                AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE=str(self.private_keys / key['workloadPrivateFile']))
            if service == 'gateway':
                env.update(AETHERIS_SECURITY_USER_KID=key['userKid'], AETHERIS_SECURITY_USER_PRIVATE_FILE=str(self.private_keys / key['userPrivateFile']))
            else:
                env.update(DB_USER=base.database_contract.role(service), DB_PASSWORD=self.base['credentials'][service]['runtime'],
                    SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:' + str(self.report['ports']['postgres']) + '/aetheris_api_key_audit?socketTimeout=3&connectTimeout=2')
                for field in ('REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD'): env.pop(field, None)
            if service == 'auth': env.update(INITIAL_ADMIN_USERNAME=self.admin_username, INITIAL_ADMIN_PASSWORD=self.admin_password)
            if service == 'system': env.pop('JWT_SECRET', None)
            self.report.setdefault('configurationDigests', {})[name] = hashlib.sha256(json.dumps(env, sort_keys=True).encode()).hexdigest()
            self.app_envs[name] = env; self.app_jars[name] = jars[service]; slots[name][0].close(); self.launch(name)
        return ports

    def launch(self, name):
        generation = self.generations.get(name, 0) + 1; self.generations[name] = generation
        path = self.folder / (name + '-' + str(generation) + '.log'); log = path.open('wb')
        process = subprocess.Popen([self.java, *identity.FLAGS, '-Xms32m', '-Xmx256m', '-XX:MaxMetaspaceSize=192m',
            '-XX:ReservedCodeCacheSize=64m', '-XX:ActiveProcessorCount=2', '-XX:+ExitOnOutOfMemoryError', '-jar', str(self.app_jars[name])],
            cwd=self.folder, env=self.app_envs[name], stdout=log, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.processes.append((name, process, log, path)); self.active[name] = process
        record = {'service': name, 'generation': generation, 'pid': process.pid, 'health': 'starting'}
        self.report.setdefault('processes', []).append(record)
        client = base.HttpClient('http://127.0.0.1:' + str(self.report['ports'][name])); deadline = time.monotonic() + 100
        while True:
            if process.poll() is not None: raise base.AuditFailure(name + ' exited before readiness')
            try:
                status, data = client.request('GET', '/actuator/health')
                if status == 200 and data.get('status') == 'UP': break
            except (OSError, TimeoutError): pass
            if time.monotonic() > deadline: raise base.AuditFailure(name + ' health deadline')
            time.sleep(.4)
        observed = re.search(r'\bwith PID (\d+)\b', path.read_text(encoding='utf-8', errors='replace'))
        if not observed or int(observed.group(1)) != process.pid: raise base.AuditFailure('DirectJavaPidMismatch')
        record.update(health='UP', springPidMatched=True)

    def versions(self):
        return self.sql("SELECT json_build_object('uid',id,'accessVersion',access_version,'sessionVersion',session_version) FROM auth.auth_user WHERE username='" + self.admin_username + "'", True)

    def control(self, data):
        op = data.get('op')
        if op == 'state':
            with self.proxy_lock:
                return {'held': None if self.held is None else {'path': self.held['path'], 'status': self.held['status']}, 'requests': dict(self.requests)}
        if op == 'hold':
            if data.get('path') not in ('/api/auth/refresh', '/api/auth/logout'): raise ValueError('Invalid held path')
            with self.proxy_lock:
                if self.held or self.hold_next: raise ValueError('Already holding')
                self.hold_next = data['path']
            return {'armed': True}
        if op == 'release':
            with self.proxy_lock:
                if self.held: self.held['release'].set()
            return {'released': True}
        if op == 'gateway':
            if data.get('name') not in ('gateway', 'gateway2'): raise ValueError('Invalid Gateway')
            self.selected_gateway = data['name']; return {'selected': self.selected_gateway}
        if op == 'restart':
            name = data.get('name')
            if name not in ('auth', 'gateway', 'gateway2'): raise ValueError('Invalid restart')
            process = self.active[name]
            if not any(owner == name and tracked is process for owner, tracked, _, _ in self.processes):
                raise base.AuditFailure('ProcessOwnershipMismatch')
            process.terminate(); process.wait(timeout=8); self.launch(name)
            return {'restarted': name}
        if op in ('pause', 'unpause'):
            name = data.get('name')
            if name not in ('postgres', 'redis'): raise ValueError('Invalid fixture')
            cid = self.containers[0 if name == 'postgres' else 1]
            info = json.loads(self.docker('inspect', cid))[0]
            if info.get('Config', {}).get('Labels', {}).get(identity.LABEL) != self.run_id: raise base.AuditFailure('FixtureOwnershipMismatch')
            if op == 'unpause' and cid not in self.paused: return {'fixture': name, 'paused': False}
            self.docker(op, cid)
            if op == 'pause': self.paused.add(cid)
            else: self.paused.discard(cid)
            return {'fixture': name, 'paused': op == 'pause'}
        if op == 'versions': return self.versions()
        raise ValueError('Unknown control')

    def start_proxy(self):
        audit = self; dist = (ROOT / 'apps/web/dist').resolve()
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self): self.handle_request()
            def do_POST(self): self.handle_request()
            def do_PUT(self): self.handle_request()
            def do_DELETE(self): self.handle_request()
            def send(self, status, raw, headers=()):
                self.send_response(status)
                for key, value in headers:
                    if key.lower() not in ('connection', 'transfer-encoding', 'content-length', 'content-encoding'): self.send_header(key, value)
                self.send_header('Content-Length', str(len(raw))); self.send_header('Connection', 'close'); self.end_headers(); self.wfile.write(raw)
            def handle_request(self):
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if length < 0 or length > 512 * 1024: raise ValueError('Invalid length')
                    body = self.rfile.read(length)
                    if self.path == '/__audit_control':
                        if self.headers.get('X-Audit-Control') != audit.control_secret: return self.send(403, b'{}')
                        result = audit.control(json.loads(body)); return self.send(200, json.dumps(result).encode(), [('Content-Type', 'application/json')])
                    if self.path.startswith('/api/'):
                        with audit.proxy_lock:
                            audit.requests[self.path] = audit.requests.get(self.path, 0) + 1
                            hold = audit.hold_next == self.path
                            if hold: audit.hold_next = None
                        conn = http.client.HTTPConnection('127.0.0.1', audit.report['ports'][audit.selected_gateway], timeout=45)
                        try:
                            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection', 'accept-encoding', 'content-length')}
                            conn.request(self.command, self.path, body or None, headers)
                            response = conn.getresponse(); raw = response.read(2 * 1024 * 1024 + 1)
                            if len(raw) > 2 * 1024 * 1024: raise ValueError('Oversize response')
                            response_headers = response.getheaders(); status = response.status
                        finally: conn.close()
                        if hold:
                            held = {'path': self.path, 'status': status, 'release': threading.Event()}
                            with audit.proxy_lock: audit.held = held
                            if not held['release'].wait(90): raise ValueError('Held response deadline')
                            with audit.proxy_lock: audit.held = None
                        return self.send(status, raw, response_headers)
                    if self.command != 'GET': return self.send(405, b'{}')
                    relative = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path).lstrip('/')
                    target = (dist / relative).resolve()
                    if target != dist and dist not in target.parents: return self.send(403, b'{}')
                    if not target.is_file(): target = dist / 'index.html'
                    import mimetypes
                    self.send(200, target.read_bytes(), [('Content-Type', mimetypes.guess_type(target.name)[0] or 'application/octet-stream'), ('Cache-Control', 'no-store')])
                except (BrokenPipeError, ConnectionResetError): pass
                except Exception as error:
                    audit.report.setdefault('proxyErrors', []).append(type(error).__name__)
                    try: self.send(502, b'{"message":"isolated proxy failure"}')
                    except OSError: pass
        self.proxy = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.report['ports']['browser'] = self.proxy.server_address[1]
        self.proxy_thread = threading.Thread(target=self.proxy.serve_forever, daemon=True); self.proxy_thread.start()

    def browser_tests(self):
        self.checkpoint('chromium-real-cookie-and-service-fault-cases')
        before = self.versions()
        config = {'origin': 'http://127.0.0.1:' + str(self.report['ports']['browser']),
            'username': self.admin_username, 'password': self.admin_password, 'runId': self.run_id,
            'controlSecret': self.control_secret, 'playwright': self.playwright, 'executable': self.browser}
        raw = self.command([self.node, str(ROOT / 'scripts/security/browser-session-runtime.cjs')], stdin=json.dumps(config), timeout=520)
        data = json.loads(raw)
        self.report['browser'] = data
        (self.folder / 'browser.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        self.report['checks'] = data['checks']
        self.report['checks'].append({'case': 'account-approval-versions-unchanged', 'passed': before == self.versions(), 'before': before, 'after': self.versions()})
        if data.get('closed') is not True or any(c.get('passed') is not True for c in self.report['checks']):
            raise base.AuditFailure('RealBrowserCaseFailed')

    def cleanup(self):
        for cid in list(self.paused):
            try: self.docker('unpause', cid); self.paused.remove(cid)
            except Exception: pass
        if self.held: self.held['release'].set()
        if self.proxy:
            self.proxy.shutdown(); self.proxy.server_close(); self.proxy_thread.join(timeout=5)
        super().cleanup()

    def run(self):
        self.report['sourceHashes'] = sources(); self.report['migrationSourceHashes'] = base.database_contract.source_manifest()
        try:
            self.java = base.resolve_java21()
            self.report['runtimes'] = {'java': self.java, 'node': self.node, 'browser': self.browser,
                'browserSha256': hashlib.sha256(Path(self.browser).read_bytes()).hexdigest()}
            self.checkpoint('fresh-web-artifact')
            log = self.command([self.node, str(ROOT / 'apps/web/node_modules/vite/bin/vite.js'), 'build', str(ROOT / 'apps/web')], timeout=180)
            (self.folder / 'web-build.log').write_text(log, encoding='utf-8')
            self.report['webArtifacts'] = {p.relative_to(ROOT / 'apps/web/dist').as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in (ROOT / 'apps/web/dist').rglob('*') if p.is_file()}
            redis = self.setup_containers(); self.setup_database(); self.generate_keys(); jars = self.build()
            self.start_proxy(); self.start_apps(jars, redis); self.browser_tests()
            if sources() != self.report['sourceHashes']: raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd'] = True; self.report['status'] = 'passed'
        except (Exception, KeyboardInterrupt) as error:
            self.report['status'] = 'failed'; self.report['failure'] = {'type': type(error).__name__,
                'message': base.redact(str(error), self.secrets) if isinstance(error, base.AuditFailure) else 'Operation failed; inspect sanitized evidence'}
        finally:
            self.report['sourceHashesMatchAtEnd'] = sources() == self.report['sourceHashes']
            self.cleanup()
        return self.report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('output', 'node', 'playwright', 'browser'): parser.add_argument('--' + name, required=True)
    report = Audit(parser.parse_args()).run()
    print('Browser runtime ' + report['status'] + '; checks=' + str(len(report['checks'])) + '; cleanup=' + str(report['cleanup']))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__': sys.exit(main())

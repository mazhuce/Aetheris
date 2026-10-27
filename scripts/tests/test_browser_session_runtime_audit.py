"""Harness safety only: mocked tools and one owned loopback HTTP fixture, never platform acceptance."""
import copy
import http.client
import http.server
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('browser_runtime_safety_under_test', ROOT / 'scripts/audit-browser-session-runtime.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

EXPECTED_CASES = frozenset((
    'ui-login-two-tabs', 'two-tab-refresh-single-rotation', 'consumed-refresh-denied',
    'late-refresh-after-logout-stays-revoked', 'late-active-A-refresh-cannot-replace-B',
    'late-A-logout-cannot-clear-B', 'late-invalid-logout-cannot-clear-B',
    'late-unconfirmed-logout-cannot-clear-B', 'old-family-logout-does-not-revoke-B',
    'revocation-survives-second-gateway-and-restart', 'redis-down-logout',
    'database-down-unconfirmed-ui-and-retry', 'expired-access-and-consumed-refresh-still-logout',
    'account-approval-versions-unchanged',
))
EXPECTED_CLEANUP = frozenset(('processes', 'containers', 'credentialsRetired', 'privateKeysRemoved', 'volumesAbsent'))


class BrowserRuntimeSafetyTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'output').mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='browser-runtime-safety-', dir=ROOT / 'output')
        self.temp = Path(temporary.name).resolve()
        self.assertEqual((ROOT / 'output').resolve(), self.temp.parent)
        self.addCleanup(temporary.cleanup)
        # A missed mock must fail, not launch Docker, Java, Node, Maven or a browser.
        for name in ('Popen', 'run'):
            blocker = patch.object(audit.subprocess, name, side_effect=AssertionError('External tools forbidden in safety tests'))
            blocker.start()
            self.addCleanup(blocker.stop)

    def new_audit(self, filename='result.json'):
        allowed = self.temp / 'reports'
        tools = self.temp / 'synthetic-tools'
        allowed.mkdir(exist_ok=True)
        tools.mkdir(exist_ok=True)
        node, browser = tools / 'node', tools / 'browser'
        for path in (node, browser):
            path.write_text('synthetic fixture, never executable', encoding='utf-8')
        playwright = tools / 'playwright'
        playwright.mkdir(exist_ok=True)
        (playwright / 'package.json').write_text('{}', encoding='utf-8')
        with patch.object(audit.identity, 'OUTPUT', allowed):
            return audit.Audit(SimpleNamespace(output=allowed / filename, node=node, browser=browser, playwright=playwright))

    def candidate(self):
        # An in-memory validator candidate, never a claimed browser run or image acceptance artifact.
        return {'status': 'passed', 'sourceHashesMatchAtEnd': True,
                'checks': [{'case': name, 'passed': True} for name in sorted(EXPECTED_CASES)],
                'cleanup': dict.fromkeys(EXPECTED_CLEANUP, True)}

    def control_fixture(self):
        instance = audit.Audit.__new__(audit.Audit)
        instance.run_id = 'b' * 32
        instance.folder = self.temp
        instance.report = {'checks': [], 'ports': {}}
        instance.proxy_lock = threading.Lock()
        instance.held = None
        instance.hold_next = None
        instance.selected_gateway = 'gateway'
        instance.requests = {}
        instance.control_secret = 'synthetic-loopback-control'
        instance.paused = set()
        instance.containers = ['owned-postgres', 'owned-redis']
        instance.active = {}
        instance.processes = []
        instance.generations = {}
        instance.proxy = None
        instance.proxy_thread = None
        instance.local_docker_verified = True
        return instance

    def test_required_case_and_cleanup_sets_keep_the_complete_current_contract(self):
        self.assertEqual(EXPECTED_CASES, audit.REQUIRED)
        self.assertEqual(EXPECTED_CASES, audit.identity.REQUIRED_CASES)
        self.assertEqual(EXPECTED_CLEANUP, audit.identity.REQUIRED_CLEANUP)
        candidate = self.candidate()
        audit.identity.finalize_status(candidate)
        self.assertEqual('passed', candidate['status'])

    def test_missing_duplicate_unknown_or_failed_case_cannot_pass(self):
        good = self.candidate()
        variants = [[], good['checks'] + [good['checks'][0]], good['checks'] + [{'case': 'unknown', 'passed': True}]]
        for index in range(len(good['checks'])):
            variants.append(good['checks'][:index] + good['checks'][index + 1:])
            for failed in (False, None, 1, 'true'):
                changed = copy.deepcopy(good['checks'])
                changed[index]['passed'] = failed
                variants.append(changed)
        for checks in variants:
            with self.subTest(checks=checks):
                candidate = {**copy.deepcopy(good), 'checks': checks}
                audit.identity.finalize_status(candidate)
                self.assertEqual('failed', candidate['status'])

    def test_source_drift_and_each_missing_or_false_cleanup_result_cannot_pass(self):
        good = self.candidate()
        variants = [{**copy.deepcopy(good), 'sourceHashesMatchAtEnd': False}, {**copy.deepcopy(good), 'cleanup': {}}]
        missing_source = copy.deepcopy(good)
        del missing_source['sourceHashesMatchAtEnd']
        variants.append(missing_source)
        for key in EXPECTED_CLEANUP:
            for value in (False, None, 1, 'true'):
                variants.append({**copy.deepcopy(good), 'cleanup': {**good['cleanup'], key: value}})
            variants.append({**copy.deepcopy(good), 'cleanup': {k: v for k, v in good['cleanup'].items() if k != key}})
        for candidate in variants:
            with self.subTest(candidate=candidate):
                audit.identity.finalize_status(candidate)
                self.assertEqual('failed', candidate['status'])
        never_promote = {**good, 'status': 'failed'}
        audit.identity.finalize_status(never_promote)
        self.assertEqual('failed', never_promote['status'])

    def test_report_requires_a_fresh_json_below_the_owned_output_root(self):
        allowed = self.temp / 'allowed'
        allowed.mkdir()
        with patch.object(audit.identity, 'OUTPUT', allowed):
            self.assertEqual((allowed / 'new.json').resolve(), audit.identity.validate_output(allowed / 'new.json'))
            for path in (allowed, allowed / 'bad.txt', self.temp / 'outside.json', allowed / '..' / 'outside.json'):
                with self.subTest(path=path), self.assertRaises(ValueError):
                    audit.identity.validate_output(path)
            old = allowed / 'existing.json'
            old.write_text('preserve old evidence', encoding='utf-8')
            with self.assertRaises(ValueError):
                audit.identity.validate_output(old)
            self.assertEqual('preserve old evidence', old.read_text(encoding='utf-8'))

    def test_checkpoint_writes_only_owned_progress_and_does_not_touch_explicit_output(self):
        instance = self.new_audit()
        instance.checkpoint('fixture-stage')
        self.assertTrue((instance.folder / 'progress.json').is_file())
        self.assertFalse(instance.output.exists(), 'A progress checkpoint must not create final evidence')
        instance.output.write_text('concurrently created evidence', encoding='utf-8')
        instance.checkpoint('next-fixture-stage')
        self.assertEqual('concurrently created evidence', instance.output.read_text(encoding='utf-8'))

    def test_final_report_creation_never_overwrites_a_file_created_after_validation(self):
        instance = self.new_audit()
        instance.output.write_text('concurrently created evidence', encoding='utf-8')
        instance.report.update(self.candidate())
        instance.cleanup()
        self.assertEqual('concurrently created evidence', instance.output.read_text(encoding='utf-8'))
        self.assertEqual('failed', instance.report['status'])
        self.assertEqual('EvidenceOutputConflict', instance.report['failure']['type'])
        archived = json.loads((instance.folder / 'report.json').read_text(encoding='utf-8'))
        self.assertEqual('failed', archived['status'])
        self.assertEqual('EvidenceOutputConflict', archived['failure']['type'])

    def test_owned_run_report_is_also_exclusively_created(self):
        instance = self.new_audit()
        local = instance.folder / 'report.json'
        local.write_text('preserve run evidence', encoding='utf-8')
        with self.assertRaises((FileExistsError, ValueError, audit.base.AuditFailure)):
            instance.cleanup()
        self.assertEqual('preserve run evidence', local.read_text(encoding='utf-8'))

    def run_fixture(self, checks, *, drift=False, stubborn_process=False):
        instance = self.new_audit()
        dist = self.temp / 'apps/web/dist'
        dist.mkdir(parents=True)
        (dist / 'index.html').write_text('synthetic web fixture', encoding='utf-8')
        instance.checkpoint = Mock()
        instance.command = Mock(return_value='synthetic command result; no tool executed')
        for name in ('setup_containers', 'setup_database', 'generate_keys', 'start_proxy', 'start_apps'):
            setattr(instance, name, Mock())
        instance.build = Mock(return_value={})
        instance.browser_tests = lambda: instance.report.update(checks=copy.deepcopy(checks))
        if stubborn_process:
            process = Mock(pid=98765)
            process.poll.return_value = None
            process.wait.side_effect = TimeoutError('synthetic process did not exit')
            path = instance.folder / 'stubborn.log'
            path.write_text('synthetic log', encoding='utf-8')
            instance.processes.append(('auth', process, Mock(), path))
        before = {'fixture-source': 'a' * 64}
        after = {'fixture-source': 'b' * 64} if drift else before
        with patch.object(audit, 'ROOT', self.temp), patch.object(audit, 'sources', side_effect=[before, after, after]), \
                patch.object(audit.base, 'resolve_java21', return_value='never-executed-java'), \
                patch.object(audit.base.database_contract, 'source_manifest', return_value={}):
            return instance.run()

    def test_actual_run_finalization_rejects_incomplete_or_duplicate_browser_results(self):
        cases = self.candidate()['checks']
        # Each run has its own fresh filesystem fixture; do not reuse a result destination.
        for checks in (cases[:-1], cases + [cases[0]]):
            with self.subTest(checks=checks):
                with tempfile.TemporaryDirectory(prefix='nested-run-', dir=self.temp) as folder:
                    with patch.object(self, 'temp', Path(folder)):
                        result = self.run_fixture(checks)
                self.assertEqual('failed', result['status'])

    def test_actual_run_rejects_source_drift_even_with_complete_successful_case_results(self):
        result = self.run_fixture(self.candidate()['checks'], drift=True)
        self.assertEqual('failed', result['status'])
        self.assertFalse(result['sourceHashesMatchAtEnd'])
        self.assertEqual('SourceChangedDuringRun', result['failure']['message'])

    def test_actual_cleanup_failure_downgrades_a_previously_successful_candidate(self):
        result = self.run_fixture(self.candidate()['checks'], stubborn_process=True)
        self.assertFalse(result['cleanup']['processes'])
        self.assertEqual('failed', result['status'])

    def test_browser_not_closed_or_a_failed_case_is_rejected_before_finalization(self):
        checks = [item for item in self.candidate()['checks'] if item['case'] != 'account-approval-versions-unchanged']
        failed = copy.deepcopy(checks)
        failed[0]['passed'] = False
        for data in ({'closed': False, 'checks': checks}, {'closed': True, 'checks': failed}):
            instance = self.new_audit('browser-' + str(data['closed']) + '.json')
            instance.checkpoint = Mock()
            instance.versions = Mock(return_value={'accessVersion': 8, 'sessionVersion': 3})
            instance.admin_username = 'synthetic-admin'
            instance.admin_password = 'synthetic-password'
            instance.report['ports'] = {'browser': 49152}
            instance.command = Mock(return_value=json.dumps(data))
            with self.assertRaises(audit.base.AuditFailure):
                instance.browser_tests()
            self.assertNotEqual('passed', instance.report['status'])

    def test_control_rejects_external_paths_ids_and_unrecognized_targets_without_any_tool_call(self):
        instance = self.control_fixture()
        instance.docker = Mock(side_effect=AssertionError('No Docker command expected'))
        instance.launch = Mock(side_effect=AssertionError('No process launch expected'))
        for op in ('pause', 'unpause', 'restart', 'gateway'):
            for name in ('../auth', '/usr/bin/java', 'C:\\Windows\\System32\\cmd.exe', 'external-container-id', 'system'):
                with self.subTest(op=op, name=name), self.assertRaises(ValueError):
                    instance.control({'op': op, 'name': name})
        for path in ('../outside', '/api/auth/login', 'http://example.invalid/api/auth/refresh'):
            with self.assertRaises(ValueError):
                instance.control({'op': 'hold', 'path': path})
        instance.docker.assert_not_called()
        instance.launch.assert_not_called()

    def test_pause_and_unpause_require_the_exact_owned_container_label(self):
        for labels in ({}, {audit.identity.LABEL: 'other-run'}, {'unrelated-label': 'b' * 32}):
            instance = self.control_fixture()
            instance.docker = Mock(return_value=json.dumps([{'Config': {'Labels': labels}}]))
            for op in ('pause', 'unpause'):
                with self.subTest(op=op, labels=labels), self.assertRaises(audit.base.AuditFailure):
                    instance.control({'op': op, 'name': 'postgres'})
            self.assertTrue(all(call.args[0] == 'inspect' for call in instance.docker.call_args_list))
        instance = self.control_fixture()
        instance.docker = Mock(side_effect=lambda *args: json.dumps([{'Config': {'Labels': {audit.identity.LABEL: instance.run_id}}}]) if args[0] == 'inspect' else '')
        instance.control({'op': 'pause', 'name': 'redis'})
        self.assertEqual({'owned-redis'}, instance.paused)
        instance.control({'op': 'unpause', 'name': 'redis'})
        self.assertEqual(set(), instance.paused)
        self.assertEqual([('inspect', 'owned-redis'), ('pause', 'owned-redis'), ('inspect', 'owned-redis'), ('unpause', 'owned-redis')],
                         [call.args for call in instance.docker.call_args_list])

    def test_restart_only_signals_the_owned_tracked_process_for_the_selected_service(self):
        instance = self.control_fixture()
        owned = Mock(pid=98765)
        instance.active['auth'] = owned
        instance.processes = [('auth', owned, Mock(), self.temp / 'auth.log')]
        instance.launch = Mock()
        instance.control({'op': 'restart', 'name': 'auth', 'pid': 1, 'path': '../external.jar'})
        owned.terminate.assert_called_once_with()
        owned.wait.assert_called_once_with(timeout=8)
        instance.launch.assert_called_once_with('auth')
        untracked = Mock(pid=87654)
        instance.active['auth'] = untracked
        instance.launch.reset_mock()
        with self.assertRaises((ValueError, audit.base.AuditFailure)):
            instance.control({'op': 'restart', 'name': 'auth'})
        untracked.terminate.assert_not_called()
        instance.launch.assert_not_called()

    def test_proxy_buffers_headers_and_set_cookie_until_explicit_release(self):
        instance = self.control_fixture()
        class Upstream(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                self.send_response(200)
                self.send_header('Set-Cookie', 'synthetic_access=held; Path=/; HttpOnly')
                self.send_header('Set-Cookie', 'synthetic_refresh=held; Path=/api/auth; HttpOnly')
                self.send_header('Content-Length', '2')
                self.end_headers()
                self.wfile.write(b'{}')
        upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        thread = threading.Thread(target=upstream.serve_forever, daemon=True)
        thread.start()
        def stop_servers():
            if instance.held:
                instance.held['release'].set()
            if instance.proxy:
                instance.proxy.shutdown(); instance.proxy.server_close(); instance.proxy_thread.join(timeout=3)
            upstream.shutdown(); upstream.server_close(); thread.join(timeout=3)
        self.addCleanup(stop_servers)
        instance.report['ports']['gateway'] = upstream.server_address[1]
        with patch.object(audit, 'ROOT', self.temp):
            instance.start_proxy()
        instance.control({'op': 'hold', 'path': '/api/auth/refresh'})
        client = socket.create_connection(('127.0.0.1', instance.report['ports']['browser']), timeout=3)
        self.addCleanup(client.close)
        client.sendall(b'POST /api/auth/refresh HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
        deadline = time.monotonic() + 3
        while instance.held is None and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertIsNotNone(instance.held, 'The actual upstream response must be held before checking header visibility')
        client.settimeout(.1)
        with self.assertRaises(socket.timeout):
            client.recv(1)
        instance.control({'op': 'release'})
        client.settimeout(3)
        response = b''
        while True:
            part = client.recv(4096)
            if not part:
                break
            response += part
            self.assertLess(len(response), 8192)
        self.assertIn(b' 200 ', response.split(b'\r\n', 1)[0])
        self.assertEqual(2, response.lower().count(b'\r\nset-cookie:'))
        self.assertIn(b'synthetic_access=held', response)
        self.assertIn(b'synthetic_refresh=held', response)
        self.assertTrue(response.endswith(b'{}'))


if __name__ == '__main__':
    unittest.main()

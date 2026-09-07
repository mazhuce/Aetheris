"""Local M0 HTTP health acceptance; credentials come only from environment."""
import importlib.util
import json
import os
import pathlib
import secrets
import time
from concurrent.futures import ThreadPoolExecutor

root = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('spark_smoke', root / 'scripts/spark-smoke-test.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
api = smoke.Api('http://127.0.0.1:8080', timeout=45)
api.token = api.login(os.environ['INITIAL_ADMIN_USERNAME'], os.environ['INITIAL_ADMIN_PASSWORD'])['token']
report = {'checks': [], 'temporaryDatasourceId': None}
def passed(name, **values):
    report['checks'].append({'name': name, 'status': 'passed', **values})
    print('PASS ' + name, flush=True)
for identifier in (5, 6):
    path = f'/api/metadata/datasources/{identifier}'
    result = api.request('POST', path + '/test')
    assert result['success'] is True and result['connectionHealth']['status'] == 'available', 'Spark probe unavailable'
    snapshot = result['connectionHealth']
    assert snapshot['checkedAt'] and snapshot['lastSuccessAt'] and snapshot['expiresAt']
    for _ in range(2):
        assert api.request('GET', path)['connectionHealth'] == snapshot, 'read unexpectedly mutated observation'
    passed('persisted_spark_probe_and_read_without_reprobe', datasourceId=identifier, health=snapshot)

payload = {'name': 'spark-m0-health-' + secrets.token_hex(6), 'type': 'spark', 'host': '127.0.0.1',
           'port': 9, 'databaseName': 'default', 'username': 'aetheris', 'status': 'active',
           'connectionRevision': 999, 'connectionHealth': {'status': 'available'}}
identifier = None
try:
    identifier = api.request('POST', '/api/metadata/datasources', payload)['id']
    report['temporaryDatasourceId'] = identifier
    path = f'/api/metadata/datasources/{identifier}'
    assert api.request('GET', path)['connectionHealth']['status'] == 'unknown'
    passed('stored_active_and_client_health_are_not_live_success')
    result = api.request('POST', path + '/test')
    assert result['success'] is False and result['connectionHealth']['status'] == 'unavailable'
    assert result['failureCode'] in ('CONNECTION_REFUSED', 'CONNECTION_FAILED')
    assert 'jdbc:' not in json.dumps(result) and '127.0.0.1' not in json.dumps(result)
    passed('closed_port_has_safe_dependency_failure', health=result['connectionHealth'])
    payload.update(host='172.18.160.1', port=10000)
    updated = api.request('PUT', path, payload)
    assert updated['connectionHealth']['status'] == 'unknown'
    assert updated['connectionHealth'].get('lastSuccessAt') is None
    result = api.request('POST', path + '/test')
    assert result['success'] is True
    before = result['connectionHealth']
    payload['description'] = 'M0 temporary connection-health fixture'
    assert api.request('PUT', path, payload)['connectionHealth'] == before
    passed('connection_change_invalidates_and_description_preserves_observation')
    # Reserve an unresponsive documentation address for a bounded concurrent probe.
    payload['host'] = '192.0.2.1'
    api.request('PUT', path, payload)
    with ThreadPoolExecutor(max_workers=1) as executor:
        pending = executor.submit(api.request, 'POST', path + '/test')
        for _ in range(40):
            observed = api.request('GET', path)['connectionHealth']
            if observed['status'] == 'checking':
                break
            if pending.done():
                raise AssertionError('probe completed before fencing test could observe checking')
            time.sleep(0.05)
        assert observed['status'] == 'checking'
        busy = api.request('POST', path + '/test')
        assert busy['success'] is False and busy['failureCode'] == 'PROBE_BUSY'
        payload['host'] = '172.18.160.1'
        api.request('PUT', path, payload)
        superseded = pending.result(timeout=35)
        assert superseded['failureCode'] == 'PROBE_SUPERSEDED'
        assert api.request('GET', path)['connectionHealth']['status'] == 'unknown'
    assert api.request('POST', path + '/test')['success'] is True
    passed('concurrent_probe_is_single_and_old_configuration_completion_is_fenced')
    report['status'] = 'passed'
finally:
    if identifier:
        api.request('DELETE', f'/api/metadata/datasources/{identifier}', expected=(204,))
        report['temporaryDatasourceCleanup'] = 'removed'
    with (root / 'artifacts/spark-m0-health-2026-09-07.json').open('w', encoding='utf-8') as output:
        json.dump(report, output, ensure_ascii=False, indent=2)

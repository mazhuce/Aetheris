"""Offline SQL policy. No connection or credential side effects on import."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def services():
    return json.loads((ROOT / 'configs/database/services.json').read_text(encoding='utf-8'))['services']


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')).hexdigest()


def ident(value):
    if not isinstance(value, str) or not value or '\0' in value or len(value.encode('utf-8')) > 63:
        raise ValueError('Invalid PostgreSQL identifier')
    return '"' + value.replace('"', '""') + '"'


def schema_name(value):
    if value not in services().values():
        raise ValueError('Schema is outside the nine-service contract')
    return value


def role(schema, suffix=''):
    return 'aetheris_' + schema_name(schema) + suffix


def source_manifest(root=ROOT):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.glob('services/*/src/main/resources/db/migration/*.sql'))}


def bootstrap_sql(schema):
    schema_name(schema)
    lines = []
    for name in [role(schema, '_owner'), role(schema, '_migrate'), role(schema)]:
        # Existing roles are verified by the runner before this idempotent block.
        lines.append(f"DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='{name}') THEN CREATE ROLE {ident(name)} NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END $$;")
    lines += [f'CREATE SCHEMA IF NOT EXISTS {ident(schema)} AUTHORIZATION {ident(role(schema, "_owner"))};',
              f'ALTER DEFAULT PRIVILEGES FOR ROLE {ident(role(schema, "_owner"))} REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;']
    return '\n'.join(lines)


FIXED_EXECUTION_HELPERS = {
    'execution_identity_valid': ('value jsonb', 'jsonb', 'boolean'),
    'execution_actor_valid': ('value jsonb', 'jsonb', 'boolean'),
    'execution_approval_valid': ('value jsonb, revision bigint, definition_hash text', 'jsonb, bigint, text', 'boolean'),
    'execution_definition_projection': ('body jsonb', 'jsonb', 'jsonb'),
}
WORKFLOW_PIPELINE_HELPERS = {
    'pipeline_plan_current': ('body jsonb', 'jsonb', 'boolean'),
    'pipeline_success_evidence': ('node_key bigint', 'bigint', 'boolean'),
    'pipeline_retry_ancestor': ('run_key bigint, source_key bigint', 'bigint, bigint', 'boolean'),
    'pipeline_dependencies_ready': ('run_key bigint, node_key bigint', 'bigint, bigint', 'boolean'),
    'pipeline_binding_valid': ('value workflow.execution_attempt', 'workflow.execution_attempt', 'boolean'),
}


def function_policy(item):
    """Return fixed DDL types and whether local runtime EXECUTE is necessary.

    Inventory identity arguments are compared byte-for-byte, never interpolated
    into SQL. Trigger invocation itself does not require an EXECUTE grant.
    """
    schema_name(item.get('schema'))
    if item.get('securityDefiner') is not False:
        raise ValueError('Security definer or unspecified function security is forbidden')
    args = item.get('arguments')
    if args == '' and item.get('resultType') == 'trigger':
        return '', False
    helper = FIXED_EXECUTION_HELPERS.get(item.get('name'))
    if item['schema']=='workflow' and helper is None:
        helper=WORKFLOW_PIPELINE_HELPERS.get(item.get('name'))
    if item['schema'] not in ('workflow', 'ingestion', 'quality') or helper is None:
        raise ValueError('Unreviewed database function')
    identity, types, result = helper
    if args != identity or item.get('resultType') != result:
        raise ValueError('Unreviewed function identity arguments or result type')
    return types, True


def check_function_inventory(functions):
    seen = set()
    for item in functions:
        _, execute = function_policy(item)
        if execute:
            key = item['schema'], item['name']
            if key in seen: raise ValueError('Duplicate reviewed helper')
            seen.add(key)
    expected = {(schema, name) for schema in ('workflow', 'ingestion', 'quality') for name in FIXED_EXECUTION_HELPERS}
    expected.update(('workflow',name) for name in WORKFLOW_PIPELINE_HELPERS)
    if seen != expected: raise ValueError('Missing fixed execution helper functions')


def _objects(before):
    for item in before.get('schemas', []):
        yield 'SCHEMA', ident(schema_name(item['name'])), item, item['name']
    # ALTER TABLE OWNER also changes owned sequences. Transfer their tables
    # first; an owned sequence cannot independently change to another owner.
    for item in sorted(before.get('relations', []), key=lambda i: (i['kind'] == 'S', i['schema'], i['name'])):
        if item['kind'] not in ('r', 'p', 'S'):
            raise ValueError('Unsupported relation kind requires manual review')
        kind = 'SEQUENCE' if item['kind']=='S' else 'TABLE'
        yield kind, ident(schema_name(item['schema'])) + '.' + ident(item['name']), item, item['schema']
    for item in before.get('functions', []):
        types, _ = function_policy(item)
        yield 'FUNCTION', ident(schema_name(item['schema'])) + '.' + ident(item['name']) + '(' + types + ')', item, item['schema']


def grants_sql(before):
    lines = []
    for kind, name, item, schema in _objects(before):
        runtime = ident(role(schema))
        lines.append(f'REVOKE ALL ON {kind} {name} FROM PUBLIC;')
        for other in services().values():
            lines.append(f'REVOKE ALL ON {kind} {name} FROM {ident(role(other))};')
        if kind == 'SCHEMA':
            lines.append(f'GRANT USAGE ON SCHEMA {name} TO {runtime};')
        elif kind == 'SEQUENCE':
            lines.append(f'GRANT USAGE, SELECT ON SEQUENCE {name} TO {runtime};')
        elif kind in ('TABLE', 'VIEW') and item['name'] != 'flyway_schema_history':
            lines.append(f'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE {name} TO {runtime};')
        elif kind == 'MATERIALIZED VIEW':
            lines.append(f'GRANT SELECT ON TABLE {name} TO {runtime};')
        elif kind == 'FUNCTION' and function_policy(item)[1]:
            lines.append(f'GRANT EXECUTE ON FUNCTION {name} TO {runtime};')
    return '\n'.join(lines)


def plan(before):
    if before.get('dependencies') or before.get('extensions') or before.get('types') or before.get('defaultAcls'):
        raise ValueError('Dependencies, extensions, standalone types or custom default ACLs require review')
    objects = list(_objects(before))
    if before.get('roles') or before.get('memberships'):
        raise ValueError('Existing target roles/memberships require review of ownership and per-database role settings')
    for item in before.get('schemas', []):
        history=before.get('history', {}).get(item['name'])
        if not history or any(not row.get('success') for row in history):
            raise ValueError('Missing or failed Flyway history requires manual review')
        if any(row.get('script') == 'V3__service_api_keys.sql' for row in history):
            raise ValueError('Historical API-key V3 cannot be repaired automatically; rebuild into a new database')
        validated=before.get('sourceValidation',{}).get(item['name'],{})
        if not validated.get('validationSuccessful') or validated.get('pending')!=0:
            raise ValueError('Current source must validate all Flyway history with zero pending; unknown scripts/versions/checksums require review')
    # Nondefault ACLs can contain grant chains and third-party consumers. Never
    # flatten these into grants with a different grantor during an auto rollback.
    if before.get('databaseAcl') is not None or any(o[2].get('acl') is not None for o in objects):
        raise ValueError('Existing explicit ACL/grant chains require a reviewed custom plan')
    db = ident(before['database'])
    forward = ['BEGIN;', '-- Database-wide PUBLIC changes require the documented consumer review.',
               f'REVOKE CREATE, TEMPORARY ON DATABASE {db} FROM PUBLIC;',
               'REVOKE CREATE ON SCHEMA public FROM PUBLIC;']
    rollback = ['BEGIN;', f'GRANT TEMPORARY ON DATABASE {db} TO PUBLIC;']
    public = before.get('publicSchema')
    if public and public.get('publicCreate'):
        rollback.append('GRANT CREATE ON SCHEMA public TO PUBLIC;')
    for kind, name, item, schema in objects:
        forward.append(f'ALTER {kind} {name} OWNER TO {ident(role(schema, "_owner"))};')
        rollback += [f'ALTER {kind} {name} OWNER TO {ident(item["owner"])};',
                     f'REVOKE ALL ON {kind} {name} FROM PUBLIC;']
        for other in services().values():
            rollback.append(f'REVOKE ALL ON {kind} {name} FROM {ident(role(other))};')
        if kind == 'FUNCTION': rollback.append(f'GRANT EXECUTE ON FUNCTION {name} TO PUBLIC;')
    for schema in services().values():
        runtime = ident(role(schema))
        forward += [f'GRANT CONNECT ON DATABASE {db} TO {runtime};',
                    f'ALTER ROLE {runtime} IN DATABASE {db} SET search_path = pg_catalog, {ident(schema)};']
        rollback += [f'REVOKE CONNECT ON DATABASE {db} FROM {runtime};',
                     f'ALTER ROLE {runtime} IN DATABASE {db} RESET search_path;']
    forward += [grants_sql(before), 'COMMIT;']
    rollback.append('COMMIT;')
    return {'schemaVersion': 1, 'inventorySha256': digest(before), 'before': before,
            'requiresBackup': True, 'requiresQuiescedServices': True,
            'applySql': '\n'.join(forward), 'rollbackSql': '\n'.join(rollback),
            'limits': ['Role provisioning and login passwords are separate from object-owner rollback.',
                       'Review every other database consumer before revoking PUBLIC TEMP/public CREATE.',
                       'Drain existing runtime sessions; ACL revocation is not an immediate session revocation.',
                       'No Flyway history mutation, repair, or whole-database ownership reassignment.']}

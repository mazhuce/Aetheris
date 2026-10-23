-- Writing requires an explicit independent action; no grant inferred from update/query.
INSERT INTO auth.rbac_permission (code, resource_type, action, name)
VALUES ('datasource.write', 'datasource', 'write', '加工写入数据源')
ON CONFLICT (code) DO NOTHING;
INSERT INTO auth.rbac_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM auth.rbac_role r CROSS JOIN auth.rbac_permission p
WHERE r.code = 'sys_admin' AND p.code = 'datasource.write'
ON CONFLICT DO NOTHING;
UPDATE auth.permission_version SET version = version + 1, updated_at = now() WHERE id = 1;

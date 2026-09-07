-- Metadata already requires datasource.query for SQL, preview and connection
-- probes, but V3 omitted the action from the grant catalog. Explicit read-only
-- grants therefore failed validation even when the execution policy allowed it.
INSERT INTO rbac_permission (code, resource_type, action, name)
VALUES ('datasource.query', 'datasource', 'query', '查询数据源与测试连接')
ON CONFLICT (code) DO NOTHING;

INSERT INTO rbac_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM rbac_role r CROSS JOIN rbac_permission p
WHERE r.code = 'sys_admin' AND p.code = 'datasource.query'
ON CONFLICT DO NOTHING;

-- Scoped viewer grants remain explicit; this does not grant every viewer new
-- resource permissions or change the existing legacy read-fallback policy.

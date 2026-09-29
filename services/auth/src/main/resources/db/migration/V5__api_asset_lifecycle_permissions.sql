-- API 资产生命周期动作：发布和废弃必须经过 auth 决策，不复用粗粒度服务管理员绕过审计。
INSERT INTO rbac_permission (code, resource_type, action, name)
VALUES
    ('data_service.publish', 'data_service', 'publish', '发布数据服务'),
    ('data_service.deprecate', 'data_service', 'deprecate', '废弃数据服务')
ON CONFLICT (code) DO NOTHING;

INSERT INTO rbac_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_role r
JOIN rbac_permission p ON p.code IN ('data_service.publish', 'data_service.deprecate')
WHERE r.code = 'sys_admin'
ON CONFLICT DO NOTHING;

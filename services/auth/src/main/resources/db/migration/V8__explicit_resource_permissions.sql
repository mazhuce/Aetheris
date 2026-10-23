-- Catalog only: this migration deliberately grants no data access to users/groups/roles.
-- Review exact existing resource grants before deploying the explicit-read contract.
INSERT INTO rbac_permission(code, resource_type, action, name)
SELECT resource_type || '.' || action, resource_type, action, resource_type || ' ' || action
FROM (VALUES ('workflow_task'), ('quality_rule'), ('ingestion_task'),
             ('governance_policy'), ('data_standard')) AS resources(resource_type)
CROSS JOIN (VALUES ('view'), ('create'), ('update'), ('delete'), ('execute')) AS actions(action)
ON CONFLICT(code) DO NOTHING;

INSERT INTO rbac_permission(code, resource_type, action, name) VALUES
('dataset.publish','dataset','publish','发布数据集'),
('dashboard.publish','dashboard','publish','发布看板')
ON CONFLICT(code) DO NOTHING;

-- A system viewer role must not advertise legacy global data permissions. Custom
-- roles and all explicit resource_grant rows remain unchanged for review.
DELETE FROM rbac_role_permission
WHERE role_id IN (SELECT id FROM rbac_role WHERE code = 'sys_viewer');
UPDATE permission_version SET version = version + 1, updated_at = now() WHERE id = 1;

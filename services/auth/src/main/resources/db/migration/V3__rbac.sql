-- ============================================================================
-- 阶段二 A：统一资源权限模型（RBAC + 资源授权 + 数据范围 + 权限版本）
--
-- 兼容性约定：
--   * auth_user.role 保持 admin/viewer 不变（V2 的 CHECK 约束不动），它是
--     "系统身份"，映射到本迁移种子的系统角色 sys_admin / sys_viewer。
--   * admin/viewer 在权限决策中不再作为硬编码分支，而是通过 sys_admin /
--     sys_viewer 的权限集合表达。
--   * 细粒度授权全部落 resource_grant；写权限没有授权即为拒绝（默认拒绝）。
--   * 读权限在无显式授权时回退到系统角色默认（admin/viewer 全读），一旦存在
--     带资源范围（resource_id 或 data_scope 非空）的读授权，即按授权收窄。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 角色与权限目录
-- ---------------------------------------------------------------------------
CREATE TABLE rbac_role (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE rbac_permission (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(128) NOT NULL UNIQUE,
    resource_type VARCHAR(32)  NOT NULL,
    action        VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512)
);

CREATE TABLE rbac_role_permission (
    role_id       BIGINT NOT NULL REFERENCES rbac_role (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES rbac_permission (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ---------------------------------------------------------------------------
-- 用户组
-- ---------------------------------------------------------------------------
CREATE TABLE user_group (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_group_member (
    group_id  BIGINT NOT NULL REFERENCES user_group (id) ON DELETE CASCADE,
    user_id   BIGINT NOT NULL REFERENCES auth_user (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

-- 用户与自定义角色的绑定；角色授权（含范围）通过 resource_grant 授给角色
CREATE TABLE user_role (
    user_id    BIGINT NOT NULL REFERENCES auth_user (id) ON DELETE CASCADE,
    role_id    BIGINT NOT NULL REFERENCES rbac_role (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------------
-- 组织与项目空间（数据范围的容器）
-- ---------------------------------------------------------------------------
CREATE TABLE organization (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE workspace (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization (id),
    code            VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_workspace_status CHECK (status IN ('active', 'disabled'))
);

-- ---------------------------------------------------------------------------
-- 资源授权绑定：支持授给 用户/用户组/角色 三种主体
--   resource_type: organization/workspace/domain/table/field/model/dimension/
--                  metric/dataset/dashboard/data_service/datasource/
--                  user/role/permission/user_group/grant
--   resource_id    为空 = 该资源类型全量（配合 data_scope 收窄范围）
--   data_scope     JSONB，形如 {"kind":"domain","values":["5"]}；
--                  支持 kind: workspace/domain/table/field/metric/dataset/dashboard
--   actions        JSONB 字符串数组，枚举见阶段二规格（view/query/create/...）
-- ---------------------------------------------------------------------------
CREATE TABLE resource_grant (
    id            BIGSERIAL PRIMARY KEY,
    grantee_type  VARCHAR(16) NOT NULL,
    grantee_id    BIGINT      NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id   BIGINT,
    data_scope    JSONB,
    actions       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    granted_by    VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_grant_grantee_type CHECK (grantee_type IN ('user', 'group', 'role'))
);

CREATE INDEX idx_resource_grant_user ON resource_grant (grantee_type, grantee_id);
CREATE INDEX idx_resource_grant_type ON resource_grant (resource_type);

-- ---------------------------------------------------------------------------
-- 权限版本：任何授权/角色/组/成员变更时 +1，供下游缓存失效
-- ---------------------------------------------------------------------------
CREATE TABLE permission_version (
    id         SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version    BIGINT      NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO permission_version (id, version) VALUES (1, 1);

-- ---------------------------------------------------------------------------
-- 权限目录种子
-- ---------------------------------------------------------------------------
-- 数据资源
INSERT INTO rbac_permission (code, resource_type, action, name) VALUES
    ('domain.view',        'domain',       'view',         '查看业务域'),
    ('domain.create',      'domain',       'create',       '新建业务域'),
    ('domain.update',      'domain',       'update',       '修改业务域'),
    ('domain.delete',      'domain',       'delete',       '删除业务域'),
    ('domain.submit_review','domain',      'submit_review','提交业务域评审'),
    ('domain.approve',     'domain',       'approve',      '审批业务域'),
    ('domain.publish',     'domain',       'publish',      '发布业务域'),
    ('domain.deprecate',   'domain',       'deprecate',    '下线业务域'),
    ('domain.grant',       'domain',       'grant',        '授权业务域'),
    ('table.view',         'table',        'view',         '查看数据表'),
    ('table.query',        'table',        'query',        '查询数据表'),
    ('table.create',       'table',        'create',       '新建数据表'),
    ('table.update',       'table',        'update',       '修改数据表'),
    ('table.delete',       'table',        'delete',       '删除数据表'),
    ('table.grant',        'table',        'grant',        '授权数据表'),
    ('table.export',       'table',        'export',       '导出数据表'),
    ('field.view',         'field',        'view',         '查看字段'),
    ('field.query',        'field',        'query',        '查询字段'),
    ('field.grant',        'field',        'grant',        '授权字段'),
    ('model.view',         'model',        'view',         '查看逻辑模型'),
    ('model.create',       'model',        'create',       '新建逻辑模型'),
    ('model.update',       'model',        'update',       '修改逻辑模型'),
    ('model.delete',       'model',        'delete',       '删除逻辑模型'),
    ('model.submit_review','model',        'submit_review','提交模型评审'),
    ('model.approve',      'model',        'approve',      '审批模型'),
    ('model.publish',      'model',        'publish',      '发布模型'),
    ('model.deprecate',    'model',        'deprecate',    '下线模型'),
    ('model.grant',        'model',        'grant',        '授权模型'),
    ('dimension.view',     'dimension',    'view',         '查看语义维度'),
    ('dimension.create',   'dimension',    'create',       '新建语义维度'),
    ('dimension.update',   'dimension',    'update',       '修改语义维度'),
    ('dimension.delete',   'dimension',    'delete',       '删除语义维度'),
    ('dimension.submit_review','dimension','submit_review','提交维度评审'),
    ('dimension.approve',  'dimension',    'approve',      '审批维度'),
    ('dimension.publish',  'dimension',    'publish',      '发布维度'),
    ('dimension.deprecate','dimension',    'deprecate',    '下线维度'),
    ('dimension.grant',    'dimension',    'grant',        '授权维度'),
    ('metric.view',        'metric',       'view',         '查看指标'),
    ('metric.query',       'metric',       'query',        '查询指标'),
    ('metric.create',      'metric',       'create',       '新建指标'),
    ('metric.update',      'metric',       'update',       '修改指标'),
    ('metric.delete',      'metric',       'delete',       '删除指标'),
    ('metric.submit_review','metric',      'submit_review','提交指标评审'),
    ('metric.approve',     'metric',       'approve',      '审批指标'),
    ('metric.publish',     'metric',       'publish',      '发布指标'),
    ('metric.deprecate',   'metric',       'deprecate',    '下线指标'),
    ('metric.grant',       'metric',       'grant',        '授权指标'),
    ('dataset.view',       'dataset',      'view',         '查看数据集'),
    ('dataset.query',      'dataset',      'query',        '查询数据集'),
    ('dataset.create',     'dataset',      'create',       '新建数据集'),
    ('dataset.update',     'dataset',      'update',       '修改数据集'),
    ('dataset.delete',     'dataset',      'delete',       '删除数据集'),
    ('dataset.grant',      'dataset',      'grant',        '授权数据集'),
    ('dataset.export',     'dataset',      'export',       '导出数据集'),
    ('dashboard.view',     'dashboard',    'view',         '查看看板'),
    ('dashboard.create',   'dashboard',    'create',       '新建看板'),
    ('dashboard.update',   'dashboard',    'update',       '修改看板'),
    ('dashboard.delete',   'dashboard',    'delete',       '删除看板'),
    ('dashboard.grant',    'dashboard',    'grant',        '授权看板'),
    ('dashboard.export',   'dashboard',    'export',       '导出看板'),
    ('data_service.view',  'data_service', 'view',         '查看数据服务'),
    ('data_service.execute','data_service','execute',      '调用数据服务'),
    ('data_service.create','data_service', 'create',       '新建数据服务'),
    ('data_service.update','data_service', 'update',       '修改数据服务'),
    ('data_service.delete','data_service', 'delete',       '删除数据服务'),
    ('data_service.grant', 'data_service', 'grant',        '授权数据服务'),
    ('datasource.view',    'datasource',   'view',         '查看数据源'),
    ('datasource.create',  'datasource',   'create',       '新建数据源'),
    ('datasource.update',  'datasource',   'update',       '修改数据源'),
    ('datasource.delete',  'datasource',   'delete',       '删除数据源'),
    ('datasource.grant',   'datasource',   'grant',        '授权数据源'),
    -- 管理资源
    ('user.view',          'user',         'view',         '查看用户'),
    ('user.create',        'user',         'create',       '新建用户'),
    ('user.update',        'user',         'update',       '修改用户'),
    ('user.delete',        'user',         'delete',       '删除用户'),
    ('user.grant',         'user',         'grant',        '授权用户'),
    ('role.view',          'role',         'view',         '查看角色'),
    ('role.create',        'role',         'create',       '新建角色'),
    ('role.update',        'role',         'update',       '修改角色'),
    ('role.delete',        'role',         'delete',       '删除角色'),
    ('role.grant',         'role',         'grant',        '授权角色'),
    ('permission.view',    'permission',   'view',         '查看权限目录'),
    ('user_group.view',    'user_group',   'view',         '查看用户组'),
    ('user_group.create',  'user_group',   'create',       '新建用户组'),
    ('user_group.update',  'user_group',   'update',       '修改用户组'),
    ('user_group.delete',  'user_group',   'delete',       '删除用户组'),
    ('user_group.grant',   'user_group',   'grant',        '授权用户组'),
    ('organization.view',  'organization', 'view',         '查看组织'),
    ('organization.create','organization', 'create',       '新建组织'),
    ('organization.update','organization', 'update',       '修改组织'),
    ('organization.delete','organization', 'delete',       '删除组织'),
    ('workspace.view',     'workspace',    'view',         '查看项目空间'),
    ('workspace.create',   'workspace',    'create',       '新建项目空间'),
    ('workspace.update',   'workspace',    'update',       '修改项目空间'),
    ('workspace.delete',   'workspace',    'delete',       '删除项目空间'),
    ('grant.view',         'grant',        'view',         '查看授权'),
    ('grant.create',       'grant',        'create',       '新增授权'),
    ('grant.update',       'grant',        'update',       '修改授权'),
    ('grant.delete',       'grant',        'delete',       '撤销授权'),
    -- 服务级管理（网关粗粒度入口保护用）
    ('service.admin.manage','service',     'admin',        '系统级管理（用户/审计/授权管理入口）'),
    ('service.metadata.admin','service',   'admin',        '元数据服务管理'),
    ('service.serving.admin','service',    'admin',        '分析服务管理'),
    ('service.quality.admin','service',    'admin',        '质量服务管理'),
    ('service.ingestion.admin','service',  'admin',        '采集服务管理'),
    ('service.governance.admin','service', 'admin',        '治理服务管理'),
    ('service.workflow.admin','service',   'admin',        '工作流服务管理'),
    ('service.system.admin','service',     'admin',        '系统服务管理'),
    ('service.ai.admin',   'service',      'admin',        '智能问数管理');

-- 系统角色：sys_admin 持有全部权限；sys_viewer 只读（view/query/execute 只读动作）
INSERT INTO rbac_role (code, name, description, is_system) VALUES
    ('sys_admin',  '系统管理员', '内置管理员角色，与历史 admin 账号映射', TRUE),
    ('sys_viewer', '只读观察者', '内置只读角色，与历史 viewer 账号映射', TRUE);

INSERT INTO rbac_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_role r
CROSS JOIN rbac_permission p
WHERE r.code = 'sys_admin';

INSERT INTO rbac_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_role r
JOIN rbac_permission p ON (p.resource_type, p.action) IN (
    ('domain', 'view'), ('table', 'view'), ('table', 'query'),
    ('field', 'view'), ('field', 'query'),
    ('model', 'view'), ('dimension', 'view'),
    ('metric', 'view'), ('metric', 'query'),
    ('dataset', 'view'), ('dataset', 'query'),
    ('dashboard', 'view'),
    ('data_service', 'view'), ('data_service', 'execute'),
    ('organization', 'view'), ('workspace', 'view')
)
WHERE r.code = 'sys_viewer';

-- 默认组织与项目空间：作为数据范围容器与演示环境种子
INSERT INTO organization (code, name, description) VALUES ('default', '默认组织', '系统初始化时创建的组织');

INSERT INTO workspace (organization_id, code, name, description)
SELECT id, 'default', '默认项目空间', '系统初始化时创建的项目空间'
FROM organization
WHERE code = 'default';
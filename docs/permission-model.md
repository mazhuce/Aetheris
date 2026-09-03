# 统一资源权限模型（阶段二 A）

本阶段把平台从"admin/viewer 两级角色"升级为**企业级资源权限模型**：RBAC + 资源授权 + 数据范围 + 权限版本。
以下说明覆盖领域模型、判定链路、安全边界与操作方式。

## 资源层级与权限动作

```
组织（organization）
  └── 项目空间（workspace）
       ├── 业务域（domain）
       │    ├── 数据表（table）
       │    │    └── 字段（field）
       │    └── 语义资产：逻辑模型（model）/ 语义维度（dimension）/ 指标（metric）
       ├── 数据集（dataset）
       ├── 看板（dashboard）
       └── 数据服务（data_service / api）
```

支持的动作：`view/list`、`query`、`create`、`update`、`delete`、`submit_review`、`approve`、
`publish`、`deprecate`、`grant`、`execute`、`export`、`manage`（系统管理）。

权限对象：用户、用户组、组织、项目空间、角色、权限目录、资源授权绑定、数据范围、权限版本。

## 判定链路

```
身份认证（gateway JWT 验签，httponly 之外仅 gateway 可信）
  → gateway 粗粒度入口保护（敏感前缀 / 管理写请求问询 auth 决策引擎）
  → 业务服务资源级判定（metadata 等经 X-Aetheris-Identity 验签取得身份，
     每次请求实时调用 auth /internal/permissions/check）
  → 数据范围匹配（DataScope kind=workspace/domain/table/field/metric…）
  → 动作判定（读/写分离，默认拒绝）
```

- 读权限与写权限分离：写动作永不自带默认放行；读动作仅在未被授权显式收窄时回退系统角色默认。
- 权限变更立即生效：授权/撤销/角色权限绑定变更时递增 `auth.permission_version`，
  业务服务不做长缓存，每次判定实时询问决策引擎，避免旧权限长期生效。
- 失败模式 fail-closed：判定服务不可用一律拒绝（业务服务返回 403；gateway 返回 503）。

## 兼容性与安全边界

- `admin` / `viewer` 登录账号保持不变：auth 侧以 `sys_admin` / `sys_viewer` 系统角色表达，
  与既有账号的 `role` 字段通过 `auth_user_role` 绑定映射，迁移不使旧账号失效。
- 业务逻辑核心链路不再把 admin 作为硬编码例外：判定统一走 `PermissionDecisionService`
  （系统角色只是决策引擎的输入之一）。
- 前端按钮可见性仅作体验优化；所有安全边界由后端强制执行。直接调用接口绕过前端
  同样被拒绝（有自动化测试覆盖）。
- 身份不来自浏览器可伪造的普通请求头：gateway 以共享令牌对 uid/username 签名，
  业务服务只认签名（X-Aetheris-Identity）；`X-User-Name` 等头一律由网关覆盖重写。
- 每次授权、撤销、拒绝与越权尝试都会写入审计事件（`system.permission_event`）。

## 数据库（Flyway 迁移，均在 auth schema）

| 表 | 说明 |
| --- | --- |
| `auth.rbac_role` | 角色（含 sys_admin/sys_viewer 系统角色，不可改删） |
| `auth.rbac_permission` | 权限目录（resource_type × action → code） |
| `auth.rbac_role_permission` | 角色 → 权限绑定 |
| `auth.user_group` | 用户组 |
| `auth.user_group_member` | 用户组成员 |
| `auth.organization` / `auth.workspace` | 组织与项目空间（数据范围容器） |
| `auth.resource_grant` | 资源授权绑定（grantee_type/资源/动作/数据范围/启停） |
| `auth.permission_version` | 权限版本（每次变更递增，纳秒时间戳语义） |
| `auth.user_role` | 用户 → 角色绑定（兼容旧 admin/viewer 角色的落脚点） |

## 服务间协议

| 端点 | 用途 |
| --- | --- |
| `POST /internal/permissions/check` | 单资源动作判定 `{username, resourceType, resourceId, action, ctx}` |
| `POST /internal/permissions/visibility` | 读列表可见范围（ALL / IDS / OPAQUE） |
| `POST /internal/permissions/mutate-check` | gateway 粗粒度写入判定 |
| （内部端点统一要求 X-Internal-Token，见 InternalEndpointInterceptor） | |

业务服务（当前接入 metadata；system/gateway 粗粒度已接入）通过
`services/*/security/AuthPermissionClient.java` 与 `MetadataSecurity` 完成判定。

## 管理界面

系统管理下新增（均要求系统管理权限）：

- 角色管理：`/admin/roles`（含权限集合绑定）
- 权限集合：`/admin/permissions`（权限目录浏览）
- 用户组：`/admin/groups`（含成员管理）
- 组织管理：`/admin/organizations`
- 项目空间：`/admin/workspaces`
- 资源授权：`/admin/grants`（授权新增/修改/撤销 + 数据范围配置）

## 数据范围（DataScope）

```json
{ "kind": "domain", "values": ["5", "6"] }
{ "kind": "table", "values": ["12"] }
```

`values` 统一按字符串存储与匹配；`kind` 表达层级（workspace / domain / table / field / metric 等），
可与资源授权叠加，实现"有表权限但无字段权限时字段被拒绝"等细粒度控制
（字段不继承表授权，要求显式授予）。

## 测试覆盖

- viewer 不能写入（决策引擎写动作不默认放行）；
- 无业务域权限时不能读取其中表和指标（visibility 收窄 + 列表过滤）；
- 撤销授权后旧缓存不继续放行（无长缓存 + 版本递增）；
- 直接调用接口不能绕过（metadata 所有业务端点强制网关身份验签，无签名 401）；
- admin/viewer 旧账号迁移后行为兼容（sys_admin / sys_viewer 映射）。
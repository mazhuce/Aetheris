# 九服务数据库账号与迁移演练

本工具提供九服务 schema 权限、独立迁移进程、存量所有权迁移计划和一次性 PostgreSQL 验证。正式 `deploy-remote.sh` 已强制加入 roles override，并检查签名发布源、实际 Compose 模型和 live 就绪收据；直接运行裸 `docker compose` 仍不会自动加入该文件。新门禁已有本地行为回归，真实隔离数据库联验正在进行，现有部署未切换。

## 账号与权限契约

`configs/database/services.json` 是服务映射：auth、system、metadata、serving、workflow、ingestion、quality、governance 各用同名 schema；ai-agent 用 `ai`。

每个 schema 有三个角色：

| 角色 | 能力与生命周期 |
| --- | --- |
| `aetheris_<schema>_owner` | NOLOGIN；只拥有本 schema 及其对象，不授数据库 CREATE，不拥有其他服务对象。 |
| `aetheris_<schema>_migrate` | 平时 NOLOGIN、密码 NULL、无 owner 成员关系；一次迁移启用至多 15 分钟，只允许 SET ROLE 自己的 owner。每个 Flyway 连接执行 SET ROLE，避免对象归登录账号。 |
| `aetheris_<schema>` | LOGIN、NOINHERIT、无角色成员关系及管理权限；只获自己 schema 的 USAGE、业务表 SELECT/INSERT/UPDATE/DELETE 和 sequence USAGE/SELECT。 |

runtime 没有 CREATE、TEMP、TRUNCATE、TRIGGER、REFERENCES、sequence UPDATE/setval、Flyway history 权限及其他服务 schema 的 USAGE。不能 SET ROLE 到 owner、migrate 或管理员，不能读 pg_authid/pg_shadow 密码。PG 的公开 catalog 仍能显示角色/对象名称；这里没有承诺隐藏这些名称。

当前跨服务持久化依赖通过 HTTP 接口实现；静态 SQL/mapper/迁移检查没有发现跨服务 schema 查询或外键。metadata 的数据源查询、数仓 DDL 等使用单独登记的数据源凭据，不为平台 runtime 开跨 schema 权限。新增跨 schema 需求必须先列对象与操作，再审阅授权。

源迁移里的函数都是 invoker trigger。调度 outbox、代次 sequence、metadata 健康失效及删除保护需要自己表的 DML；不需要给 runtime 手动 EXECUTE。owner 的默认 PUBLIC EXECUTE 被撤销，迁移后精确同步对象 ACL。没有未来全表默认 DML，`flyway_schema_history` 始终排除。VIEW/MATERIALIZED VIEW、独立类型、扩展、非零参数函数、SECURITY DEFINER、未知依赖均阻断自动计划，转为明确审阅，不声称已经支持。

## 文件与凭据

所有真实配置、密码和就绪收据放在 ignored `configs/database/private/`，不要复制到报告、制品或 Docker build context。提交的 `*.example.json` 仅有路径；不要填入真实值。runner Dockerfile 的构建 context 只能是 `scripts/database/runner`，不使用仓库根目录：

```powershell
docker build -f scripts/database/runner/Dockerfile -t <reviewed-runner-tag> scripts/database/runner
```

发布前按基础镜像锁定方案将两个同版本 base 的 build args 指定为经核验 digest；本包没有执行这个构建或编造镜像 digest。发布的 runner 必须以 `image@sha256:...` 配置 `AETHERIS_DB_MIGRATION_IMAGE`。现用依赖为项目 Spring Boot 3.4.7 管理的 Flyway 10.20.1，以及 metadata 已固定的 PostgreSQL JDBC 42.7.13；没有升级主版本。主机脚本默认离线 Maven 编译；缓存缺失就失败，不自行联网升级。

密码文件使用 UTF-8、无 BOM、无末尾换行，runtime/migration 使用 32–128 字符的随机 URL-safe 密码。Windows PowerShell 5 不要用默认 Out-File 写密码。主机 CLI 要求秘密文件实际解析路径位于 private 目录内，禁止通过符号链接指向其他目录。使用操作系统 ACL 限制操作者之外的读取。

服务镜像以 UID/GID 10001 运行。Compose 从普通文件挂载 secret 时不会保证实现声明的 uid/gid/mode 重映射；Linux 必须预先给 UID 10001 只读访问，Windows 必须核对 Docker Desktop 的挂载结果。可在正式启动前用只执行 `test -r` 的临时检查验证可读性，不能 cat 密码。runtime 每个容器只挂自己一份 `spring.datasource.password`，通过 Spring configtree 读取；不挂 admin/migration 文件。migration job 只挂自己的迁移密码和管理员退休用密码，任务结束即移除。

## 干净安装

停止应用服务后，复制 operator 示例到 private 目录，配置目标 URL、管理员用户名及密码文件路径，准备九个 runtime/migration 密码文件。工具不改现有 `.env` 或密钥。

```powershell
py -3 scripts/database/database_roles.py provision-empty --config configs/database/private/operator.local.json
py -3 scripts/database/database_roles.py migrate --config configs/database/private/operator.local.json --service auth
# 对其余八服务分别执行 migrate；服务名见 services.json。
py -3 scripts/database/database_roles.py sync-grants --config configs/database/private/operator.local.json --output configs/database/private/readiness.local.json --acknowledge-quiesced
```

`provision-empty` 拒绝有业务对象/schema 或已存在目标角色的数据库，不会把现有安装伪装成空库。它建立 owner/schema、runtime 账号、NOLOGIN migration 账号，并撤数据库 PUBLIC TEMP/public schema CREATE。迁移过程验证源文件并禁止 clean；每次迁移后 finally 退休该迁移账号。`sync-grants` 仅接受 owner 已正确的九 schema，对业务表和 sequence 同步 ACL 后生成实际数据库验证的收据。

也可用 `docker-compose.database-migrate.yml` 的 `db-migrate-*` 操作任务。先审阅并复制对应 migration 示例至 private，覆盖非默认 DB 名/管理员配置，将 `AETHERIS_<SCHEMA>_DB_MIGRATION_CONFIG` 指向副本。secret 路径通过 `AETHERIS_DB_ADMIN_PASSWORD_FILE` 和 `AETHERIS_<SCHEMA>_DB_MIGRATION_PASSWORD_FILE` 指定。任务禁止自动拉镜像，不会启动应用或为其保留迁移凭据；它不代替 bootstrap/存量 owner 计划、迁移后 ACL 同步及收据检查。

## 已有 schema 的可审阅迁移

先停写、排空所有客户端连接并制作可恢复备份，不能在本地工作流直接对现有库执行演练。

```powershell
py -3 scripts/database/database_roles.py inventory --config configs/database/private/operator.local.json --output configs/database/private/inventory.local.json
py -3 scripts/database/database_roles.py plan --inventory configs/database/private/inventory.local.json --output configs/database/private/plan.local.json
```

inventory 在只读事务中记录 cluster system identifier、数据库 OID/名称、对象原 owner/ACL、函数定义摘要、默认 ACL、角色成员关系、依赖、Flyway history、所有当前源迁移 SHA256，以及当前源的真实 Flyway validate/零 pending 结果。关闭 `*:future` 默认忽略；success=true 本身不能证明兼容。未知版本/script/checksum、缺历史、失败历史均阻断自动计划。

plan 同时保存原快照、applySql、rollbackSql。只生成九个指定 schema/明确对象名的 ALTER OWNER 和 ACL；表先于 owned sequence 转移。不会执行全库 REASSIGN OWNED、Flyway repair、删历史或贴历史通过标签。已有目标角色、显式 ACL/grant 链、特殊对象/依赖/默认 ACL 会保守阻断，保留 inventory 供 DBA 定制审阅。

自动回滚只覆盖已列对象的 owner 和有效默认权限；PostgreSQL 可能把原 NULL ACL 表示为等效显式 ACL。角色创建、登录密码及 owner 的默认 ACL 是独立操作，不能假称 rollbackSql 是数据库数据恢复或跨服务业务版本回滚。原 owner/原 ACL 均完整保存，复杂 grantor 链不被工具简化重建。

应用计划还必须有备份收据：`archive`、`sha256`、`database`、`sourceSystemIdentifier`、`sourceDatabaseOid`、`sourceInventorySha256`、`backupCreatedAtUtc`、`verifiedRestoreAtUtc`。archive 是真实 PGDMP custom dump；收据必须对应同一 cluster/OID/inventory，时间有时区、先备份后恢复、均在过去 24 小时。记录恢复时间是外部恢复验证者的声明，不是仅凭 JSON 能证明已恢复；操作者必须确实在独立库恢复并核对数据，再签署/保管收据。CLI 会重新核对文件头及完整摘要。

```powershell
py -3 scripts/database/database_roles.py apply-plan --config configs/database/private/operator.local.json --plan configs/database/private/plan.local.json --backup-receipt configs/database/private/backup-receipt.local.json --acknowledge-quiesced
```

apply 在事务中获取本工具 advisory lock，重读完整 inventory，与已审阅快照逐项一致，且拒绝任何其他 client session，再执行确定性重算的 SQL。任何快照变化都要求重新审阅。撤 PUBLIC TEMP/public CREATE 影响同库其他消费者，必须先完成消费者清单核对；本工具无法给某个账号单独 DENY 一个 PUBLIC 权限。停写窗口也必须防止其他管理员并发 DDL。

权限 revoke、角色 NOLOGIN/VALID UNTIL 不是已存在会话的即时撤权。必须排空 runtime 会话后变更。迁移 finally 会 NOLOGIN、清密码、撤 owner 成员关系、终止该 migration 账号现有连接，并独立查询 pg_authid/成员关系/pg_stat_activity 断言退休。若进程被杀或宿主崩溃，15 分钟 TTL 只限制后续登录，不会即时结束已有连接。恢复后先运行：

```powershell
py -3 scripts/database/database_roles.py retire --config configs/database/private/operator.local.json
```

retire 会逐一尝试全部九账号，任何未退休都返回失败；不能看到迁移失败就直接启动 runtime。

## 旧 serving V3 到 V8 的边界

历史 commit `c570203e92f55cb1ba6881df0f65cf6bd8fdfe68` 同时有 semantic V3 与 API-key V3。当前 `44c6235adf848d02e5c7c62a34f0669fbe5e5764` 的命名修正把后者相同 SQL 改为 V8；没有证明任何真实生产历史属于哪一支。

一次性 PostgreSQL 演练证明：双 V3 源树被 Flyway 拒绝；只应用 semantic V3 的合法历史可升级当前；应用 API-key V3 的旧历史升级当前会出现真实 FlywayValidateException，且历史表未变。这些是明确构造的历史 fixture，不是生产记录。旧 API-key V3 路径默认阻断；推荐在新库用当前迁移重建，然后审阅并迁移业务数据、核对行数/约束/sequence/业务功能后切换。若必须原库修复，先完整提取历史、版本源码和实际对象差异，由 DBA 给出专用方案；此工具不自动改 Flyway history。

## 正式部署门禁接口

`receipt`/`sync-grants` 生成 `purpose=aetheris-database-runtime-readiness`、schemaVersion=1 的私密收据。它绑定全部源迁移 SHA256、目标 URL、九 schema/runtime role、九 runtime 密码文件 SHA256、真实 inventory 摘要、runId 和 UTC 验证时间。实际连接九 runtime 账号检查权限，并用当前源运行 Flyway validate/零 pending、检查 migration 账号退休；24 小时后过期。不得把带密码指纹的生产收据上传公共 CI artifact；演练报告故意不写密码指纹。

```powershell
py -3 scripts/database/database_roles.py verify-receipt --config configs/database/private/operator.local.json --receipt configs/database/private/readiness.local.json
py -3 scripts/database/compose_contract.py --resolved <in-memory-or-private-resolved-compose.json>
```

执行顺序：独立 migration job 成功 → sync-grants/receipt 成功 → 正式 deploy 验签 → 使用同一配置/源/秘密文件 live verify-receipt → 验证内存中的 resolved Compose 九 runtime 唯一 secret/账号/Flyway=false、11个实际服务的镜像 digest → 从实际 Compose postgres 读取 cluster/OID/database 并与收据所指数据库一致 → 私密记录收据 SHA256、签名清单 SHA256、commit/制品 → 明确加入 roles override 后启动。无收据、过期、源改变、秘密指纹不匹配、数据库 ACL/history 漂移或 Compose 回落共享账号，一律失败，不能改用原共享 DB_USER 继续部署。单独运行 compose_contract 不是上线许可。

新发布清单 schemaVersion=2，签名覆盖九服务的全部 databaseSources；schemaVersion=1 只可读取历史信息，不具备新部署许可。默认私密输入为 `configs/database/private/operator.local.json` 和 `readiness.local.json`，可通过 `AETHERIS_DB_OPERATOR_CONFIG` / `AETHERIS_DB_READINESS_RECEIPT` 指定同目录内的文件。`deployment-readiness.local.json` 是 `verified-not-deployed` 的本地准入记录，不把门禁验证当部署成功。resolved Compose 可能含其他凭据，只通过内存管道消费，不写报告或控制台。

主机当前还需 JDK21、Maven和经过准备的离线依赖缓存；deploy 不自动安装这些依赖，也不自动执行 migration/sync-grants。回滚保留 roles override，并要求上一份签名清单的 migration sources 与当前实际数据库收据一致；不能退回旧共享账号/tag路径，也不自动降级数据库。跨 migration 的应用回滚需另行审阅兼容方案。部署结束不执行全局 image prune。

base Compose 在合并 override 前仍解析 `DB_PASSWORD:?`；只为 base 解析提供**非敏感占位值**，最终九 runtime 的 DB_PASSWORD 必须为空，实际 spring.datasource.password 来自各自 secret。最终模型检查不可省略。数据库管理员密码只在 postgres/专用操作进程出现，不进入九个业务应用。上述代码变更没有执行生产切换。

## 可复现验证

```powershell
py -3 scripts/tests/test_database_roles_contract.py
py -3 scripts/tests/test_database_roles_behavior.py
py -3 scripts/database/database_roles.py acceptance
```

acceptance 只使用已缓存的 postgres:16-alpine，在随机 loopback 端口运行带 runId 标签的新容器、tmpfs 数据目录，无现有 volume。验证全部源 SQL、存量 owner/ACL 计划、真实 dump/restore、runtime 实表 CRUD/sequence/Quartz 锁/outbox、跨 schema/DDL/history/密码 catalog 权限负例、Flyway 再启动和历史 V3 fixture。报告在 ignored output/audit-database-roles；报告只有所有项及 finally 退休和容器删除均成功才 passed。没有运行九个完整应用镜像或正式部署，本轮数据库 IT 不能代替后续应用启动与业务冒烟。

权限语义参考 [PostgreSQL 16 Privileges](https://www.postgresql.org/docs/16/ddl-priv.html)、[ALTER DEFAULT PRIVILEGES](https://www.postgresql.org/docs/16/sql-alterdefaultprivileges.html)、[GRANT](https://www.postgresql.org/docs/16/sql-grant.html)。

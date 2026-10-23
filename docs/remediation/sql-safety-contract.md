# SQL 与数据源边界

## 支持的执行能力

业务查询接受单条 SELECT、只读 WITH、EXPLAIN SELECT，以及受限的 SHOW/DESCRIBE。数仓写入接受 INSERT SELECT/VALUES、CREATE TABLE、CREATE VIEW AS SELECT 和 CREATE DATABASE/SCHEMA IF NOT EXISTS。Hive 的 INSERT OVERWRITE [TABLE] 与仅列名的动态 PARTITION 列表会在结构校验时转换为等价 INSERT 形状；执行保留原文。静态分区、DDL 扩展或方言语法未通过结构校验时明确拒绝，需增加能力测试后开放。

使用 MyBatis 已包含的 JSqlParser 4.9 做结构检查；词法扫描负责区分注释、字符串和引用标识符，拒绝多语句、执行注释、未闭合/嵌套注释、不明确的引号转义和过深嵌套。普通注释及字符串中的分号/关键字不会被当作操作符。SQL Server 等方言的保留字作为表/字段名时需要引用，例如 `warehouse."output"`。不声称完整支持每个数据库的所有 SQL 扩展。

读写共享显式函数清单；未知函数、自定义 schema 函数、DBLINK 系列、文件/进程/会话控制及外部存储路径被拒绝。合法内置函数通过 PostgreSQL `pg_catalog` 显式限定仍需处于清单中。增加函数必须同时评估副作用、数据库权限和对应引擎用例。

## 数据库权限与连接

SQL 解析是辅助防线。只读角色只授业务 schema 的 USAGE/SELECT，禁止平台 auth/system/metadata 等 schema；加工角色限定目标 schema，不应为超级用户或拥有服务器文件/进程权限。数据库可执行函数和公共 schema 的 CREATE 权限也必须最小化，不能仅凭函数名字证明一个账户无副作用。

PostgreSQL/MySQL 的查询连接启用只读并关闭自动提交，连接关闭时回滚；PostgreSQL 的只读事务是数据库级兜底。其他引擎不能据 JDBC 提示被视为强制只读。查询行数与超时限制继续生效。

原 `managed=true` 平台连接在所有 JDBC 入口拒绝，启动时更新为 isolated，保留原记录和密文用于审计及人工迁移。平台数据库凭据不再自动注册。业务连接必须由管理员显式创建并使用限定权限账户。

嵌入式 H2/Derby/SQLite 在 Spring 运行配置中默认禁用；隔离开发可显式设置 `AETHERIS_ALLOW_EMBEDDED_DATASOURCES=true`。参数仍执行允许清单，不能启用 INIT、动态驱动类、socketFactory 或任意启动 SQL。无参 JdbcService 仅用于进程内测试，不是 Spring 运行配置。

自动生成与自定义 JDBC URL 最终都要验证；主机、端口、数据库名称不能夹带参数。MySQL 默认 VERIFY_IDENTITY，SQL Server 默认加密并验证证书；开发环境如需降低 TLS 要求必须显式配置，验收报告需记录脱敏配置。

## 验收入口与当前限制

单元回归见 `JdbcSqlSafetyRegressionTest`、`SqlWriteGuardSafetyRegressionTest`、`SqlWriteGuardTest` 与质量规则测试。`scripts/audit-sql-boundary-acceptance.ps1` 仅使用已存在的 PostgreSQL 镜像启动一次性随机端口容器，执行 metadata Flyway、真实读写权限及只读事务断言后删除容器。它不连接原项目数据库；原环境角色分离及全链部署仍需独立验收。

出站预检查已接入，具体能力及DNS rebinding残余边界见[出站契约](outbound-contract.md)。按服务数据库角色部署、内部调用服务身份和全部真实方言兼容矩阵仍在整改台账中，不因上述单元/隔离测试通过而标记完成。

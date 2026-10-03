# 支持的数据源

数据源连接由 `metadata-service` 统一管理，连接探测、只读查询、Schema/表/字段发现和预览共用同一套 JDBC 适配层。前端下拉目录与后端 URL/端口定义保持同名；新增类型必须同时更新两处并补 `JdbcServiceTest`。

后端权威目录可通过认证接口 `GET /api/metadata/datasources/catalog` 获取，当前返回 19 个类型、默认端口/库名和能力标签；创建、更新和保存前连接测试都会复用同一目录校验。

R1 交付时，PostgreSQL、MySQL、SQL Server、Oracle、ClickHouse、SQLite、Hive 和 Trino 的 JDBC 驱动都必须在 `configs/lakehouse/connector-allowlist.toml` 中登记 Maven Central 来源、SHA-256、许可证和许可证链接；版本由 `r1-bom.toml` 逐项校验，避免“目录里能选但部署包不可复现”。

| 类型 | 默认端口 | JDBC 驱动/协议 | 当前能力 |
|---|---:|---|---|
| PostgreSQL / TimescaleDB | 5432 | PostgreSQL JDBC | 探测、查询、元数据、预览、任务写入 |
| Greenplum / openGauss / CockroachDB / YugabyteDB | 5432 / 26257 / 5433 | PostgreSQL JDBC | 探测、查询、元数据、预览、任务写入 |
| MySQL / MariaDB | 3306 | MySQL Connector/J | 探测、查询、元数据、预览、任务写入 |
| TiDB | 4000 | MySQL Connector/J | 探测、查询、元数据、预览、任务写入 |
| OceanBase | 2881 | MySQL Connector/J | 探测、查询、元数据、预览、任务写入 |
| Apache Doris / StarRocks | 9030 | MySQL Connector/J | 探测、查询、元数据、预览、任务写入 |
| Apache Hive / Spark Thrift Server | 10000 | Hive JDBC / HiveServer2 | 探测、查询、元数据、预览、Hive/Spark SQL 写入 |
| Microsoft SQL Server | 1433 | Microsoft JDBC | 探测、查询、元数据、预览 |
| Oracle Database | 1521 | Oracle JDBC | 探测、查询、元数据、预览 |
| ClickHouse | 8123 | ClickHouse JDBC | 探测、查询、元数据、预览 |
| Trino | 8080 | Trino JDBC 476（与 R1 Trino 476 对齐） | 探测、查询、元数据、预览 |
| SQLite | 文件 | Xerial SQLite JDBC | 探测、查询、元数据、预览（开发/轻量用途） |

## 流式 CDC

R2 首批流式源为 PostgreSQL 和 MySQL：隔离 R1 profile 已分别提供 `source-postgres`（logical WAL/`pgoutput`）与兼容 Flink CDC 3.4.0 的 `source-mysql`（MySQL 8.0.40、ROW/FULL binlog/GTID），Flink SQL 模板位于 `artifacts/r1-fixture/flink/`。两种源均已完成初始快照、增量 UPDATE/DELETE/INSERT、checkpoint、TaskManager 重启恢复和最终一致性验收。MySQL 当前批准版本为 8.0.40；MySQL 8.4.5 因 Flink CDC 3.4.0 不再支持 `SHOW MASTER STATUS`，不纳入本版本兼容矩阵。

## 能力边界

- “支持”表示连接配置、健康探测和元数据/查询链路已经有实现；不代表每个引擎的方言都被转换成统一 SQL。
- 批量接入（transfer）当前仍只对 PostgreSQL/MySQL 兼容目标开放，避免把不同引擎的类型映射和事务语义伪装成一致能力。
- SQL 任务的写入语句继续按 metadata 的统一写入校验执行；Trino、SQL Server、Oracle、ClickHouse、SQLite 先作为查询型数据源接入，待方言和写入语义验证后再开放写入。
- 自定义 JDBC URL 必须与所选类型前缀匹配，例如 SQL Server 使用 `jdbc:sqlserver:`，ClickHouse 使用 `jdbc:ch:`，Trino 使用 `jdbc:trino:`。ClickHouse 默认连接关闭压缩（`compress=0`），避免精简运行镜像缺少可选 LZ4 native 模块。
- metadata 服务运行时已内置 `io.trino:trino-jdbc:476`，与 R1 Compose 的 `trinodb/trino:476` 对齐；不再依赖人工把驱动挂载到 classpath。自定义部署若替换 Trino 主版本，必须同步更新该依赖并重新执行连接探测。

## 真实连接验收

`scripts/datasource-smoke.ps1` 通过 metadata 的非写入 `POST /api/metadata/datasources/test`，逐项执行真实 `SELECT 1` 探测；它不创建或修改数据源记录。配置文件只保存 `passwordEnv` 环境变量名，不保存密码：

```powershell
$env:AETHERIS_SMOKE_POSTGRES_PASSWORD = '从密码管理器临时注入'
powershell -File scripts/datasource-smoke.ps1 `
  -ManifestPath configs/datasource-smoke.example.json `
  -MetadataBaseUrl http://127.0.0.1:8082 `
  -AuthorizationHeader 'Bearer <短期测试令牌>'
```

示例清单覆盖 PostgreSQL、MySQL、HiveServer2、SQL Server、Oracle、ClickHouse、Trino 和 SQLite 八类协议；实际环境中将可用条目标记为 `required: true`，或传 `-RequireAll` 将未注入凭据的条目也视为失败。脚本输出只包含类型、状态、延迟和后端诊断消息，不回显连接密码。

### 本地可回归运行门禁

需要同时验证“能连上”和“平台查询链路能返回结果”时，使用独立 smoke Compose 与运行验收脚本：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/datasource-runtime-acceptance.ps1 `
  -StartSmokeStack -TimeoutSeconds 180
```

该门禁在平台 metadata 容器内真实执行 `SELECT 1`，并临时写入、清理 9 条数据源记录；当前已通过 PostgreSQL、MySQL、Trino、MariaDB、ClickHouse、CockroachDB、SQLite、H2、Derby 九类路径。报告写入 `artifacts/datasource-smoke/runtime-acceptance-report.json`，不包含密码。MariaDB、ClickHouse、CockroachDB 夹具位于独立的 `infrastructure/datasources/docker-compose.smoke.yml`，只绑定 loopback 端口，不共享 R1 卷。

SQL Server、Oracle、Hive/Spark 等仍属于外部环境型连接：目录和 JDBC 适配已实现，但本地门禁没有伪造实例，因此不能把它们写成“本机运行已通过”。

## Flink SQL Gateway

Flink SQL 任务通过 SQL Gateway 执行，不把 Gateway 当作 JDBC 数据源。任务表单可直接测试任务填写的 Gateway 地址；开发页的独立健康卡片只代表默认 `AETHERIS_FLINK_SQL_GATEWAY_BASE_URL`，两者不要混淆。Gateway 连接成功后才进入 session/statement/operation 执行链路。

## Spark 制品上传

受管 Spark 现在提供 `POST /api/workflow/spark/artifacts/upload`（multipart：`name`、`version`、`artifactKey`、`file`）。上传的 `.py` 或 `.jar` 文件会广播到当前所有活动 Spark Runner 的制品目录，随后可在 Spark 作业定义中选择。上传制品的 SHA-256 由服务端计算，runner 接受 1B–64MB 文件，并对同名不同内容拒绝覆盖。

`.py` 制品按 PySpark 参数执行；`.jar` 制品登记为 `spark-jar`，创建作业时必须填写合法 JVM 主类名，runner 会以 `spark-submit --class <mainClass>` 启动。当前仍要求 JAR 已经由外部构建系统编译完成，平台不在上传时编译 Scala 源码。

Spark 作业创建还支持可选的参数架构 JSON、默认参数 JSON，以及同一计算环境内的指定 Runner；不指定 Runner 时由持久化资源队列按容量和 FIFO 自动选择。参数架构、默认参数、制品摘要和 Runner 绑定都会固化到发布定义中。

## Iceberg maintenance 执行前置条件

R5 maintenance/schema change 不接受任意 SQL。metadata 先生成操作白名单内的单条 `CALL`、`SELECT` 或受控 `ALTER TABLE`，workflow 再以固定 request key 通过 Runner 提交 `spark/approved/aetheris_lakehouse_maintenance.py`。启用前必须把相同文件部署到每个 Runner 的 approved artifact 目录，并配置其 SHA-256：

```powershell
$env:AETHERIS_SPARK_MAINTENANCE_ENABLED = 'true'
$env:AETHERIS_SPARK_MAINTENANCE_ARTIFACT_SHA256 = '16a6d79fe4574622e446e0499cc0a45ef0d6d839f2c6fc04e6b60b84d22f4b89'
```

未配置摘要、Runner 文件摘要不一致、远端提交结果不确定时，控制面不会标记成功；作业保持 `running`，等待 `/reconcile` 继续核对。

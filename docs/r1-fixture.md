# R1 PostgreSQL → Iceberg fixture

这套 fixture 为 R1 的跨引擎门槛提供可复核输入和输出契约。它使用独立的 `source-postgres` 容器，源表为 `r1_fixture.source_orders`，Bronze 表为 `bronze.r1_orders`。输入行、聚合结果和 SHA-256 校验值固定在 manifest 中；Spark 与 Trino 的结果即使行顺序或 Decimal 表示不同，也会先归一化后再对账。

## 生成材料

```powershell
python scripts/r1-fixture.py generate --output artifacts/r1-fixture
```

生成目录包含：

- `postgres/001_source_orders.sql`：源库初始化 SQL；Compose 的 `source-postgres` 首次初始化会自动挂载它。
- `postgres/002_cdc_mutations.sql`：R2 的确定性 UPDATE/DELETE/INSERT 序列；必须在 CDC 作业确认 RUNNING 后执行。
- `mysql/002_cdc_mutations.sql`：MySQL binlog 的同一组确定性变更；必须在 MySQL CDC 作业确认 RUNNING 后执行。
- `flink/001_bronze_orders.sql`：有界 Flink JDBC 模板，读取源表并写 Iceberg v2 Bronze 表；源表使用 Flink 临时表语义，避免被 REST Catalog 当作空的 Iceberg 表持久化。
- `flink/002_postgres_cdc.sql`：R2 PostgreSQL snapshot + WAL 模板；这是持续运行任务，用于 checkpoint、重启和增量恢复验收。
- `flink/003_mysql_cdc.sql`：R2 MySQL snapshot + binlog 模板；对应 Compose 的 `source-mysql`，使用 ROW/FULL binlog 设置。
- `flink/rendered/`：只在显式提供真实 `.env.r1` 并执行 `render` 或 `r1-rebuild.ps1 -Start` 后生成；其中包含 SQL Gateway 可直接执行的凭据，不应提交到 Git。
- `spark/001_reconcile.sql` 与 `trino/001_reconcile.sql`：对同一 Bronze 表执行相同聚合；`scripts/r1-spark-read.ps1` 与 `scripts/r1-trino-read.ps1` 会在真实引擎中执行并写入 `results/*.json`。
- `manifest.json`：源字段、行数、期望聚合和 SHA-256。

源表初始化会设置 `REPLICA IDENTITY FULL`，保证 PostgreSQL UPDATE/DELETE 的 WAL 事件包含旧行镜像，便于 Bronze upsert/delete 和增量对账。
MySQL 源库使用固定且与 Flink CDC 3.4.0 兼容的 `mysql:8.0.40`、固定 CDC 用户 `r1_mysql_fixture`、ROW/FULL binlog、GTID 和独立数据卷；Flink CDC 3.4.0 仍使用 `SHOW MASTER STATUS`，不能把 MySQL 8.4 直接当作同一兼容组合。建议在真实环境只替换 `R1_SOURCE_MYSQL_PASSWORD`，未提供时仅隔离本地 smoke 复用 PostgreSQL 源密码。

脚本默认拒绝覆盖非空目录；只有明确传入 `--force` 才会重建 fixture 目录。

## 运行与对账

先使用 `configs/lakehouse/.env.r1.example` 创建隔离的 `.env.r1`，补齐源库密码，然后按 R1 Compose 预检启动。执行 Flink SQL 后运行两个引擎读取脚本，它们会导出包含 `columns` 和 `rows` 的 JSON：

如果只需要渲染 Flink SQL 而不启动容器：

```powershell
python scripts/r1-fixture.py render `
  --fixture artifacts/r1-fixture `
  --env-file configs/lakehouse/.env.r1
```

渲染器会拒绝 `replace-with` 或 `PENDING_CAPTURE`，并把结果写入被忽略的 `flink/rendered/` 目录；模板本身不包含真实密码。

```powershell
powershell -File scripts/r1-spark-read.ps1
powershell -File scripts/r1-trino-read.ps1
```

CDC 作业确认已完成初始快照并保持 `RUNNING` 后，执行确定性变更，再读取 Bronze 当前状态：

```powershell
# 在隔离 source-postgres 容器中执行 artifacts/r1-fixture/postgres/002_cdc_mutations.sql
powershell -File scripts/r2-trino-cdc-read.ps1
python scripts/r1-fixture.py verify-cdc `
  --fixture artifacts/r1-fixture `
  --result artifacts/r1-fixture/results/r2-cdc.json `
  --report artifacts/r1-fixture/r2-cdc-report.json
```

`verify-cdc` 同时校验 UPDATE、DELETE、INSERT 后的当前主键集合与金额，不能只用作业仍在运行或 checkpoint 成功作为 R2 增量验收。

```powershell
python scripts/r1-fixture.py reconcile `
  --fixture artifacts/r1-fixture `
  --spark-result artifacts/r1-fixture/results/spark.json `
  --trino-result artifacts/r1-fixture/results/trino.json `
  --report artifacts/r1-fixture/reconcile.json
```

只有 Spark 和 Trino 都匹配 manifest 且两者 SHA-256 相同，命令才返回 0；当前 checkout 的跨引擎回归报告为 `artifacts/r1-fixture/reconcile.json`，状态为 `passed`。该报告来自复用现有隔离卷的运行回归，`acceptance-manifest.json` 的 `freshRebuild=false`；空环境硬门禁需要单独保留 `freshRebuild=true` 的报告。

通过 `scripts/r1-rebuild.ps1 -RunAcceptance` 时，脚本还会生成 `acceptance-manifest.json`。该文件只记录门禁状态、三个引擎的结果摘要、Flink Job ID/终态、fixture/BOM/connector/Compose/表契约的 SHA-256、是否清卷重建和当前 Git commit，不写入密码或完整 Compose 环境变量。只有 `freshRebuild=true` 的记录才是空环境硬门槛证据。

也可以使用一键 R2 PostgreSQL smoke（默认不重建源库，只执行隔离变更并在 finally 中取消远端作业）：

```powershell
powershell -File scripts/r2-postgres-cdc-smoke.ps1 -Confirm:$false
```

需要从空源表重建时，显式增加 `-ResetSource -Confirm:$false`；脚本会输出并写入
`artifacts/r1-fixture/r2-postgres-cdc-report.json`（MySQL 为对应的 `r2-mysql-cdc-report.json`），包含源类型、初始快照行数、Job ID、checkpoint、重启后的第二个 checkpoint、变更是否已提交、当前状态对账和脱敏失败分类。只有重启后再次产生 checkpoint，才允许进入变更验收。
同一脚本通过 `-SourceKind mysql` 支持 MySQL，或直接运行 `scripts/r2-mysql-cdc-smoke.ps1`；MySQL 报告默认写入 `r2-mysql-cdc-report.json`。

需要一次性验收两种首批 CDC 源时运行：

```powershell
powershell -File scripts/r2-cdc-acceptance.ps1 -RestartTaskManagerAfterSnapshot
```

该入口顺序执行 PostgreSQL 和 MySQL，任何一个源的 snapshot、checkpoint、重启后第二个 checkpoint、变更和 Trino 对账未通过，合并报告都会是 `failed`，不会把部分成功标成整体成功。报告默认写入 `artifacts/r2-cdc/r2-cdc-acceptance-report.json`。

2026-09-23 14:09–14:10（Asia/Shanghai）在当前隔离 R1 栈使用 `-ResetSource -RestartTaskManagerAfterSnapshot` 重新验收通过；PostgreSQL 与 MySQL 均为 5 行最终状态、5 个唯一主键、无重复 ID，且重启后继续产生 checkpoint。R1 JobManager 已配置 10 次、10 秒固定延迟恢复窗口，覆盖 Docker Desktop 上 TaskManager 冷启动超过单次重试间隔的情况。

## 受控重建和恢复

```powershell
# 只生成 fixture，不触碰服务或卷
powershell -File scripts/r1-rebuild.ps1

# 启动隔离 R1 项目
powershell -File scripts/r1-rebuild.ps1 -Start

# 执行跨引擎运行回归；不删除卷，因此不等同于空环境硬门槛
powershell -File scripts/r1-rebuild.ps1 -ForceFixture -RunAcceptance

# 完整 R1 空环境硬门槛；显式删除且只删除当前项目的独立卷
powershell -File scripts/r1-rebuild.ps1 -AllowVolumeReset -ResetVolumes -RequireFreshRebuild -ForceFixture -RunAcceptance -Confirm:$true

# 在不占用现有 R1 项目端口的情况下，启动一次全新隔离门禁（推荐回归方式）
powershell -File scripts/r1-rebuild.ps1 `
  -ProjectName aetheris-r1-empty-gate -PortOffset 1000 `
  -FixtureOutput artifacts/r1-empty-fixture `
  -AllowVolumeReset -ResetVolumes -RequireFreshRebuild `
  -ForceFixture -Start -RunAcceptance -Confirm:$false
```

`r1-rebuild.ps1` 默认只生成确定性输入，不覆盖已有材料，也不会停止或删除任何项目；覆盖材料需显式使用 `-ForceFixture`，删除卷还必须使用 `-AllowVolumeReset -ResetVolumes` 并通过 PowerShell `ShouldProcess` 确认。`-ProjectName` 和 `-PortOffset` 让空环境门禁与正在运行的 R1 栈并行，端口偏移也包含未在 `.env.r1` 中显式声明的 MySQL 默认端口。`-RunAcceptance` 会复用或启动指定项目，预检后先把隔离 `source-postgres` 的 fixture 表恢复为确定性初始状态（不会触及平台数据库），再要求 SQL operation 与对应 Flink Job 都以 `FINISHED` 结束，最后运行 Spark/Trino 读取和 checksum 对账；任一步失败都会返回非零退出码。`-RequireFreshRebuild` 若未同时给出 `-ResetVolumes` 会直接拒绝运行，防止旧 Catalog/对象存储数据冒充重建成功。

验收失败时不会生成成功的 `acceptance-manifest.json`，脚本会写出同目录的 `acceptance-failure.json`，其中包含 `phase`、隔离项目名、fixture 路径、时间戳和脱敏错误摘要。该文件只用于定位失败，不能替代 `status=passed` 的成功门禁报告。

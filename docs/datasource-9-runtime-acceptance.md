# 九类数据源真实接入门禁

本门禁把“目录支持”与“真实连接通过”分开记录。默认清单覆盖九类：PostgreSQL、MySQL、MariaDB、HiveServer2、SQL Server、Oracle、ClickHouse、Trino、SQLite。每条记录都必须通过 metadata 的连接测试接口执行真实 `SELECT`/等价探测；缺少凭据或服务不可达时为 `manual_review_required`，并标记 `retryable=true`；只有真实探测全部通过才是 `passed`，其它协议/驱动错误才是 `failed`。

## 执行

```powershell
$env:AETHERIS_SMOKE_POSTGRES_PASSWORD = '<temporary-secret>'
$env:AETHERIS_SMOKE_MYSQL_PASSWORD = '<temporary-secret>'
$env:AETHERIS_SMOKE_MARIADB_PASSWORD = '<temporary-secret>'
$env:AETHERIS_SMOKE_SQLSERVER_PASSWORD = '<temporary-secret>'
$env:AETHERIS_SMOKE_ORACLE_PASSWORD = '<temporary-secret>'

pwsh -NoProfile -ExecutionPolicy Bypass `
  -File scripts/datasource-external-acceptance.ps1 `
  -MetadataBaseUrl http://127.0.0.1:8082
```

报告默认写入 `artifacts/datasource-smoke/external-9-runtime-report.json`。报告只保留类型、状态、延迟和受限诊断，不写入密码或授权头。通过条件是九类全部 `passed`，并且 `missingRequiredTypes` 为空。当前环境缺失外部实例时仍会生成报告，不能把 `manual_review_required` 当作通过。

本地独立 Compose 门禁目前已真实通过 PostgreSQL、MySQL、Trino、MariaDB、ClickHouse、CockroachDB、SQLite 七类。HiveServer2、SQL Server、Oracle 没有被伪造为本地容器；要将九类门禁标为通过，必须在目标环境注入这三类真实服务和临时凭据后执行上面的入口。

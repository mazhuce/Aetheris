# 数据源 JDBC 运行验收

这份门禁补足了目录级支持与真实运行支持之间的差距。它不把“前端能选”或“JDBC 驱动在 classpath”当成通过，而是在 metadata-service 容器内建立临时数据源、执行真实查询、最后清理临时记录。

## 当前命令

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/datasource-runtime-acceptance.ps1 `
  -StartSmokeStack -TimeoutSeconds 180
```

`-StartSmokeStack` 使用 `infrastructure/datasources/docker-compose.smoke.yml` 启动固定版本的 MariaDB 11.4.3、ClickHouse 24.8.14.39 和 CockroachDB 24.3.6。所有端口仅绑定 `127.0.0.1`，项目名独立于 R1；脚本从容器环境读取临时凭据，不把凭据写入报告。若脚本自行启动 smoke 栈，默认会在结束时停止它；调试时才使用 `-KeepStack`。

## 2026-10-03 运行证据

报告 `artifacts/datasource-smoke/runtime-acceptance-report.json` 为 `status=passed`，九类均返回单行 `SELECT 1`（Derby 使用系统表探测）：

`postgresql`、`mysql`、`trino`、`mariadb`、`clickhouse`、`cockroachdb`、`sqlite`、`h2`、`derby`。

临时数据源记录已清理；报告只包含类型、状态、行数和清理状态，不含密码。SQL Server、Oracle、Hive/Spark 尚未在本地夹具中启动实例，仍按外部环境型连接管理，不能从这份报告外推为本机已验收。

## 失败语义

- 连接、查询或清理任一步失败，报告为 `status=failed` 并返回非零退出码。
- smoke 栈只使用独立测试卷；清理时不得使用平台/R1 项目名。
- ClickHouse 默认 URL 保持 `jdbc:ch:`，并显式使用 `compress=0`，避免精简镜像缺失可选 LZ4 native 依赖造成假阴性。
- SQLite 只在 metadata 服务专用 `/tmp` exec tmpfs 中运行 native 驱动；其它 Java 服务继续保留 hardened noexec tmpfs。

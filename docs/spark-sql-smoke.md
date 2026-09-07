# Spark SQL M0 验收

本验收只证明现有 Spark Thrift SQL 链路可用，不代表独立 Spark 应用提交、集群调度或多租户计算已经交付。脚本通过网关使用真实平台身份，从 `metadata-service` 的实际网络路径执行检查；它不会在打开页面时扫描或探测全库。

## 恢复依赖

先确认 WSL 中现有 Derby Metastore 与 HDFS `current/VERSION` 文件存在，再启动测试栈。恢复脚本不会格式化 HDFS、创建替代 Metastore 或覆盖仓库：

```powershell
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh preflight
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh start
pwsh ./scripts/windows-wsl-hive-relay.ps1 start
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh health
pwsh ./scripts/windows-wsl-hive-relay.ps1 health
```

脚本优先使用 Windows WSL 虚拟网卡地址；需要时用 `-ListenAddress` 指定一个已经从 `metadata-service` 容器验证可达的地址。将该地址写入 Spark 数据源主机字段。relay 是临时 TCP 转发，不配置系统自启或持久防火墙规则。`health` 只检查 TCP 依赖，不能替代下面的 SQL 和权限验收。

## 专用测试数据

生成器只写新文件，不执行 SQL，也不覆盖已有文件：

```powershell
python scripts/spark-smoke-test.py --fixture-sql artifacts/spark-m0-fixture.sql
```

输出会给出一个 16 位小写十六进制 owner marker。由运维在已确认的测试 Spark 环境中，用 Beeline 明确执行生成的文件：

```powershell
wsl -d Ubuntu -- env JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 `
  /usr/local/spark-3.5.6-bin-hadoop3/bin/beeline `
  -u jdbc:hive2://127.0.0.1:10000/default -n yafei --force=false `
  -f /mnt/c/Users/yafei/RA1NL0VE/Aetheris/artifacts/spark-m0-fixture.sql
```

SQL 使用 `aetheris_smoke_<marker>` 新库，建立 3 行源表和空的分区目标表，并写入 `aetheris.smoke.owner` 属性。数据库已存在时 DDL 失败；不要加 `IF NOT EXISTS`，也不要将 marker 改成业务库名。

## 只读验收

设置平台管理员身份和 Spark 数据源 ID。密码只从本机环境读取，不放在命令行报告、源码或 JSON 结果中：

```powershell
$env:INITIAL_ADMIN_USERNAME='已有管理员账号'
$env:INITIAL_ADMIN_PASSWORD='本机已有管理员密码'
$env:AETHERIS_SPARK_DATASOURCE_ID='5'
python scripts/spark-smoke-test.py --fixture <marker> --report artifacts/spark-m0-smoke.json
```

脚本检查真实登录、数据源类型、`SELECT 1`、专用库的 schema/table/column、owner 属性、已知 3 行数据、独立聚合和资源级权限。它创建唯一的临时 viewer 与窄权限授权，验证授权查询成功、移除 `query` 动作后两个公开查询入口均返回 403，最后删除临时账号再删除授权。清理失败会让验收失败并在报告中标明需要人工处理的资源。

## 隔离写入与重跑

只有明确增加 `--write` 才会创建一个 SQL workflow 任务。任务使用固定 marker 分区的 `INSERT OVERWRITE`，以同一业务日期运行两次，并检查 attempt 递增、结果与 Python 独立聚合相同且没有重复行：

```powershell
python scripts/spark-smoke-test.py --fixture <marker> --write `
  --keep-task --report artifacts/spark-m0-smoke-write.json
```

`--keep-task` 会保留测试任务和日志供人工审查；不使用它时，成功任务会在清理阶段删除。网络超时或提交结果未知时脚本不会盲目重提或删除任务，会保留任务并将报告标为失败。脚本从不删除测试数据库，因为底层 Hive 删除能力和回收策略需要由运维明确执行；验收完成后可按 marker 手工清理该专用库。

## 连接健康状态

数据源的 `status=active` 只表示配置启用。列表和详情只读取持久化探测结果，不会自动连接；“可用”来自手动的 `POST /api/metadata/datasources/{id}/test` 轻量 `SELECT 1`，结果 60 秒后显示“未知”。状态包括未知、探测中、可用和不可用，保存最近成功时间及脱敏失败代码。修改连接字段会使旧结果立即失效，迟到的旧探测不能恢复为可用。库表采集、业务查询耗时和 Spark 服务端口状态分别验证。

## 2026-09-07 运行证据

- WSL HDFS NameNode、DataNode、Metastore、Spark Thrift 和 relay 已恢复；依赖 `health` 四项 TCP 检查通过。
- relay 使用 `172.18.160.1:10000 -> 172.18.166.51:10000`，该地址从 `metadata-service` 容器可达；旧 Default Switch 地址 `172.19.64.1` 不可达，两个 Spark 数据源已同步到新地址。
- 两个 Spark 数据源均通过平台连接探测和 `SELECT 1`。
- [健康状态运行记录](../artifacts/spark-m0-health-2026-09-07.json)通过了持久化、过期、脱敏失败、并发抢占和配置变更 fencing 检查。
- [专用 fixture SQL](../artifacts/spark-m0-fixture-2026-09-07.sql) 已执行；只读主线通过登录、metadata、schema/table/column 和 3 行源数据检查。
- 授权/重跑主线曾因权限目录漏注册 `datasource.query` 及 JSONB 绑定问题中断；修复已写入代码和迁移，但本机认证镜像需要重建后再运行完整 `--write` 验收。报告保留在 `artifacts/spark-m0-smoke-final-2026-09-07.json`，其中没有凭据。

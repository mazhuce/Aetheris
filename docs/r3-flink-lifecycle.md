# R3 Flink 生命周期运行态验收

R3 是在隔离 R1 Compose profile 上验证受管 Flink streaming Job 生命周期的运行门禁。它不是“容器健康”检查，而是提交真实 Job、轮询 JobManager 证据并在每一步持久化可回归 JSON。

## 验收范围

`r3-flink-lifecycle-acceptance.ps1` 按以下顺序执行：

1. 通过 SQL Gateway 提交 `r3_orders_lifecycle` streaming Job，并记录 session、operation、Job ID。
2. 等待 completed checkpoint；重启 TaskManager 后要求同一 Job ID 产生严格更新的 checkpoint，且所有 vertices 连续 RUNNING 5 秒。
3. 调用 JobManager stop-with-savepoint，记录 request ID、savepoint location，确认 Job 进入 FINISHED。
4. 使用同一 savepoint 提交 resume Job，确认新 Job ID、稳定运行和新 checkpoint。
5. 生成 replacement SQL 摘要，执行第二次 stop-with-savepoint，再从该 savepoint 启动 replacement Job；记录旧/新 Job ID 和 SQL SHA-256。
6. 从隔离 Compose 网络断开 TaskManager 8 秒，再接回；要求同一 Job ID 稳定运行并产生严格更新的 checkpoint。
7. 停止 JobManager，探测远端不可达；不可达只产生 `unknown` 证据，绝不转为成功。恢复 JobManager 后根据同一 Job 是否可见记录 `auto_reconciled` 或 `manual_review_required`。
8. 无论成功或失败都取消本次隔离 Job、关闭 SQL Gateway handle，并写出报告。

运行入口（standalone）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/r3-flink-lifecycle-acceptance.ps1 `
  -ResetSource -TimeoutSeconds 240
```

报告默认写入 `artifacts/r3-lifecycle/r3-flink-lifecycle-report.json`。报告的 `schemaVersion=1`、`gate=R3-flink-lifecycle-runtime-gate` 可用于 CI 回归；`phases.submit/checkpoint/taskManagerRestart/pause/resume/upgrade/networkInterruption` 是阶段性证据，`reconciliation.unknownWasNotSuccess=true` 是失联安全门槛。网络阶段可用 `-SkipNetworkFaultInjection` 显式跳过，但生产回归不应默认跳过。

HA 运行入口：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/r3-flink-lifecycle-acceptance.ps1 `
  -ComposeOverridePath infrastructure/lakehouse/docker-compose.r3-ha.yml `
  -ProjectName aetheris-r3-ha `
  -GatewayEndpoint http://127.0.0.1:18186 `
  -JobManagerEndpoint http://127.0.0.1:18184 `
  -RecoveryJobManagerEndpoint http://127.0.0.1:18185 `
  -SkipNetworkFaultInjection -TimeoutSeconds 360
```

## 当前运行证据

2026-09-21 22:34（Asia/Shanghai）在双 JobManager HA overlay 中通过 R3：提交 Job `77efd2858381560b49c7c0bfc49e4e81`，完成 checkpoint、TaskManager 重启、pause/savepoint、resume 和 replacement SQL；主 JobManager 停止期间明确观测远端不可达，`unknownWasNotSuccess=true`，备用 JobManager 通过 ZooKeeper 接管后原 Job ID 重新可见，`reconciliation.mode=auto_reconciled`。报告 `artifacts/r3-lifecycle/r3-ha-flink-lifecycle-report.json` 为 `passed`。TaskManager 网络闪断仍由 standalone 报告单独覆盖；HA 本轮跳过网络注入以隔离领导者切换变量。

## 后端对账语义

2026-09-23 的受管 workflow API 验收见 artifacts/r3-lifecycle/r3-managed-stream-report.json（schemaVersion=2、status=passed）。它在真实 workflow、SQL Gateway、JobManager 和 PostgreSQL 上执行：同一 Gateway session 内的受限 setup SQL 与 streaming INSERT、自动持久化 Job ID/checkpoint、租约续期、重复运行和容量拒绝、pause/savepoint/resume、replacement SQL upgrade、断开 workflow 到 R1 网络后的 unknown（不是成功）及重连自动恢复，最后由 JobManager 取消并清理两个临时任务。三个连续 Job ID 不同，恢复和升级后的 checkpoint 均已落库；升级提交同时将任务定义推进到 definitionVersion=2，普通 resume 不改变任务定义。

复跑需要先启动 R1 与 platform 依赖，再使用 infrastructure/lakehouse/docker-compose.r3-workflow.yml overlay 启动 workflow，执行 scripts/r3-managed-stream-acceptance.ps1 -TimeoutSeconds 150。若平台数据库已经存在历史 `unknown` 流任务，验收应传入 `-FlinkEndpoint http://aetheris-r1-lakehouse-flink-sql-gateway-1:8083`，把本次验收的容量租约隔离到同一真实 Gateway 的独立 endpoint key；历史 unknown 仍保留并继续阻止原 endpoint 的重复提交。该 overlay 是本地验收桥接：workflow 加入隔离 R1 网络、使用本地 Maven JAR bind mount、把流任务容量设为 1 以验证拒绝路径；不是生产镜像或生产拓扑。脚本会暂时断开 workflow 的 R1 网络，异常路径会重连，并在报告中记录清理结果。

`FlinkStreamReconciler` 从 SQL Gateway 提交结果自动绑定 Job ID；Gateway 的 FINISHED 只代表提交确认，不代表无界 Job 成功。`FlinkJobLifecycleReconciler` 定时读取 Job ID、生命周期 intent、checkpoint 和 JobManager 状态：RUNNING 可恢复 unknown；明确 FAILED/CANCELED 才写终态；不可达或未知状态写 unknown。恢复时先解绑旧 Job ID 和 operation，防止旧轮询覆盖新 Job。2026-09-23 本地 workflow 数据库已执行 V26，增加 resuming 约束和活动 Job ID 唯一性。生产环境仍须部署 HA JobManager、持久化 HA 元数据并完成目标部署形态的故障恢复与长时 soak；本地验收不等于生产就绪。

## HA 验收边界

`infrastructure/lakehouse/docker-compose.r3-ha.yml` 提供独立的双 JobManager + ZooKeeper + Flink HA overlay，使用 `aetheris-r3-ha` 项目、独立网络/卷和备用宿主端口 18184/18185；主 JobManager 停止后由备用端点继续提供 Job 视图。2026-09-21 已完成真实 HA 故障注入，报告为 `auto_reconciled`；生产长时 soak、RTO/RPO 和多 TaskManager 压测仍属于 R13，不在本次 R3 门禁中。

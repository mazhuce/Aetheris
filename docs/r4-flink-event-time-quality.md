# R4 Flink 事件时间与质量运行验收

scripts/r4-flink-event-time-quality-acceptance.ps1 是 R4 的真实运行态门：它不只检查 workflow 字段或 metadata 接口，而是启动一个 PostgreSQL CDC → Flink SQL → Iceberg → Trino 的流链路。

验收顺序：

1. 在隔离 source PostgreSQL 中创建唯一的事件表和 CDC slot；
2. 通过 SQL Gateway 提交包含 WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND 的 streaming job；
3. 先写入两个按事件时间递进的记录，再写入一条已经落后 watermark 的记录；
4. Flink 使用 CURRENT_WATERMARK(event_time) 将记录路由为 accepted 或 dead_letter，Iceberg 保留每个 CDC 事件的 append-only 证据；
5. Trino 验证至少两个 accepted 和一个 dead-letter，并记录非空 watermark 行；
6. 默认把 late/dead-letter 统计及实际观测到的 watermark 回写 metadata Gold 质量观测，验证门禁 fail-closed；死信仍存在时保持阻断；
7. 取消 Flink job、删除 source 表，并清理临时 metadata 表；任何清理失败都会进入报告而不是静默通过。

前置条件：R1 lakehouse compose 已启动，包含 source-postgres、Flink SQL Gateway/JobManager/TaskManager、Iceberg REST Catalog、MinIO、Trino；若启用 metadata 回写，还需要运行中的 metadata 容器和平台 PostgreSQL。凭据只从 .env.r1 和容器环境读取，不写入报告。

运行命令：pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/r4-flink-event-time-quality-acceptance.ps1 -ProjectName aetheris-r1-lakehouse -MetadataContainer aetheris-metadata-service-1

`-ProjectName` 用于推导 `<project>-source-postgres-1` 与 `<project>-trino-1`，避免验收误连到其他 Compose 项目；JobManager 只接受 `RUNNING` 状态作为有效提交，`RESTARTING/CREATED/INITIALIZING` 会继续等待而不会被误判成功。

如只需要验证引擎链路，可显式使用 -SkipMetadataCallback；这不会被当作完整 R4 门通过，报告会明确标注 metadataCallback.status=skipped。机器可读报告写入 artifacts/r4-event-time/r4-event-time-quality-report.json。

这项验收证明的是实际 CDC、watermark、迟到路由、Iceberg 写入、Trino 对账和 Gold 回写边界；它不替代 R3 JobManager HA 生命周期验收，也不把静态任务配置当成流式运行证据。

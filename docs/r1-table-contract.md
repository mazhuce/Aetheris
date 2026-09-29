# R1 Bronze / Silver / Gold 表规范

机器可读契约位于 [`configs/lakehouse/r1-table-contract.toml`](../configs/lakehouse/r1-table-contract.toml)，发布门禁会同时校验本文和该文件，避免只改文档就绕过层级字段约束。

R1 只把 Iceberg format v2 作为实时湖仓表格式。三层表的职责、字段和演进边界固定如下；表名、快照和质量报告应记录在 Aetheris 的资产元数据中。

| 层 | 命名 | 写入者 | 必备字段 | 粒度与分区 | 消费边界 |
| --- | --- | --- | --- | --- | --- |
| Bronze | `<domain>.bronze_<entity>` | Flink CDC | 源字段、`_change_kind`、`_source_lsn`、`_event_time`、`_ingest_time` | 一行源变更；按事件日期分区，声明 `id` 主键并以 Iceberg v2 upsert 处理 UPDATE/DELETE | 只供 Silver 作业读取，不直接给看板 |
| Silver | `<domain>.silver_<entity>` | Flink/Spark 清洗 | 稳定类型字段、主键、`_first_seen_at`、`_last_seen_at`、`_is_deleted` | 一行当前或规范化实体；按业务日期或事件日期分区 | 供 Gold、质量规则和回放作业读取 |
| Gold | `<domain>.gold_<subject>` | Spark/Flink 聚合 | 业务维度、指标、`metric_version`、`_as_of` | 明确业务粒度；按消费日期分区，禁止无界全表更新 | 供语义层、Trino、看板和 API 消费 |

Bronze 保留源字段和变更元数据，禁止在入口处覆盖源值。Silver 负责类型稳定、去重、删除标记和迟到事件处理。Gold 只发布已经通过质量规则的业务口径，必须携带指标版本和快照时间，避免下游把不同批次混在一起。

## Schema 演进

- 新增可空列默认进入兼容变更队列，先写入 Bronze，再由 Silver 校验后发布。
- 重命名、删除、缩窄类型和主键变更属于破坏性变更，必须进入审批/隔离表；不能直接修改在线 Gold 表。
- 每次变更记录源 DDL、目标表快照 ID、审批人、发布时间和回滚快照。
- Iceberg 快照、Flink checkpoint/savepoint 与 Catalog 元数据共同构成恢复材料；仅备份 PostgreSQL 控制库不算湖仓恢复。

## 质量与保留

- Bronze 以可重放为第一目标，至少保留 CDC 事件和原始 `_source_lsn` 到约定保留期。
- Silver 必须通过主键唯一、事件时间有效、删除状态一致和金额/枚举类型校验。
- Gold 发布前必须通过行数、主键、空值、数值范围和跨引擎对账；失败时保留上一版快照，不覆盖在线版本。
- 快照过期、小文件合并和孤儿文件清理必须是带审计记录的维护作业，并提供 dry-run 与回滚窗口。

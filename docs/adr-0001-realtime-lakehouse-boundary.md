# ADR-0001：实时湖仓边界与 R1 验收

- 状态：提案，进入 R1 兼容验证后再批准
- 日期：2026-09-18
- 范围：Flink、Flink CDC、Iceberg v2、Spark、Trino、REST Catalog 与对象存储

## 决策

Aetheris 继续作为控制平面，负责连接、权限、语义、任务定义、审计和运行状态。Flink 负责持续计算和 CDC 写入，Iceberg v2 负责统一表格式，Spark 负责批处理、回填与维护，Trino 作为交互查询候选，REST Catalog 作为目录接口边界。业务代码不直接绑定某个 Catalog 厂商或对象存储实现。

首期只支持 Iceberg format v2。开发环境可使用 S3 兼容对象存储；生产环境必须保留客户自有 S3、OSS、COS 或 Ceph 的接入边界。Flink、Spark 和 Trino 的具体版本由 BOM 锁定，不能只按“组件可启动”判断兼容。

## 需要锁定的兼容证据

R1 必须记录 JDK、Flink、Flink CDC、Iceberg runtime、Spark、Trino、对象存储 SDK、数据库驱动和 connector JAR 的版本、来源、SHA-256、许可证与漏洞扫描状态。候选版本只有在以下链路全部通过后才能进入正式 BOM：

1. PostgreSQL 源表产生初始快照与增量变更。
2. Flink 将同一张 Bronze Iceberg v2 表写入对象存储。
3. Spark 读取该表并完成行数、主键和关键列校验。
4. Trino 读取同一表并完成相同校验。
5. 清空可重建环境后，按记录的版本和配置重新生成一致结果。

## Schema、快照和恢复边界

Bronze 保留源字段与变更元数据，Silver 承担清洗和类型稳定，Gold 承担对外语义消费。新增列默认进入审批后的演进流程；重命名、删除和缩窄类型属于破坏性变更，必须隔离、审批或回滚。快照、checkpoint、savepoint 和 Catalog 元数据都属于恢复材料，不能只备份业务数据库。

## 未决项

- REST Catalog 的具体实现及其对象存储认证方式。
- Flink 2.2、Flink CDC 3.6 与 Iceberg runtime 的最终组合。
- Trino 是否在实际 P95、并发和成本基准中优于现有查询路径。
- PostgreSQL 与 MySQL 首批 CDC 的日志保留、DDL 策略和重放边界。

## 退出条件

本 ADR 只有在兼容矩阵、端到端 fixture、故障重启和重建结果均有可复核记录后，才能从“提案”改为“批准”。

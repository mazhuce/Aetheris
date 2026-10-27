# Aetheris 下一阶段目标与执行计划

> 2026-10-26 接续入口：[新版执行目标](next-goals-2026-10-26.md)与[全项目复核](reviews/2026-10-26-project-gap-review.md)。下文保留 2026-10-22 的计划和执行记录，不代表当前整体验收已通过。

> 计划日期：2026-10-22  
> 依据：[当前功能支持报告](current-feature-report-2026-10-21.md) 和 [审查整改台账](remediation/2026-10-18-audit-plan.md)。

## 总目标

先把当前工作树收敛成一份**可验证、可恢复、可发布的批处理平台版本**，再扩展实时和高级分析能力。完成标准不是页面数量，而是每项能力同时具备真实执行、权限边界、失败状态、迁移、自动化回归和运行证据。

## 目标优先级

### P0：收口当前版本的安全与列表边界

1. **权限感知分页**：表、指标和资产目录统一使用稳定游标；OPAQUE 权限不能因候选超过 500/1000 条而返回假空集或截断成功。
2. **兼容接口止损**：旧全量数组接口保留兼容，但必须有明确候选/结果预算，超限返回可识别的 503；新页面不再依赖全量接口。
3. **当前工作树回归**：metadata、auth、gateway、workflow、ingestion、quality、serving、ai-agent 逐服务定向测试，然后再做前端 type-check/test/build。

### P1：证明跨服务执行链真正可用

1. 固定执行定义必须经过批准，执行快照、操作者、审批者、nonce、receipt 和数据源版本必须贯穿 SQL、transfer、quality、Spark/Flink。
2. 对 `unknown`、超时、撤权、服务重启和调度竞争做真实 PostgreSQL/隔离容器验收；未知结果不能自动重投。
3. 复跑 API key、资源授权、查询审计和数据源 reader/writer 隔离链，不能把历史分包报告当成当前冻结版本证据。

### P2：形成可交付版本门禁

1. 冻结源码和镜像/连接器摘要，重新生成当前 release context；旧报告只能作为历史参考。
2. 完成全新安装、数据库迁移、备份恢复、N-1 升级/回滚和 Compose smoke。
3. 解决 release strict 的基础镜像摘要阻断，并明确哪些外部引擎只作为补充证据。

### P3：再扩展产品能力

1. 看板全局筛选、联动、下钻、导出、订阅和查询取消。
2. Agent 权限感知检索、数字引用、评测集、成本预算和版本回归。
3. PostgreSQL/MySQL CDC 的完整运行态、Schema 演进、流任务恢复和容量 soak。

## 本轮执行范围

本轮先完成 P0 的第一项：指标管理页和指标资产页从兼容全量接口迁移到权限感知游标分页，并保留前端对空页、游标停滞、权限服务 503 和重复记录的保护。完成后执行前端定向测试与 type-check；如果验证暴露后端缺陷，再在同一主题内修复，不扩展到新 CRUD 或视觉改版。

## 完成判定

- 新页面不调用 `listMetricDefinitions()` / `listPublishedMetrics()` 全量接口。
- 页面不显示未经后端提供的“总数”；统计卡片明确标注为“本页”。
- 下一页可继续读取超过首个候选批次的数据；无权限的空页仍能通过 `hasMore + nextCursor` 继续。
- 筛选条件变化会丢弃旧游标；权限服务不可用时显示失败而不是成功空列表。
- `npm run test`、`npm run type-check` 和受影响后端测试结果写回本次工作记录。

## 后续记录规则

任何“已完成”必须同时记录源码范围、测试命令、运行 ID/制品摘要和未覆盖边界。未完成真实容器或外部引擎验收时，只标记为“代码已实现”或“模块测试通过”，不升级为生产可用。

## 2026-10-22 执行记录

- **P0-1 页面迁移已完成**：`modeling/metrics`、`assets/metrics` 改用 `listMetricDefinitionsPage` / `listPublishedMetricsPage` 与 `useVisibleListPage`；数据集编辑器改为输入关键词后服务端分页检索指标。筛选条件变化会重置游标，页面不再展示未经后端提供的全量总数。
- **前端验证通过**：最终工作树 `npm run type-check`；`npm test`（48 个测试文件、163 个测试）；`npm run build`；`git diff --check`（本轮文件）。
- **后端分页回归通过**：`mvn -pl services/metadata "-Dtest=MetadataListVisibilityRegressionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，13 项通过，覆盖 OPAQUE 大列表、稳定游标、权限服务不可用、搜索预算和已发布快照域一致性。
- **后端全量模块回归通过**：`mvn -s scripts/maven-mirror-settings.xml test`，13 个 Maven 模块全部 `BUILD SUCCESS`；该命令包含当前工作树的服务级单元/契约测试，不等同于隔离容器或外部引擎验收。
- **尚未完成**：真实隔离 PostgreSQL 的 `VisibleListPostgresIT`、完整多服务冻结版本回归和运行态发布验收；本轮不因此宣称 P0 或平台整体生产可用。

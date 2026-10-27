# 可见资源分页与批量授权契约

更新时间：2026-10-28。对应整改项：N11（W3/S07/P03）。

## 目标

列表查询必须先保持稳定的候选位置，再对候选逐窗口授权。不能先 `LIMIT` 再授权后把后页资源截掉，也不能把授权服务故障伪装成空列表。

## 共享契约

- `VisibleListPage<T>` 始终返回 `items`、`nextCursor` 和 `hasMore`；`hasMore` 与 `nextCursor` 必须一致。
- `VisibleListCursor` 使用版本化、不可变的 ID 倒序 keyset。首个请求冻结 `upper`，后续请求携带 `after`，避免翻页期间新增记录改变已读范围。
- 游标绑定资源类型、筛选条件哈希、页大小、用户 UID、用户名和 `accessVersion`。游标只是未受信任的分页位置，不是授权凭证；伪造、过期、主体或筛选不匹配必须拒绝。
- 每个候选窗口最多 500 条，单请求最多扫描 5000 个候选。每个窗口调用资源授权过滤，授权异常直接向调用方报告不可用（HTTP 503），不得返回空成功。
- 游标推进到最后一个实际扫描的候选。窗口中还有未消费候选或数据库仍有候选时返回 `hasMore=true` 和下一游标；没有候选时返回 `hasMore=false`、`nextCursor=null`。
- HTTP 页大小限制为 1–200。认证旧数组接口保留兼容入口，但超过 200 条会显式失败（503），不静默截断。

## 当前接线

共享实现位于 `libs/security-protocol/src/main/java/com/aetheris/security/protocol/pagination/`，覆盖以下服务端入口：

- Quality：规则、全局检查历史、单规则检查历史、隔离记录。
- Ingestion：任务、全局运行记录、单任务运行记录。
- Governance：治理策略、数据标准。
- Workflow：任务、全局实例、单任务实例。

前端 API 使用页对象；仍保留的数组读取通过有界分页收集，并受最大页数/行数限制。质量规则页和质量趋势页直接使用游标分页及页导航。

## 已执行证据

证据索引：[`output/audit-pagination-n11-20261028/report.json`](../../output/audit-pagination-n11-20261028/report.json)。共享扫描器 2/2，Quality 12/12，Ingestion 16/16，Governance 19/19，Workflow 2/2；前端类型检查通过，质量规则页与分页 hook 共 6/6。

这些结果绑定本次源码路径和提交前基线，属于组件/服务聚焦验证。尚未证明真实 PostgreSQL 中超过 1000 条记录的完整可见性、翻页期间新增/删除/撤权、真实权限服务 503、真实浏览器交互或 W6 冻结版本整体验收。未验证项仍阻断 N11/W3/W6 关闭。

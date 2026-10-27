# Aetheris 全项目问题与规划缺口复核

复核日期：2026-10-26。执行入口：[新版 agent 目标](../next-goals-2026-10-26.md)。原审查发现仍由[整改台账](../remediation/2026-10-18-audit-plan.md)管理，本文件增加 N01–N22，不替换 F01–F35、S/R/E/P 工作包或原失败记录。

暂停交接说明（2026-10-26）：下文保留进入本次复核时的事实与源码定位；随后 N01/N02/N04/N06/N16–N18/N21/N22/N23 已有不同范围的修复和分项证据，N03 仅前端接通。最新状态、尚未修复项和恢复顺序以[目标文档第 1 节](../next-goals-2026-10-26.md)及原台账最新补充为准。用户当前要求保持暂停；本次只是更新交接说明，没有重新执行全项目审查，也没有完成基线交付后的独立复查。

## 1. 结论与复核边界

V34 可以作为一个已完成分项验证的里程碑，但阶段 0–5 的可靠交付目标尚未完成。当前还有真实接线缺陷、用户可见功能缺口和必要验收空白，不能直接进入“仅增加新功能”的下一轮。

本次核对了九个业务服务、Gateway、前端路由与主要页面、共享安全库、数据库迁移、数仓脚本、CI/Compose/发布工具，以及四份产品规划。采用两路独立只读审查，主代理复核关键源码路径。它是全项目结构和关键路径盘点，不是逐行安全审计，也不是全平台重新运行验收。

本次未取得 `Aetheris_mimo.md`、`Aetheris_deepseek.md` 两份原附件；当前项目文件清单未找到它们。原发现范围沿用台账的 M/D 映射，没有声称重新读取、逐条复现了原报告全文。W0 保留来源缺口并要求从可追溯来源恢复；未恢复不能把映射完整当原文复核完整。

本次仅新增审查和目标文档、更新文档入口；没有修改应用、迁移、公共配置或既有证据。没有启动应用、容器、浏览器或付费模型，没有操作现有数据库、真实密钥、提交、推送或部署。

证据等级：**源码确认**表示当前调用链或数据流可见；**局部复现**表示明确列出的隔离探针实际运行；**待整合验证**表示已有实现但缺当前运行证据；**规划缺口**和**有意边界**均不能标为已交付。以下 P0/P1/P2 是本轮建议优先级，不是未经验证的漏洞严重性评级。

## 2. 进入本次复核时的真实基线

| 项目 | 核对结果 |
| --- | --- |
| HEAD | `8610de063d51a76d97f65d4d003f8b2ec93d307c`，工作区有大量既有未提交变更 |
| 复核开始时工作树指纹 | `7864f24d4fe9836fa062bc294a5257bd6327d6f9f5bbf2db93734e8368bf7b38` |
| 留存分项索引 | `artifacts/validation/current-worktree-20261024.json`，SHA256 `9fa0c5cabf4979eec5c5b33c8d1e62ca15674c5140b9cc89e0c20c6923787790` |
| 发布状态 | `partial`、`releaseEligible=false`、`frozenReleaseContext=false` |
| 迁移 | 122 份，九服务；Workflow 34 份 |
| 常规分项 | 1236 项 / 182 suites；范围为 protocol、servlet、Auth、Metadata、Quality、Workflow，不是所有服务全量测试 |
| PostgreSQL 引擎契约 | 31 项；V32→V33→V34 隔离升级，Auth 决策及引擎结果使用受控响应 |
| 六应用整链 | 119 项；真实 Auth/System/Metadata/Quality/Workflow/Gateway，SQL/quality 与实际业务 JDBC |
| 五应用引擎整链 | 92 项；真实 Auth 等五应用，Spark/Flink 为受控 HTTP peers；混合链为 Spark→Flink→SQL，不含同一链内的 quality |
| 脚本 | 369 项执行通过；另 25 项 Spark 脚本中 21 项执行通过、4 项跳过 |
| 数据库部署准入 | 122 源、1 正 23 负；使用明确的 unsigned synthetic manifest，不是正式签名发布 |

本次重新读取上述六份报告并逐字节校验其索引摘要，分别核对 768、489、669、626、156、137 个作用域内源文件，均无差异。这些是**留存结果的完整性复核，不是重新执行这些测试**。新增本文件和目标文档后，整个工作树指纹会改变；旧索引保留原字节及原运行含义，不能将其改签为新文档版本的正式验收。

本次机器记录：[只读检查报告](../../output/audit-project-review-20261026-fd8faf07c76b/report.json)，SHA256 `0fedb917915744444d227254c98b3add3cb4447db31907fe65f610c8d5d27a75`。记录包含源指纹始末一致、六份报告摘要复核、日志摘要及局部缓存探针。

重点代码位置另留存[52 份源码摘要清单](../../output/audit-project-review-20261026-fd8faf07c76b/reviewed-source-manifest.json)，SHA256 `4b34ba3f1c83cdacf684d6f7edfcfcd8558a33668dea3d796404561d037c35f7`；这是定位和完整性记录，不是新的运行验收。

| 本次实际检查 | 结果与含义 |
| --- | --- |
| `verify-flyway-migrations.py` | exit 0；122 份版本命名无重复，不等于旧库升级通过 |
| `check-document-integrity.py` | exit 0；复核开始时 80 份 Markdown；不证明文案事实正确 |
| `check-workflow-contract.py` | exit 0；六份 workflow 静态契约，不证明干净 runner/应用能运行 |
| `base-image-inventory.py --check` | exit 1；两项 MinIO 引用仍阻断 |
| `release-evidence.py validate --report <留存索引>` | exit 1；分项索引被正式发布验证器拒绝，符合要求 |
| 实际 React Query 内存探针 | 第二主体的查询回调调用 0 次，返回第一主体缓存；不是浏览器整链复现 |

## 3. 新增问题与遗漏

### 安全、可信状态和交付入口

| ID / 优先级 | 当前事实、影响条件与代码定位 | 必须达到的验收结果 |
| --- | --- | --- |
| **N01 / P0** 跨账号看板缓存 | 源码确认且局部复现。`DashboardWidgetContent.tsx:128` 的 query key 只有 datasetId；`main.tsx:15` 全局 QueryClient 的 staleTime 为 10 秒。登出与登录均 SPA 导航，没有清缓存、取消旧查询或绑定主体/权限代际。具体源码在 `apps/web/src/pages/analytics/dashboards/components/`、`auth/session.ts:36`、`layouts/components/TopNav.tsx:74`。 | 双账号访问同 datasetId、不同数据范围；同名账号重建；登出时旧请求尚未返回；权限撤销后缓存失效。任何路径都不得显示前主体数据，且旧响应不得重新填充新会话缓存。 |
| **N02 / P1** 旧写请求随新会话重放 | 源码确认。`apps/web/src/api/http.ts:42` 在 refresh 或 `/me` 成功后直接重发原 config，不区分修改请求，也不核对原主体。普通用户资料、RBAC、数据源等 API 有此调用路径；执行批准接口已有单独禁止重试配置，不属于此缺口。 | A 发出的写请求延迟返回 401，B 登录或权限代际变化后，不得以 B 执行原请求。保留同主体正常刷新，同时明确写入重试与幂等契约。 |
| **N03 / P1** 验收页历史假绿 | 源码确认。`apps/web/src/pages/development/acceptance/index.tsx:191` 无条件显示“九类运行门禁最近一次已通过”；`:141` 仅凭摘要 passed 映射成功。`api/acceptance.ts:67` 仅类型断言。两个 public acceptance JSON 仍为 2026-10-03、schema1、无 run/context 绑定。 | 当前、历史、失败、缺失、过期分开呈现；错源码、错制品、缺附件、请求失败和严格门禁失败均不能显示当前成功。健康探测不能替代实际业务验收。 |
| **N14 / P0 交付** V2 安装未接入默认启动 | 源码确认。`AuthV2SecurityConfiguration.java:93` 等无条件要求 registry/workload key，Gateway 还需 USER key；根 `docker-compose.yml` 十个 Java 服务无对应环境配置或挂载，数据库 roles override 也不提供它们。`ci.yml:130` 直接裸 Compose 启动。`scripts/security/installation.py` 只生成、明确不安装。 | 新目录/干净配置中，独立密钥、公开 registry、文件读取权限及最小数据库账号全部通过正式安装入口配置并启动；缺失配置要在预检中明确拒绝。不能依赖本机 JAR producer 注入的临时设置。 |
| **N15 / P1 交付** 部分 CI 依赖本机缓存假设 | 源码确认准备步骤缺口，干净 CI 尚未复现。`.github/workflows/platform-build.yml:48` 起直接逐模块 `mvn -f ... test`，没有先安装两个本仓共享库或使用根 reactor；服务 POM 引用 `com.aetheris:security-* :0.1.0-SNAPSHOT`。主 CI 的根 reactor 已存在，不能说所有 CI 都未接。 | 空 Maven 缓存/干净 runner 使用明确 reactor 或共享库准备；实际 Linux CI、Windows 脚本原始字节和 Python 支持版本通过，不以已有缓存或一次重试掩盖前次失败。 |

### 执行、湖仓和治理链路

| ID / 优先级 | 当前事实、影响条件与代码定位 | 必须达到的验收结果 |
| --- | --- | --- |
| **N04 / P1** Flink bounded/batch 冲突 | 源码确认。`TaskController.java:195` 与前端 `TaskFormModal.tsx:193` 接受 bounded/streaming；`WorkflowEngineMaterial.java:103/112` 只接受 batch/streaming。公开 bounded 任务不能批准，batch 不能公开创建。现引擎 producer 只创建 streaming。 | 统一创建、存储、批准、封存、恢复与 UI 模式，显式处理存量值；经真实公开 API 创建→批准→手动/cron/pipeline 执行有界任务。 |
| **N05 / P1** 新可信 Flink 生命周期未完成 | 有意拒绝旧接口，但替代功能缺失。`TaskExecutionService.java:552` 拒绝 sealed attempt 使用旧 savepoint/pause/resume/upgrade 路径；新 `WorkflowFlinkExecutor` 仅执行、对账、取消和状态。旧功能报告的完整生命周期支持已不准确。 | 原作业的 savepoint/pause/stop/cancel 保存独立生命周期操作及 fence；重新提交的 resume/upgrade 才建立新批准绑定和物理 attempt，停止未知不能新投。实际引擎验证恢复、升级、撤权、丢响应和回退。 |
| **N06 / P1** streaming 终态契约不一致 | 源码确认差异，实际后果待复现。`WorkflowFlinkExecutor.java:41` 对全部 mode 的 Job FINISHED 记 succeeded；旧 `FlinkJobLifecycleReconciler.java:120` 将无持久化生命周期意图的无界结束判为失败。当前 peer FINISHED 正例不能替代业务语义裁定。 | 明确 bounded 完成、streaming 意外结束、批准停止/升级的不同状态；验证不错误放行 DAG、不吞掉流任务异常，不从状态文字推断数据完整。 |
| **N07 / P1** 湖仓资源不在 Auth 调用目录 | 源码确认。`LakehouseTableController.java:29` 等要求 lakehouse_table；`RbacConstants.java:52` 的 metadata 资源清单和 `AuthV2SecurityConfiguration.resourceAllowed` 都没有该类型，`InternalV2Controller.java:115` 在用户决策前拒绝 caller。管理员亦不能绕过，Metadata 将不可用表现为 503。 | 为湖仓资源定义明确操作和上下文，真实管理员及获授权用户可访问；跨资源、撤权和未知类型拒绝。不能用允许任意资源或 mock security 修复。 |
| **N08 / P1** 维护/Schema 执行仍调用旧协议 | 源码确认。`LakehouseMaintenanceExecutionClient.java:25` 仍使用 X-Internal-Token 和旧 `/internal/lakehouse/maintenance/*`；Schema change 也复用。`WorkflowV2SecurityConfiguration.java:80` 只列两个 lookup，`WorkloadProofFilter.java:78` 拒绝未注册路径。 | 新增窄 v2 调用、实际主体/批准定义/资源范围/receipt；真实 Metadata→Workflow→Runner 的维护、Schema 变更和状态读回成功，撤权时零派发。禁止恢复共享令牌旁路。 |
| **N09 / P1** 发布审批/质量门禁旧接口漏接 | 源码确认且公开可达。`ReleaseGovernanceService.java:63` 的表审批调用旧 `MetadataImpactClient`；`:85` 的带质量门禁发布调用旧 `QualityExecutionClient.latest`。目标服务 V2 过滤不接受这些旧路由。 | 影响分析和批准后质量结果使用独立只读操作契约；绑定目标版本、质量结果来源与新鲜度。实际表审批和带质量门禁的发布成功，过期/篡改/撤权拒绝。 |
| **N10 / P1** 新执行链缺少血缘结果写入 | 源码确认缺接线。旧 `LineageEvidenceClient.java:59` 使用旧接口，但新 SQL/Spark/Flink fixedexecution 路径没有替代调用；sealed Spark 分支提前进入新 executor。不能误报为新路径仍在调用旧客户端。 | 同原 attempt/result/hash 幂等写入血缘；写入失败可诊断、重试和补偿，不伪造业务回滚。完成真实任务后能读到相应来源证据。 |
| **N11 / P1** 非 metadata 列表先截断后授权 | 源码确认。`QualityRuleController.java:162`、`QualityQuarantineController.java:37` 先取最多 200 再过滤；后续授权记录不可见。Ingestion/Quality/Governance 主列表还存在全量加载后授权。 | 扩展稳定游标和批量授权；>1000 项、仅后页有权限、翻页变化、Auth 503 均不假空或截断成功。复用已完成的 metadata 分页思路，而非重造它。 |
| **N12 / P1** Gold 质量门禁没有强制执行闭环 | 源码确认。`LakehouseQualityGate.java:13` 空观测集合返回 allowed=true；当前 goldGate 消费仅见 GET 查询，没有接入真实 Gold 发布/派发；观测未形成真实生产者→规则版本/表快照→发布凭证链。 | 明确必需规则；缺失、过期、错快照、unknown/failed 都阻止推进。实际流生产者写回合格证据后才放行；存在查询接口不能称为强制门禁。 |
| **N13 / P1** 语义下线缺少消费方依赖约束 | 源码确认。`MetricDefinitionService.java:243`、`LogicalModelService.java:201`、`SemanticDimensionService.java:183` 直接 deprecated/清 publishedVersion，没有跨数据集、看板、API 的影响阻断。 | 先列实际依赖；正在被发布消费者使用时阻断，或走明确、可审查的迁移/废弃流程。验证新旧版本、并发发布和下线后的消费结果。 |

上述 Java 路径分别位于 `services/workflow/src/main/java/com/aetheris/workflow/`、`services/metadata/src/main/java/com/aetheris/metadata/`、`services/auth/src/main/java/com/aetheris/auth/` 和 `services/quality/src/main/java/com/aetheris/quality/` 下的 controller/service/config/rbac/fixedexecution 对应目录，可按文件名直接定位。

### 前端操作闭环和状态表达

| ID / 优先级 | 当前事实与定位（均为源码确认，尚未浏览器复现） | 必须达到的验收结果 |
| --- | --- | --- |
| **N16 / P1** 依赖详情与修改目标错配 | `apps/web/src/pages/development/workflows/index.tsx:109` 无选择代际；A 的迟到依赖覆盖 B，`:223` 删除却使用当前 selectedId。 | A→B、A→B→A 乱序不串对象；详情数据、操作参数、选择代际成对绑定，不能误改 B 的依赖。 |
| **N17 / P2** 依赖图故障伪装为无依赖 | 同文件 `:84` 对所有任务无界 Promise.all；`:93` 将请求失败变成空上游。 | 有界并发或批量拓扑；部分 403/503 保持未知/失败，不展示完整拓扑结论。 |
| **N18 / P2** 表详情旧响应覆盖 | `apps/web/src/pages/assets/tables/index.tsx:72` 先换标题再无条件写字段/预览；内层 catch 把权限或连接错误吞为空数据。 | 关闭、切换、乱序、403、503、真实空结果分别正确呈现；旧 A 结果不显示在 B 下。 |
| **N19 / P2** ChatBI 重复与乱序提问 | `apps/web/src/pages/ai/chatbi/index.tsx:31` 无同步请求锁/代际；Enter、示例按钮可再发请求，旧回答无条件写回。普通聊天已有 epoch，不属于同一缺口。 | 固定免费替身覆盖重复触发、取消、超时、切换和卸载；只展示当前问题的回答，预算不被重复消耗。 |
| **N20 / P2** 看板失败永久加载 | `apps/web/src/pages/analytics/dashboards/stage.tsx:23/45` 与 `editor.tsx:84` 只 toast，不进入可重试错误态；路由切换也缺旧响应隔离。 | 404/403/503 退出加载且可重试；A→B 不出现旧画布；编辑/预览/发布视图遵守相同对象边界。 |
| **N21 / P2** 任务成功率失真 | `apps/web/src/pages/operations/tasks/index.tsx:46` 只将 success/failed 算终态，分母为零时显示 100%；全部 timed_out 时可显示零失败、100% 成功。 | 定义超时、取消、跳过和 unknown 口径；无样本不显示 100%；全超时和混合终态固定样例通过。 |
| **N22 / P1 产品边界** 发布记录冒充实际生效 | `apps/web/src/pages/development/releases/index.tsx:173` 承诺下游生效，回滚亦承诺停止生效；`ReleaseGovernanceService.java:77` 和 `ReleaseRecordController.java:142` 实际只推进发布记录状态。 | 默认先明确“发布登记”的真实语义；若要承诺实际发布，须有目标不可变版本、执行结果、消费方切换与回退证据。不能因为一个记录为 released 就宣称业务或部署已生效。 |

## 4. 原计划不能漏掉的剩余验收

| 原工作包 | 已有成果与当前仍缺的证据 | 新目标承接 |
| --- | --- | --- |
| S01–S03、S11–S12 | SQL/JDBC/状态/DTO/数据库最小权限已有实现和分项；缺九类实际源、TLS/出站、历史秘密数据、存量连接及物理引擎负例的同版本验收 | W1、W2、W4 |
| S04–S10、S13 | 签名主体、稳定 UID、API Key、刷新和撤权机制已实现；缺九服务/浏览器/全部执行入口的同冻结版本闭环，现又有 N01/N02/N07–N09 | W1、W2、W6 |
| R01–R03 | 严格日期、批准/generation、unknown/receipt/fencing已有分项；缺四类型同一 pipeline、真实引擎、进程杀死、双实例/重启及物理取消矩阵 | W2 |
| R04、P02–P03 | 表/指标游标、聊天 epoch、Spark/Flink 串行轮询已有实现；缺真实浏览器、全服务分页与 N16–N21 修复 | W3 |
| E01–E03、E08 | 严格拒绝旧证据及签验逻辑已实现；两项 MinIO 锁、干净 CI/V2安装、真实构建/扫描/签验及八类当前 producer 仍未闭环 | W4、W6 |
| E04–E06 | 有隔离 PG/数仓分项；明确旧版本 inventory、新装/升级/完整恢复、实际 JDBC/调度金额对账仍待验证，不能盲目 repair | W4 |
| E07、E09、P06–P09 | 文档/忽略/密钥工具部分完成；需校准过时支持描述、迁移 Runner 制品来源、Wrapper/版本/许可证清单、危险清理与临时产物边界 | W0、W4、W5 |
| P01、P03–P05 | Hikari/外部连接控制、requestId、查询历史 DTO、AI 服务端历史/工具预算已有代码；缺连接预算实测、告警投递/保留策略和真实 Auth 下免费模型替身整合 | W5 |

现行八类正式 producer 为：`r1-empty-rebuild`、`r2-cdc`、`r3-managed-lifecycle`、`r4-event-time-quality`、`r4-r5-control-plane`、`r5-maintenance-runner`、`datasource-nine-local-runtime`、`r3-ha-runtime`，见 `scripts/release-evidence.py:36`。隔离环境的 R3 HA producer 属现行门禁；长期生产 HA、容量和客户验收属于另外的证据范围，不能混淆，也不能为了缩 scope 删掉现行 gate。

## 5. 规划中未实现或仅部分实现的能力

这些条目必须保留去向，但不自动全部塞入下一轮可靠交付基线。推荐先 W0–W6，再按业务价值选择独立产品里程碑。

| 路线图 | 当前实际边界 | 去向、理由及残余影响 |
| --- | --- | --- |
| R1–R5 实时湖仓 | 已有脚本和控制面；真实引擎、生命周期、维护与强制 Gold gate 仍有上述缺口 | 当前已承诺路径和既有门禁在 W2/W4/W6 补齐；不以历史 R1–R5 passed 关闭 |
| R2 CDC 产品化、断点续传 | Ingestion 执行定义是批 transfer；sourceType/sourceConfig 字段不等于 CDC 作业、offset/checkpoint/Schema 状态已接入 | 后续独立产品包；先保持 PG/MySQL 范围，避免把所有数据源扩为 CDC。现行 R2 运行 producer 仍须执行 |
| R4 违规数据行隔离/重放 | `QualityQuarantineRecord.java:9` 明确违规行留源表；release 仅处理问题记录 | 当前文案和安全契约先校准；物理隔离/修复/重放另立批准与幂等方案，不能直接复制生产数据 |
| R5 物理 branch/tag/rollback | `LakehouseSnapshotRefServiceImpl.java:36/61` 仅更新平台库登记，没有物理 Catalog 操作 | 当前暴露操作明确登记性质；实际表生命周期列独立实现，需 Catalog readback 与并发写入验证 |
| R6 多表语义、派生/复合指标 DAG | `SemanticMetricQueryService.java:180` 单表受控聚合；derived/composite 类型标签没有表达式 DAG，结果缺统一 snapshot/freshness | 基线后独立实现；残余限制是单物理来源表，不能宣传自动 JOIN 或批流统一口径 |
| R7 看板 2.0 | 已有基础图表、版本/发布与刷新；globalFilters 字段不等于筛选联动。下钻、同比环比、导出、订阅等未形成闭环 | 优先全局筛选/查询取消，再考虑导出订阅；每项都延续数据权限、缓存和审计，避免先堆图表 |
| R8 可信 Agent | 已有稳定 UID、服务端历史、每次工具重验、轮次/耗时/token预算；缺逐数字引用、固定业务评测、提示词/模型版本治理与费用观测闭环 | W5完成现有安全与预算整合；引用/评测独立产品包，默认免费替身，不以多模型数量为目标 |
| R9 分析工作流 | 未见归因、质量诊断、看板草稿等完整可回放工作流 | 依赖 R6/R8 可信结果后再做；只生成建议/草稿，不自动发布、授权、删表或生产重跑 |
| 治理自动执行/责任工单 | `GovernancePolicyServiceImpl.java:10` 为 CRUD ServiceImpl；无策略评估、执行历史和责任工单状态机 | W5准确标基础治理登记；后续选一个有价值策略做评估→问题→责任人→复验，避免搭通用框架 |
| R10 企业身份/租户/KMS | API Key 生命周期、分布式准入已有实现；OIDC/SAML/LDAP、完整租户隔离、动态脱敏/KMS 未形成企业闭环 | 不重造已实现 Key 功能；企业集成按实际客户立项，不能把 workspace 字段当隔离证明 |
| R11 可观测/SLO/成本 | Prometheus scrape、requestId、平台通知已有基础；全链 tracing、错误预算、长期 soak 和成本核算未完成 | W5补当前诊断/通知/保留验收；长期 SLO 和容量依环境单列 |
| R12–R14 企业部署、HA、试点 | Compose、Flyway、备份和发布工具已有；Helm/Kubernetes、离线发行物、PITR/完整灾备、客户 GA 无当前证据 | W4/W6完成现有基线的新装升级恢复及严格发布；企业架构和试点不默认扩入本轮，也不能写为完成 |

旧功能报告将数据服务的“待验收”与“待补齐”合并描述，当前应拆开：`VersionedServingController`、`ApiKeyApplicationService`、`ApiSlaService` 已有实现，归为待冻结版整合验证；告警投递等缺口单列，不能重复规划重造 Key/配额功能。另一方面，旧报告对 Flink 完整生命周期的“支持”过强。应同时明确已有实现和未完成边界，不能只增加待办或只增加成功描述。

## 6. 收口规则

新增 N01–N22 由新版目标 W0–W6 承接；原 F01–F35 与全部 S/R/E/P 包继续逐项保留来源、影响条件、实现、证据、剩余工作和验收标准。记录“已实现但未验证”“当前阻断”“规划延期”时必须写明条件，不能合并成完成。

下次执行先核对实际工作树与本次基线的差异；本次文档新增本身不触发重跑全部旧测试，但实现、配置、迁移、制品变化后，相关证据必须重新生成。任何现有业务库变更、真实密钥轮换、提交推送或部署，仍需遵守原授权边界，先准备具体变更、验证和回滚。

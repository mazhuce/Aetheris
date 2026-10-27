# S08 固定执行：现状、最小增量与接线图

采集：2026-10-18，基于本轮读取的工作区源码。只读设计输入；仅本文为新增文件，没有改源码、target、SQL、配置或台账，没有启动服务或执行数据库验证。以下 `/internal/v2` 路由、字段和迁移均为建议，需 root 冻结后实现；不能作为 S08 已完成证据。总体方向沿用 [internal-service-contract.md](internal-service-contract.md)。

## 结论与需要先冻结的接口

2026-10-26接续：下文保留2026-10-18设计时的现状与建议。已批准SQL pipeline按workflow V31实现原UID/AV、完整批准计划、run/node/attempt/fence封存与来源回查；V32接入手动、cron及pipeline父任务的workflow-child quality，冻结子规则revision/hash，四项实时授权和原receipt对账见[子质量契约](workflow-quality-child-contract.md)。具体验证边界见[恢复契约](execution-recovery-contract.md)和[整改台账](2026-10-18-audit-plan.md)。自动恢复保留原actor；显式retry新建run并保存当前验签操作者，链接保留原run，不改写其invocation。下文“一概复制原actor”的早期建议由该明确新run规则替代；V33接入可信Spark/Flink来源，V34补齐引擎上游成功证据；真实Auth五应用92项与Spark→Flink→SQL链通过，引擎业务结果为受控HTTP响应。物理引擎IO隔离、全部四类同一pipeline及Flink新attempt生命周期仍未完成，见[引擎契约](workflow-engine-execution-contract.md)。

复用现有 run/attempt 与 R03 unknown/租约，新增可信 actor、仅执行字段的 revision/批准、封存 manifest，再接 metadata submit/status/receipt。不能只替换 HTTP signer：旧请求没有任何 run/fence 引用；当前 auth 权限接口需要短期 USER，长任务、cron、worker 无法靠已过期 USER 检查资源权限。

root 已认可提出 **仅 metadata caller 的 fixed-execution authorize 窄契约**，仍待最终字段冻结。metadata 必须先通过固定 URL 向正确来源作签名回查，核对持久化 actor/批准者/revision/fence/lease/实际执行参数，再向 auth 发结构化固定授权集合。auth 实时验证 UID/name/AV 和逐项资源权限，不能新增对任意 username 开放的 principal/check 或由来源随意 body.username 代替来源快照。

cron 最小绑定按 root 本轮确认：显式审批/启用时绑定当前 `TrustedUser` 的 UID/name/AV；不得从 body 选择 admin、猜存量创建者或自动读取新 AV 延长授权。密码、禁用或降权导致 AV 变化即停止，须本人或管理员重新明确审批/启用。该 cron actor 与定义批准者是两个概念，分别保留。

2026-10-26引擎接续：已单独实现workflow-only引擎窄授权并定点验证原UID/AV、批准者管理权限；Spark提交5xx/断线只向原runner核对，且提交后旧fence不释放容量。生产引擎来源与资源快照、Flink恢复和实际引擎仍未闭环，见[引擎执行契约](workflow-engine-execution-contract.md)；不能将接口/HTTP夹具通过当作这些入口已接通。

## 现有可复用证据与缺口

| 对象 | 已有字段和真实写入点 | 接入所缺，不能冒用的字段 |
|---|---|---|
| workflow task/instance/attempt | [TaskExecutionService.java:102](../../services/workflow/src/main/java/com/aetheris/workflow/service/TaskExecutionService.java:102) 读 Task 后 claim；[ExecutionClaimCoordinator.java:92](../../services/workflow/src/main/java/com/aetheris/workflow/service/ExecutionClaimCoordinator.java:92) 原子 `(task_id,biz_date)` upsert、attempt+1、fencing_token+1，active/unknown 拒抢；[同文件:136](../../services/workflow/src/main/java/com/aetheris/workflow/service/ExecutionClaimCoordinator.java:136) attempt 保存 task SQL/datasource/type/qualityRule/job/Flink 参数、bizDate 参数摘要、lease；`ExecutionClaim` :674 含 attemptId/no/fence/lease。 | `triggered_by` 仅名称，无 UID/AV、批准者。claim 目前使用事务外读的 Task，未在短事务校验执行 revision/CAS。`definition_version` 不是专用执行 revision。[TaskController.java:290](../../services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java:290) 对一般 update 也尝试加版本，且非管理员 retainEditable 会删不可编辑字段，不能把该值等同安全批准版本。 |
| workflow digest/快照 | [ExecutionClaimCoordinator.java:122](../../services/workflow/src/main/java/com/aetheris/workflow/service/ExecutionClaimCoordinator.java:122) digest 含 name、updatedAt、SQL、datasource、子定义等；:168 prepareSparkAttempt 在 queued 状态补 job/artifact/输入输出及参数证据。 | 展示名、updatedAt 使无关更新改变摘要；用分隔符拼字段并非无歧义结构编码。现有 digest 不含批准者/可信 invocation，也未覆盖实际 SQL timeout、重试等全部执行政策；不能用作授权 manifest。Spark 准备阶段仍会改 attempt，需增加独立 `manifest_sealed_at`，准备后封存再调远端，不能直接冻结整行所有生命周期字段。 |
| workflow pipeline | [PipelineExecutionService.java:97](../../services/workflow/src/main/java/com/aetheris/workflow/service/PipelineExecutionService.java:97) 在 createRun 事务内对固定 ID 列表逐项授权，:118 保存 `pipeline_node_run(task_id,sequence_no)`；:133 afterCommit 投递 worker，:217 调 task execute。 | 固定的是 ID 集合，不是定义版本；:207 worker 重读当前 Task。retry :87 重新算当前 DAG。需保存节点 revision/执行 manifest/hash/批准者、可信 parent invocation；调度消费封存节点，不能授权后再读新定义/新增依赖。 |
| ingestion run | [IngestionExecutionService.java:75](../../services/ingestion/src/main/java/com/aetheris/ingestion/service/IngestionExecutionService.java:75) 保存 source/target datasource、sourceSql、targetTable、writeMode、batchSize 的 execution_snapshot，requestKey、replayOfRunId；:100 claim 租约后 :103 调 metadata。 | 只有 run ID，没有 attempt_no/数值 fence；[ExecutionRecoveryService.java:16](../../services/ingestion/src/main/java/com/aetheris/ingestion/service/ExecutionRecoveryService.java:16) lease_owner 是进程 UUID，同进程所有 run 共用，不是每次执行专属 nonce。snapshot 无 revision/批准者/actor/dependency digest，未见数据库阻止改 snapshot 的触发器；发送参数仍来自 Task 对象，不从封存快照取。 |
| quality result | [QualityExecutionService.java:80](../../services/quality/src/main/java/com/aetheris/quality/service/QualityExecutionService.java:80) 保存 datasource/targetTable/ruleType/expression/threshold snapshot，:102 claim 后 :105 buildSql，:106 查询；R03 result ID、lease、unknown 唯一占用保留。 | 同样没有数值 attempt/fence；SQL 是保存 snapshot 后由当前 rule 对象生成，未保存生成器版本/实际 SQL hash；无 parent attempt 绑定、revision、actor UID/AV、批准者。需一次冻结规范化规则及精确生成 SQL，不能让不同版本生成器重建不同 SQL。 |
| R03 completion/recovery | ingestion [ExecutionRecoveryService.java:30](../../services/ingestion/src/main/java/com/aetheris/ingestion/service/ExecutionRecoveryService.java:30) 按 run+owner+未过期 lease CAS；:53 过期转 unknown；quality [ExecutionRecoveryService.java:29](../../services/quality/src/main/java/com/aetheris/quality/service/ExecutionRecoveryService.java:29) 锁当前有效租约后同事务写检查/隔离/摘要；V7 两模块 active 索引包含 unknown。 | 来源 run.save 与 recovery.claim 目前是分开事务，须合为短原子 claim（含 sealed snapshot/actor）；未知不可释放占用。metadata receipt 是新的跨服务副作用证据，不能只用来源状态覆盖它。 |
| Spark 发布/排队 | [ManagedSparkCatalogService.java:271](../../services/workflow/src/main/java/com/aetheris/workflow/service/ManagedSparkCatalogService.java:271) 发布，:398 digest 含 name/version、artifact/来源、inputs/output URI/参数/资源；[SparkBatchExecutionService.java:56](../../services/workflow/src/main/java/com/aetheris/workflow/service/SparkBatchExecutionService.java:56) prepare 后排队，:115 出队再次 `catalog.job(id)`。 | publishedBy 为名字且无本包批准者 UID/AV；排队重读 job 应改已封存 artifact/parameters/runner 数据。输入表名和 outputUri 不天然是 metadata datasource ID，不能编造 datasource.write 映射。Spark/Flink 不经过 metadata JDBC receipt，本轮三窄入口完成不能覆盖它们。 |

旧 run snapshot 文本默认 `{}`（ingestion/quality V7）不是可信历史批准，不能升级时补为 admin。workflow 老 attempt 有执行材料但同样缺批准者/可信 actor；旧 unknown 留对账，不能自动重放。现有 release_record 的 title/targetVersion/changeSummary/approved_by 名称审批也不是 task execution approval 的替代物（[ReleaseGovernanceService.java:108](../../services/workflow/src/main/java/com/aetheris/workflow/service/ReleaseGovernanceService.java:108)）。

## 所有已查到的触发链与可信 actor 写入点

| 入口 / 当前调用 | 必要接线 |
|---|---|
| 手动 workflow `POST /api/workflow/tasks/{id}/run`，[TaskController.java:333](../../services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java:333)；ingestion `POST /api/ingestion/tasks/{id}/run`，[IngestionTaskController.java:125](../../services/ingestion/src/main/java/com/aetheris/ingestion/controller/IngestionTaskController.java:125)；quality `POST /api/quality/rules/{id}/run`，[QualityRuleController.java:129](../../services/quality/src/main/java/com/aetheris/quality/controller/QualityRuleController.java:129)。 | 控制器从 Verified `TrustedUser` 捕获不可变 UID/name/AV/requestId，服务参数改 `Invocation`，禁止 String username 重载成为生产入口。服务验证批准 revision/enabled 后，在短 claim 事务落可信 actor+snapshot；metadata 在 JDBC 前再做实时 fixed authorize。浏览器 body triggeredBy 不入身份字段。 |
| Quartz workflow [SqlTaskJob.java:48](../../services/workflow/src/main/java/com/aetheris/workflow/job/SqlTaskJob.java:48) `schedule/scheduler`；ingestion [IngestionSyncJob.java:35](../../services/ingestion/src/main/java/com/aetheris/ingestion/job/IngestionSyncJob.java:35)、quality [QualityCheckJob.java:34](../../services/quality/src/main/java/com/aetheris/quality/job/QualityCheckJob.java:34) 使用 generation+scheduledFireTime requestKey，执行服务名称写 scheduler。 | 用持久化且显式确认的 cron actor tuple，不能用字符串 scheduler 作 auth 主体。Quartz 仅携 resourceId/schedule generation/fireTime，执行端重读 matching binding、当前 enabled+revision，旧版本丢弃。ingestion/quality 可复用现有 generation；workflow JobData 当前只有 taskId（[TaskSchedulerService.java:77](../../services/workflow/src/main/java/com/aetheris/workflow/service/TaskSchedulerService.java:77)），需补 schedule generation 和触发唯一键。每次准入 AV/execute 失效即拒绝。 |
| pipeline `POST /api/workflow/pipelines`、`/{id}/retry`，[PipelineController.java:67](../../services/workflow/src/main/java/com/aetheris/workflow/controller/PipelineController.java:67)、:83；后台 worker、resolved-node 恢复 [PipelineExecutionService.java:350](../../services/workflow/src/main/java/com/aetheris/workflow/service/PipelineExecutionService.java:350)。 | createRun 保存同一可信 actor 和确切节点版本集合。先在事务外做远端权限预检，短事务 CAS 图版本/定义版本并封存；实际 metadata 准入再实时检查，避免跨远端调用持有 DB 锁。后台恢复读取原 invocation、节点 manifest，不拿 RequestContext、不重算更多节点。显式 retry 的当前操作者单独记 requester，原 invocation/原 manifest 保留；不同人需新人工 run，不静默替换 actor。 |
| SQL 内部 retry [TaskExecutionService.java:617](../../services/workflow/src/main/java/com/aetheris/workflow/service/TaskExecutionService.java:617)，仅已确认可重试错误；unknown :646 停止。ingestion replay `POST /api/ingestion/runs/{id}/replay`，[IngestionRunReplayController.java:33](../../services/ingestion/src/main/java/com/aetheris/ingestion/controller/IngestionRunReplayController.java:33) 只允许 failed，但目前 :45 重新读当前定义执行。 | 自动 retry 新 attempt/fence、继承原 actor+sealed manifest，实时重验权限；只在 metadata receipt 明确拒绝且没开始时容许新 attempt。人工 replay 新 runId 和当前 requester证据，若声明是旧运行重放，使用原快照/原 invocation并要求当前操作者有 execute；要用修改后定义则明确为新 run，不伪称旧 run 重放。unknown 不接受 replay。 |
| workflow quality task [TaskExecutionService.java:594](../../services/workflow/src/main/java/com/aetheris/workflow/service/TaskExecutionService.java:594) → [QualityExecutionClient.java:33](../../services/workflow/src/main/java/com/aetheris/workflow/service/QualityExecutionClient.java:33)；旧 `POST /internal/quality/rules/{id}/run` [InternalQualityController.java:36](../../services/quality/src/main/java/com/aetheris/quality/controller/InternalQualityController.java:36) 信 body.triggeredBy。 | 替换为 parent instance/attempt/fence+manifestHash 引用；quality 向固定 workflow origin 回查，必须 taskType=quality、绑定 ruleId/revision、actor、lease 一致。原子创建唯一 `(parentOrigin,parentAttemptId,parentFence,ruleId)` 子 run，派生可信 actor，不接受 body.user/admin。父定义批准者须有绑定 quality_rule.execute；子规则批准者须有 datasource.query；actor 对父 workflow_task 和子 quality_rule 的 execute 都实时检查。 |
| quality old `GET .../{id}/latest`，workflow release publish [ReleaseGovernanceService.java:85](../../services/workflow/src/main/java/com/aetheris/workflow/service/ReleaseGovernanceService.java:85) 调 latest；[QualityExecutionClient.java:46](../../services/workflow/src/main/java/com/aetheris/workflow/service/QualityExecutionClient.java:46)。 | 这是全规则最近结果，不是本次 parent run 的执行确认。发布门禁若业务继续允许历史结果，必须固定 ruleRevision/resultId/freshness，单独只读 operation；不能将 latest 当子执行幂等或 receipt。 |
| Flink `flink-resume`、`flink-upgrade` 以及后台 JobManager reconciler；[TaskController.java:534](../../services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java:534)、:555，[TaskExecutionService.java:447](../../services/workflow/src/main/java/com/aetheris/workflow/service/TaskExecutionService.java:447) 同 attempt 重提，:475 读取当前 taskSql；Spark queued/reconcile 路径。 | 这些会发新远端执行，必须纳入 actor/revision/新物理 dispatch fence；不能对相同 receipt key 变 SQL。upgrade 是执行定义变更，replacementSql 需新批准版本；resume 使用已批准 snapshot。Flink/Spark 的远端能力与资源 URI 授权另属执行器接线，未闭合时暂停相关 cron/普通委派，不能从 SQL receipt 推导其安全。 |

### 启用/调度授权：已有门可复用但不完整

三模块 PUT 现有 `cron非null且实际改变`、`disabled→enabled` 分支要求资源 execute：workflow [TaskController.java:275](../../services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java:275)、ingestion [IngestionTaskController.java:77](../../services/ingestion/src/main/java/com/aetheris/ingestion/controller/IngestionTaskController.java:77)、quality [QualityRuleController.java:81](../../services/quality/src/main/java/com/aetheris/quality/controller/QualityRuleController.java:81)。保留 update/execute 双门，在该分支从当前 TrustedUser 写 cron binding。

还必须覆盖：(1) create 即 enabled+非空cron，显式 execute 校验并绑定创建者；(2) enabled 定义执行字段变化使旧批准/cron binding 失效，即使 cron 文本没变也不得沿旧主体启动；(3) 无 binding 的存量 enabled+cron 不启动，提供显式重新批准/启用动作，不能借修改 description 隐式绑定新 actor；(4) inactive actor/AV 过期时调度拒绝，不自主刷新 AV；(5) 并发保存用 expected revision/generation CAS，绑定与定义/调度 outbox 同短事务；(6) stop/展示编辑不重新绑定、不重批、不延长 AV。现有 ingestion/quality schedule_version 在 name/cron/status 更新增长（各 V6），它不是 executionRevision；不能用它重批执行定义。

quality [ServiceIdentitySigner.java:27](../../services/quality/src/main/java/com/aetheris/quality/security/ServiceIdentitySigner.java:27) 当前默认 service-principal=admin；[QualityQueryClient.java:80](../../services/quality/src/main/java/com/aetheris/quality/service/QualityQueryClient.java:80) 无请求身份时 fallback。新固定质量查询应 USER FORBIDDEN、只认来源 run 回查与 auth 决策，删除生产 fallback 调用；缺 cron/批准证据必须明确拒绝，不创建兼容 admin 主体。

## 建议冻结的 V2 固定目录（全部 POST、无 query）

下表 operation 由服务器 method+canonical path 决定；调用方固定 origin 配置，body 禁 URL、caller、username 身份替代字段。来源 lookup 对 actor/批准者有服务到服务输出，因此必须只允许指定 caller，不能当普通 API。

| 接收服务/路径 | server-fixed operation；允许 caller | 严格 body / 签名输出 |
|---|---|---|
| metadata `/internal/v2/executions/sql/submit` | `metadata.fixed-sql.submit`；workflow | `instanceId,attemptId,fence,executionRevision,manifestSha256,parametersSha256,execution{datasourceId,sql,timeoutSeconds,bizDate}`；回 `receiptId,origin,operation,runId,attemptId,fence,state`，202。 |
| metadata `/internal/v2/executions/transfer/submit` | `metadata.fixed-transfer.submit`；ingestion | `runId,fence,executionRevision,manifestSha256,execution{sourceDatasourceId,targetDatasourceId,sourceSql,targetTable,writeMode,batchSize}`；同型 receipt 输出。 |
| metadata `/internal/v2/executions/quality-query/submit` | `metadata.fixed-quality-query.submit`；quality | `resultId,fence,executionRevision,manifestSha256,execution{datasourceId,sql,generatorVersion,timeoutSeconds}`；同型 receipt。不能使用通用 agent/query 的 USER 权限或自报身份绕过。 |
| metadata `/internal/v2/executions/status` | `metadata.fixed-execution.status`；workflow/ingestion/quality | `receiptId,runId,attemptId?,fence,operation`；caller 必须为 receipt.origin，仅返回自己的状态/计数/安全错误，不再执行 JDBC。 |
| workflow `/internal/v2/executions/lookup` | `workflow.execution.lookup`；metadata，另允许 quality 的 parent 专用投影 | `instanceId,attemptId,fence`；metadata仅接SQL投影；quality仅接taskType=quality且自身rule绑定的父投影。来源自行查持久化 active attempt/fence、租约，输出下述 manifest；禁止按 caller body 选择主体。 |
| ingestion `/internal/v2/executions/lookup` | `ingestion.execution.lookup`；metadata | `runId,fence`；从 run sealed snapshot+有效 lease 输出固定投影。 |
| quality `/internal/v2/executions/lookup` | `quality.execution.lookup`；metadata | `resultId,fence`；从 result sealed snapshot 输出，带可选 parent引用及父manifestHash；不能临时从当前 rule 重新拼定义。 |
| quality `/internal/v2/executions/workflow-child/submit` | `quality.workflow-child.submit`；workflow | 仅 parent instance/attempt/fence/manifestHash、绑定 ruleRevision；quality回查 workflow后建唯一子run，回 childResultId/状态202。不接 triggeredBy/SQL/任意rule选择。 |
| quality `/internal/v2/executions/workflow-child/status` | `quality.workflow-child.status`；workflow | parent引用+childResultId；只返回绑定该父的结果，不用全规则 latest。 |
| auth `/internal/v2/permissions/fixed-execution-authorize` | `auth.fixed-execution.authorize`；仅metadata | metadata 已验回查后，传固定 origin/operation/run/fence/revision/manifestHash、actor tuple、批准者tuple与有界 typed dependencies；禁止任意用户名权限查询、任意 actions/ctx。返回与manifestHash/运行键绑定的逐项decision及安全reason；metadata要求全部allow才进入 receipt执行。 |

固定 lookup 响应最小 DTO：`origin, resourceType/resourceId, runId, attemptId?, fence, status, leaseExpiresAt, executionRevision, manifestSha256, invocation{kind,uid,name,av,requestId}, approval{uid,name,av,approvedAt,definitionSha256}, execution{...}, dependencies[], parent?`。只有 identity 类型允许 actor 字段；入口请求不接受该主体作证据。response signature 验 caller/receiver/nonce/status/rawBody。lease 来自来源 DB 时钟，receiver 对时钟偏差保守拒绝；已 unknown/终态/失效fence 不新准入。状态读取允许查询 unknown，lookup投影中不得把 unknown 标成active。

用户手动接入的短 USER 仅用来建立 invocation，可记录原 proof hash 供审计，不能长期保存 bearer/JWT 作为 worker 凭据；fixed调用 USER FORBIDDEN，由 workload 和已持久化快照构成来源证明。metadata auth调用的 actor字段只能取刚验过的响应，不能取submit body。metadata→auth 窄契约的授权断言权归 metadata；来源服务 DB+私钥完全失陷不在此回查方案能防御的范围，不能宣称独立审批签章。

## 最小字段与方法变换

**定义：** 三定义表新增独立 `execution_revision BIGINT`、`execution_definition_json JSONB`、`execution_definition_sha256 CHAR(64)`、`approved_by_uid/name/av`、`approved_at`、`approved_revision`。保存时服务器只白名单提取执行字段，排序键、严格类型、明确 schemaVersion 的结构编码再 hash，不以字符串拼接或 JSONB 任意显示顺序作摘要。SQL保留原字节不trim；null/default 明确规范化一次。display owner/name/description、cron/status/last result 不入执行摘要。状态只影响准入，调度绑定独立。

执行字段集合：workflow SQL(type,datasourceId,taskSql,timeoutSeconds,maxRetries,业务日期替换版本)；quality 子任务固定 ruleId+ruleRevision；Spark/Flink additionally 封存实际artifact/参数/runner或endpoint、读写目标、资源/超时/流语义，不能只hash引用ID。ingestion(sourceDatasourceId,targetDatasourceId,sourceSql,targetTable,writeMode,batchSize,transfer版本)；quality(datasourceId,targetTable,ruleType,expression,threshold,SQL生成器版本,timeout)。sourceType/sourceConfig当前MetadataIngestionClient不发送；若将来执行器消费它们，也必须加入版本字段，不先假称它们已参与执行。

**批准：** 新/改执行字段只在审批者当前可信身份和底层权限验证后 CAS 批准，执行字段变更原子 revision+1 并清批准和cron binding；展示编辑不改 executionRevision/批准者。最小实现只接受“当前仍批准的revision”，旧revision retry明确409，若未来要继续执行旧批准版本再单独引入版本目录，不含糊地重读新定义。存量允许 revision存在但 approved_revision/UID为空，默认不可执行；清单逐项人工重批，不填历史 owner/admin。

**invocation/snapshot：** workflow attempt、pipeline run/node，ingestion run、quality result 增 actor UID/name/AV/kind/requestId、executionRevision、批准者副本、manifest JSON/hash、sealedAt、metadataReceiptId；source运行键和阈值/生成SQL/解析日期一起封存。三服务 `claim(ExecutionManifest, Invocation, expectedRevision)` 短事务：锁/CAS当前定义批准版本、写actor、写sealed snapshot、抢租约；不跨HTTP/JDBC长事务。运行状态/lease/heartbeat可以更新，sealed执行/身份/批准字段禁止UPDATE（DB触发器与应用双守卫）。每个retry新attempt/run，在事务里复制原sealed manifest并校验仍可执行，不能在callback才读当前定义。

**fence：** workflow保留现有数值fence。ingestion/quality新增 `execution_fence BIGINT NOT NULL DEFAULT 1 CHECK>0`，run/result永不复用为新执行，禁止在原unknown上增fence重投；新人工重放新runId。lease_owner继续本地worker完成CAS，lookup回显数值fence并核对未过期lease。最小fence常为1不是伪装已有attempt_no；receipt身份由caller+operation+runId+fence唯一，workflow再包含attemptId。远端回查不能因为换owner就为同run授权第二次副作用。

**cron：** 定义表增 `cron_actor_uid/name/av, cron_approved_execution_revision, cron_binding_generation`，生成量只随实际调度/绑定变更原子提升，workflow补调度generation/唯一fireKey，ingestion/quality复用现有scheduleVersion但将binding变更纳入触发器。QuartzData不存可由终端控制的actor。绑定来自上述显式动作当前TrustedUser，不是环境默认admin。禁用/撤权后不由scheduler查新AV续命。

**依赖批准检查：** actor只需要固定definition.execute，普通操作者不被授任意SQL query/write。批准者实时有效，workflow SQL至少要目标datasource.write，对同连接SELECT读取集合也需datasource.query；ingestion源datasource.query+目标datasource.write，replace含DELETE仍须专用write；quality datasource.query。父workflow quality任务批准者还需被绑定子quality_rule.execute，子规则批准者需其query。auth.datasource.write目录已存在，不得换成datasource.update。SQL引用表/行权限若不能可靠解析/映射，应明确当前授权粒度是datasource，依赖最小DB角色兜底，不能声称有逐表/跨catalog细粒度授权。未知依赖、空context、未解析源/目标拒绝。

## 长同步改 submit/status/receipt，不能把60秒签名延成作业时长

现有 [SqlExecutionClient.java:61](../../services/workflow/src/main/java/com/aetheris/workflow/service/SqlExecutionClient.java:61) HTTP读超时 `(taskTimeout+30)s`；[MetadataIngestionClient.java:27](../../services/ingestion/src/main/java/com/aetheris/ingestion/service/MetadataIngestionClient.java:27) 为3,630,000ms；[QualityExecutionClient.java:25](../../services/workflow/src/main/java/com/aetheris/workflow/service/QualityExecutionClient.java:25) 为120,000ms。不能直接换成 SignedHttpClient 同步调用（硬上限60秒），更不能延长proof TTL或自动重发POST。

1. 来源短事务claim+seal→固定submit。metadata验workload/nonce后固定源回查、auth逐项授权，原始执行body与快照逐字段及规范hash一致；校验默认值、bizDate、timeout也一致。
2. metadata短事务 INSERT唯一receipt `queued`，存全部不可变manifest/来源证据/执行body hash；事务提交后交有界worker。队列容量和拒绝结果明确；不能只有进程内任务丢失却返回已接受。`queued`持久化可被单个workerCAS领取；必须先持久化 `executing`，再开JDBC，重启时executing转unknown，不再领取执行。
3. worker只消费receipt sealed数据；开库前可再复验来源有效lease及auth（排队期间可能撤权/过期），然后执行。超过定义deadline、lease不可靠或停止无法确认：unknown，不假失败也不自动重放。
4. 202响应带稳定运行键/receiptId；客户端用新的nonce POST status，原submit响应丢失只能按唯一来源运行键查receipt，禁止换nonce重新执行。重复submit相同运行键+相同hash返回原receipt/409，改变hash/revision/SQL拒冲突。nonce409本身不是安全重试证明。
5. 完成结果持久化与source CAS可重试“保存结果/读取状态”，不能重试JDBC。数据库已commit而receipt完成写失败保留unknown；[JdbcService.java:249](../../services/metadata/src/main/java/com/aetheris/metadata/service/JdbcService.java:249) transfer的目标commit与metadata自库事务不原子，不能承诺exactly-once或用本地rollback推断远端没提交。
6. 来源线程不必占满HTTP等待：保存receiptId，后台reconciler只查status并按原fence完成；workflow pipeline await/quality门禁按绑定receipt/childResultId等待。客户端断连不撤销已接受执行，取消需显式受权请求和确认，未确认保持unknown/运行。运行结果与通知分离，通知失败不得重复执行。

receipt建议列：`id,origin,operation,run_id,attempt_id(归一化非NULL),source_fence,execution_revision,manifest_sha256,raw_request_sha256,source_manifest_json,execution_json,status,worker_id,worker_fence,deadline_at,created/started/finished_at,result_json,safe_error,reconciliation_evidence`；唯一 `(origin,operation,run_id,attempt_id,source_fence)`，对ingestion/quality attempt_id固定0并CHECK，避免Postgres NULL唯一漏洞。metadata worker_fence只是本地完成CAS，不改变source唯一运行键。unknown/executing不得短TTL清理，resolved删除也须有业务保留边界和幂等墓碑，不应删除后可原键重新执行。

## root-owned 新迁移最小集合（建议四个schema各一份，版本号由root分配）

| schema | 增量内容 | 必须保留的边界 |
|---|---|---|
| workflow | task执行revision/规范定义/批准者+cron binding/generation；attempt actor/sealed manifest/receipt引用；pipeline run actor与node固定revision/manifest；按需将Spark批准者UID/AV及sealed dispatch纳入同迁移。 | 原attempt/fence/lease保留；旧行批准证据为空不能执行；Flink同attempt重新提交不能覆写sealed manifest，要新dispatch fence/明确新批准；缺失子定义映射不猜。 |
| ingestion | task revision/批准者+cron binding；run actor、sealed snapshot/hash/revision、fence、receipt；immutable触发器；V6调度生成触发器纳入binding变化。 | 保留V7 unknown占用/删除保护、requestKey/replay链，不能使旧unknown恢复running。 |
| quality | rule revision/批准者+cron binding；result actor/sealed manifest/SQL/hash/fence/receipt、parent引用唯一；immutable触发器；调度binding变化。 | 保留quality本地quarantine/result原子完成；旧actor字符串不升级成可信UID；去掉默认admin路径后缺证据明确阻止。 |
| metadata | execution_receipt表、唯一键、状态CHECK、claim/完成CAS及索引；只读/写入角色最小授权。 | 已有nonce表复用，不再造身份库；unknown不删除；runtime不能改sealed证据；新增表授权需同步roles与fresh receipt/gate，不能只迁表不测权限。 |

auth新窄授权operation可用已有用户/权限表，不因实现它而新增通用principal数据库；如要保存明确的cron审批证据，归各定义服务的绑定字段，auth只实时判断。新列由服务端专用DTO写，禁止直接暴露Entity让客户端伪造。审批变更/封存触发器与runtime角色UPDATE列授权需要一起审阅，触发器不能令合法生命周期更新失败。

## 可执行次序与验收

先冻结10条窄路由/DTO、actor/cron重批与旧revision拒绝策略；再做四迁移及真实PG约束；接来源claim/snapshot/回查与精确执行参数；实现metadata receipt与有界worker/status，再接auth窄判定；切三类客户端、pipeline与quality子运行；最后关旧execute/transfer/agent-quality共享token入口，未接的Spark/Flink/维护路径列为未完成，不能fallback。SQL语法、连接、出站和最小DB角色校验都继续保留。

必须真实PG/真实签名HTTP验证（本轮未运行）：

- 同定义并发更新revision原子递增，展示/last-result更新不提升revision、不更换批准者；执行变更清批准/cron；并发审批CAS冲突不能批准错版本。runtime改sealed快照/actor/批准者失败，而心跳、结果、quarantine正常。
- 伪造body actor/approvedBy、来源A引用B run、UID/name不匹配、AV变更、禁用、撤execute/批准者query/write、源目标互换、改writeMode/timeout/bizDate/SQL/生成器版本都在JDBC前拒绝；普通操作者只有definition.execute正常成功，任意SQL和相邻资源失败。
- cron绑定来自实际TrustedUser，缺绑定/存量/改AV/改定义未重批停止；普通update-only不能改cron或启用；旧generation、重复fire、删除重建、并发保存不产生重复执行；scheduler不能自行续AV。
- pipeline快照后改依赖/定义，worker不得执行新节点或新SQL；retry/后台resolved continuation保持原invocation，旧revision按冻结策略拒绝；quality子run按parent唯一，替换rule/parent/fence和latest冒充本轮结果失败。
- 两metadata副本并发同运行键只一次JDBC；不同nonce同键仍不执行第二次；NULL attempt归一化约束有效；worker重启executing→unknown，不自动重投；queued受控领取；坏/无响应签名不能消费status；所有跨服务响应绑定实际status/body/nonce。
- 目标commit后切断响应、receipt完成写失败、进程kill、source lease过期、迟到source CAS、queue满和网络超时均不自动再写；unknown人工对账含停止确认/证据和审计，保留幂等键。临时PG最小角色拒绝额外UPDATE/跨schema权限，迁移/roles receipt更新和容器/网络/数据清理独立证明。

重要未知：Spark/Flink定义到实际datasource/URI权限映射、长期流生命周期授权/新dispatch fencing、maintenance/schema变更相反方向调用及发布latest质量结果的业务新鲜度政策，尚未由当前代码提供完整答案。它们不阻止先封闭SQL/transfer/quality三个metadata边界，但必须留在S08剩余清单，不用固定任务方案替代未确认业务授权。

# 资源权限契约与显式读取迁移

日期：2026-10-18。本文描述本地修复的目标契约、保守边界与部署前审阅步骤；不是生产迁移执行记录。

## 决策与资源模型

- 身份仍沿用现有网关签名；浏览器提交的 owner、triggeredBy、用户名或角色头不能授予权限。owner/创建人只是业务元数据。
- mayMutate 是网关/服务的粗入口检查：某服务存在写授权才允许请求继续。它不能替代具体资源的 action 检查。
- mayManage 是平台管理权限，不是普通资源修改权限；用户、授权规则、订阅管理、unknown 人工对账等仍需平台管理。
- 所有非管理员的数据 view/query/export 均要求显式启用授权。删除或禁用最后一个授权后，check、check-batch、visibility 均保持拒绝/空集合；不存在 viewer 全读回退。
- 管理员仍有全量资源默认权限，不被自己的范围授权意外收窄。授权服务不可用时拒绝；不采用跨请求长期权限缓存。

| 资源类型 | 真实主键 | 子记录权限 |
| --- | --- | --- |
| workflow_task | workflow.task.id | instance、attempt、日志按父 task 的 view；执行/取消按 execute |
| ingestion_task | ingestion.ingestion_task.id | run/重放按父 task 的 view/execute |
| quality_rule | quality.quality_rule.id | result、quarantine 按 rule.view；运行按 execute；隔离释放按 update |
| governance_policy | governance.governance_policy.id | 不从字符串 targetTable 推导 domain/workspace |
| data_standard | governance.data_standard.id | 不从自由文本 applyScope 推导资源继承 |
| dataset / dashboard | serving 中各自真实主键 | 两种类型独立；相同数字 ID 不互相授予权限 |

新增五种资源目录动作是 view/create/update/delete/execute；execute 仅适用于实际存在执行入口的类型，目录存在不代表新增业务端点。dataset/dashboard 另补 publish 目录。没有静默创建任何用户、组或角色的数据 grant。

流水线读需 target 与已持久化 node 全部 task.view；取消/重试需相应 task.execute。start/retry 在实际 createRun 事务内，对将要持久化并 dispatch 的同一份不可变 task ID 集合再次校验。依赖后续变化不会在 dispatch 时额外重算并增加节点。质量豁免、绑定远程 job、替换 SQL 的 Flink 升级、未知结果认领仍需平台管理。

## 当前委托能力与暂限项

已有、由管理员定义的固定任务/规则可以授予非管理员 view/execute，不能跨资源访问或运行。update 仅开放不会扩大访问面的展示、状态、调度字段：

| 类型 | 非管理员可更新字段 |
| --- | --- |
| workflow_task | name / description / status / cronExpression |
| ingestion_task | name / description / status / scheduleCron |
| quality_rule | name / description / status / cronExpression |
| governance_policy | name / description / status |
| data_standard | standardName / status |

执行定义字段变更返回明确 403，包含被拒字段；SQL、expression、目标表、源/目标 datasource、writeMode、qualityRule/jobDefinition、阈值、owner 等不属于以上白名单。先比较持久化定义，校验后在数据库更新对象中移除受保护字段，避免旧快照覆盖管理员并发修改；运行结果与调度版本等服务端字段不接受此更新写回。workflow 保存还使用 definitionVersion 条件更新，竞争失败返回 409。

workflow_task / ingestion_task / quality_rule 的 cron 实际变化，或 disabled→enabled 启用，还必须同时拥有该资源 execute。只改名称/描述保持 update 即可；停用仍允许 update，不把它误当新执行。ingestion/quality 更新省略状态时保留当前状态，不因更新默认值把 disabled 自动改为 enabled。

上述五种资源的 create 暂须平台管理。dataset 的 create/update 也暂须平台管理，因为 querySql/datasource 仍能改变数据访问范围。dashboard 的真实 ID 更新/发布授权保留。delete 必须有该具体资源的 delete 授权。定时触发沿用已保存定义，不把调度线程误当浏览器用户。

这是有意收窄的兼容边界，不能宣称完整的非管理员定义编写能力已交付。后续须补 datasource.write 的实际含义、目标对象约束及 S08 携带主体/操作范围的内部凭据，再开放执行定义编辑。内部共享 token 只证明服务边界，不能自动证明最终用户有权扩大目标数据访问。unknown 对账是高权限确认，不能用普通 execute 授权代替。

前端尚普遍以 canManagePlatform() 隐藏运行/更新按钮，且尚无仅基于签名当前主体的 self permission 接口。因此本轮是后端权限契约收口，不能宣称 S07 的普通授权用户界面可用性已经完成。后续由主代理补限定资源类型/ID/动作的 self check-batch 和对应前端门禁，本轮不新增该接口、不修改相关前端。

## 可信上下文

domain 上下文由 metadata controller 读取同一份持久化 Domain 实体，生成 domainId + domainCode；不存在的关联域返回空上下文并拒绝范围授权。调用方不能把终端 JSON/query 中自报的 domainCode 直接透传为授权事实。业务域 code 创建后不可修改。

auth 可按授权值匹配 ID 或 code；它没有 metadata 的 ID/code 映射，不能独立证明二者关系。此一致性由可信业务服务从同一实体构造保证。域 code 范围无法表达为数字 IDS，visibility 返回 OPAQUE，业务列表批量逐条 check。resourceId 与 dataScope 是 AND；不能把二者合成 IDS 并集。visibility 只考虑本次请求的 action，query 不自动变成 view。

显式传入空/不完整 dataScope 返回 400，不会把空范围解释为全类型授权；旧库中存在此类范围时，check/batch/visibility 均不授予访问。确需全类型授权必须在审阅后明确使用 null。

dataset/dashboard 当前没有 workspace 外键；不凭创建人或客户端输入伪造 workspaceId。新增/更新这两类带 workspace data_scope 的 grant 返回 400，说明需明确资源 ID。既有 workspace 范围 grant 保留供审阅，但在缺少真实 workspace 归属时不能访问这些资产。未来补外键及可信上下文、迁移和测试后才能开放继承。

serving 的 ServiceGrant.id 是管理规则行 ID，DataSubscription.id 是订阅行 ID；它们不是 data_service/dataset 资产 ID。相关管理 list/detail 使用 mayManage，避免同数字跨类型授权。模板/驱动类型目录只需有效身份，因为不包含用户数据定义；实际资产列表仍做逐 ID 过滤。

公开权限提示不能替代业务服务校验。本轮检查的权限决策入口为受内部 token 保护的 /internal/permissions；实际读取与写入接口必须自己构造上下文并校验。列表批量分块最多 500 个 ID，每个请求不复用其他请求的许可。

## 存量迁移与部署门槛

V8__explicit_resource_permissions.sql 只增加目录、删除 sys_viewer 角色的旧全读目录关联并提升 permission_version，不创建数据授权、不删除已有 resource_grant。自定义角色及显式用户/组/角色 grant 保留。历史 V3/V4 不改写，Flyway 校验和不变。

1. 在部署前导出用户/组/角色绑定、resource_grant、sys_viewer 的角色目录关联、permission_version，以及受治理资源 ID/名称清单；保存为经审阅的受控备份，不把含业务定义/凭据的清单提交 Git。
2. 明确每个使用主体实际需要的资源和动作，由负责人审核批准。优先授予精确 ID；只在明确业务需要时授予全类型范围。禁止用“所有资源授予所有人”恢复旧行为。
3. 先使目录迁移可用，再通过现有管理接口预置批准的精确 grant（旧代码期间可能仍保留原默认读取，不能把该阶段视为安全切换完成）。新五类目录可预置，不依赖临时放宽策略。
4. 在隔离环境复制经过脱敏的授权关系，验证 admin 保持管理、viewer 默认空、普通用户获授权资源可读/运行、相邻 ID 拒绝、撤销立即拒绝。记录审批清单与实际授权差异。
5. 维护窗口按 auth 目录/决策引擎和业务端资源过滤一起切换。上线前确认所有下游理解新五种类型；仅改网关粗入口不算完成。
6. 分批验收后移除临时权限；监控拒绝事件并核对预期，不因 403 直接扩大为全量 grant。

回滚不得直接恢复 viewer 全读。出现业务遗漏时优先回退业务功能或补已批准的精确 grant。确需恢复旧决策代码将重开已知读取缺陷，必须作为单独风险决策；数据库目录和显式 grant 保留可审阅，恢复前先核对权限版本及角色关联备份。生产数据库未在本地修复中修改。

## 验收用例

- 无 grant 的 viewer：check 拒绝 datasource.query；列表为空；最后一个 scoped grant 删除/停用后仍为空。
- 同一用户仅拥有 A.view/execute：A 定义/运行记录可读可运行；B 定义/日志/结果/重放不可读不可执行；owner/triggeredBy 相同不放行。
- grant 仅含 query 不提供 view；domain 编码范围可正常匹配真实实体，不丢成空 IDS；组合 resourceId+scope 不泄漏并集。
- 普通 update 改名称/cron成功，改 SQL/数据源/目标表/规则引用/阈值返回 403；遗漏执行字段不会清空或覆盖它们；管理员并发修改不被旧定义覆盖。
- pipeline 任一实际 node 无 execute 则不创建运行/node记录；读/重试/取消按完整实际集合；controller预检查不能替代createRun再次检查。
- dashboard ID 8 仅检查 dashboard.8，不检查 dataset.8；published slug 解析真实 dashboard ID 后再鉴权。dataset授予不能读取相同数字的订阅管理记录。
- workspace scope 在无外键模型上返回说明性 400，不能假装授权成功。
- auth 超时/异常/空响应拒绝；同请求复用判定，新请求撤权重新判定。真实运行结果未知的对账权限仍为 mayManage。

单元/集成测试实际结果由本轮交付记录报告；本文件不把计划、源码存在或未执行的验收当作测试通过。

## 本地实际验证记录

2026-10-18，JDK 21.0.7 / Maven 3.9.10，offline Maven。以下为实际执行结果，均未跳过或删除既有业务测试；IT 单独执行，不计入单元总数。

| 模块 | 单元测试 | 失败 / 错误 / 跳过 | 执行范围 |
| --- | ---: | --- | --- |
| auth | 108 | 0 / 0 / 0 | 全量 |
| workflow | 169 | 0 / 0 / 0 | 17:21 全量，含实际 pipeline 节点/父资源权限、调度与出站回归 |
| governance | 28 | 0 / 0 / 0 | 全量 |
| serving | 86 | 0 / 0 / 0 | 全量，含看板真实类型/ID与列表过滤 |
| metadata | 196 | 0 / 0 / 0 | clean test 全量，含可信 domain context 与 datasource 列表 |
| ingestion | 37 | 0 / 0 / 0 | 17:21 全量，含权限、调度执行权限、R03管理路由与删除409 |
| quality | 40 | 0 / 0 / 0 | 17:21 全量，含权限、调度执行权限、R03管理路由与删除409 |

ingestion/quality 本次17:21全量已覆盖主代理此前冻结的 R03 执行摘要事务修改；R03 真实库验收另由该工作包记录。本表不代表未来源码仍自动通过。权限 controller 当前哈希见下表。

auth 的 PermissionMigrationPostgresIT：1 个真实 PostgreSQL 用例通过，0 失败/错误/跳过。先执行全部 V1–V7，再种入精确 datasource grant 与自定义角色关联，再应用 V8：精确 grant 和自定义角色保持、sys_viewer 旧默认目录关联消失、新资源及 publish 目录齐全、permission_version 仅升一次。独立 postgres:16-alpine、随机容器/凭据、loopback 随机端口、tmpfs；没有使用已有数据库。

- runId：02499a636f5148a19923657166212588。
- 运行状态 passed；docker rm 后按本轮唯一 label 核验无残留容器，cleanup passed。
- 日志：services/auth/target/permission-postgres-it.log。
- 结果：services/auth/target/permission-postgres-report.json。
- JUnit：services/auth/target/surefire-reports/com.aetheris.auth.service.PermissionMigrationPostgresIT.txt。

失败基线保留事实：auth 新契约首轮 18 例中 6 失败；后续两次定点定位 visibility 分支和 domainCode 缺 domainId 提前拒绝问题，按协议向主代理升级并获批准修正。质量定义无授权列表用例先失败；ingestion/quality 未决删除用例各先失败（旧返回404），实现后全量通过。metadata 最初遭旧 target 两个非法 class 生成物阻断；主代理提供独占窗口后 clean 全量通过。没有将环境失败算作业务缺陷。

最后的调度权限补充在三个模块分别先出现一个预期失败（原更新不会检查 execute），实现后各自全量通过；新增启用拒绝、只改展示保留禁用、停用不要求 execute 等用例一起通过。

R03 新路由只读核对：/api/ingestion/execution-recovery 与 /api/quality/execution-recovery 均被既有 /api/** 签名拦截器覆盖；此路径不在任务资源 mayMutate 放宽段，POST 粗入口仍要求 mayManage。GET列表/详情及POST reconcile 自身再调用 RecoveryAdminGate.requireManage，主体为签名 RequestAttribute；gate 明确 500ms 连接 / 1500ms 读取超时，异常/空响应拒绝。删除controller先查 running/unknown 并返回说明性409；若预查与删除之间开始新执行，数据库55000保护异常也映射同一409。数据库触发器本身的真实验收属于主代理 R03 IT。

以下 SHA-256 是本轮测试后的关键源码快照，可供整合核对；不是整个仓库发布证明。

| 文件（仓库相对路径） | SHA-256 |
| --- | --- |
| services/auth/src/main/resources/db/migration/V8__explicit_resource_permissions.sql | 29225A82B029D1333182F7317FB3FDD0BDBF78EF1FBA02AAFE36B9DD2EFFB3F7 |
| services/auth/src/main/java/com/aetheris/auth/service/PermissionDecisionService.java | 6CBA58495F915A14A75E15E1D78A8792175BE292105EA21553E7B3D630DBC99F |
| services/auth/src/main/java/com/aetheris/auth/service/RbacAdminService.java | BAEB714D223A5215427C3640566077E80AB492A2150B0B3466251064D7173FD9 |
| services/workflow/src/main/java/com/aetheris/workflow/service/PipelineExecutionService.java | 13109C6FED7BA5149B06D8A7CC2D1692080D11F73E8E69CEE4562C533CB76CEF |
| services/workflow/src/main/java/com/aetheris/workflow/service/WorkflowAuthorizationService.java | BB24453C6040E7C0CDB05F0077684F1345BF3CFAADE76D7FBAD3332F9B271442 |
| services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java | 44784F53CA336DD86E51FFBF3C1C88D5AA7701C700447449A2FB4CD4139B1275 |
| services/metadata/src/main/java/com/aetheris/metadata/controller/DomainPermissionContext.java | BD4A8F2DB3BBFD96A3165BD8A2FAE3B38D56F1A71E97F254B7D41490FB05CE11 |
| services/ingestion/src/main/java/com/aetheris/ingestion/controller/IngestionTaskController.java | B4FF6C721B48B6BA7CFAFFD34756D22E579BD42BD312C10564E1B80B8CFF25E1 |
| services/quality/src/main/java/com/aetheris/quality/controller/QualityRuleController.java | B127934F0A91F31A82290219663D8DA56976EF06BF8099594C8EB61AFB8642AA |

这些验证不代表已经完成生产升级、Compose全平台启动或浏览器到数据库的全部端到端验收；生产迁移与存量grant审批均尚未执行。本轮未提交、未推送、未部署、未改真实数据库。

## 查询审计范围与可信关联（2026-10-19）

`GET /api/metadata/query-audit/summary` 保留既有 `metric.view` 入口权限；汇总范围另向auth实时请求 `query_audit.view`。此资源能力只允许metadata调用且只允许view，不扩展metadata的may-manage权限、写操作或相邻服务能力。当前active管理员由真实决策引擎放行；普通用户的metric/table/domain授权不等于全局审计授权。S13将本人范围从可复用username改为可信稳定UID；裸角色/用户名头、`global=true`、`ownerUid`或`username`参数不参与范围选择。权限依赖不可用返回503，不读取全局或本人摘要来掩盖故障。

普通同步查询审计从当前请求的 `TrustedUser` 取得actor_uid/requestId，且username必须与该证明一致；没有可信请求上下文时拒绝新增审计，不从外部 `X-Request-Id` 或同名账号猜测主体。metadata权限错误响应也采用该可信requestId，未认证时生成新UUID。API Key查询继续使用已封存admission内的owner/key/request证据，不混为USER请求，actor_uid等于已验证owner_uid。审计表只保存SQL的SHA256和有限固定错误码/提示，不持久化原始驱动错误。本人SQL历史保留原SQL以支持复制和复用，但必须按UID隔离；自动保留策略仍未完成。

审计写失败保留异常，并增加无用户/SQL/requestId标签的 `aetheris.query.audit.persist.failed` 计数；已完成的只读查询仍遵循原有旁路审计语义。该计数不等于告警已经投递；Prometheus告警规则、审计/执行历史保留及跨服务tracing仍未完成。

验证：metadata305项、auth176项全量通过；真实失败基线分别10项中5失败、1项中1失败已归档。`output/audit-query-audit-package/run-20261019-v1/archive-66beac01f452/delivery.json` SHA256 `5d3bd16bc3bd2a29e85fcb642aa0525c38eede46cf4f14a6bc9820408d476ec7`。一次性PG run `c34558bb8e114f8fb795f6013ce2e834` 的两项IT执行31份metadata版本迁移、重启0pending、普通runtime真实落库、本人/全局汇总与P50/P95、错误裁剪、权限丢失/非法key来源拒绝及失败指标；报告 `output/audit-query-observability/run-20261019-v2/report.json` SHA256 `0cb63fda92aaddf99a6339a21404c573ffb2491d341220dc6ff7cafbb7fc1917`。Flyway另有schema创建标记，不计入31份版本迁移；首次夹具误计此标记的失败保留。

真实五应用102项验收另见 `output/audit-published-key-v2/run-20261019-v1/report.json`，run `158c87fbe5be4103b6c49396376965b9`，SHA256 `fe554eb2d161d1771291b2f7e9f4231a45ee35b4837d3d2f0ddce59a90976dbe`。实际通过Gateway→metadata→auth读管理员全局、普通用户本人、伪造角色不扩权，另一管理员两次普通查询/拒绝分别落可信requestId与安全错误，业务表行数未变。该结果不覆盖浏览器或审计保留。两次PG及五应用独立核对自有PID/端口/精确Docker标签均无残留。

## 稳定UID与本人SQL历史（S13，2026-10-19接续）

上述305/176、两项PG和102项整链为V31历史分包，不能代替以下新源码验收。auth支持物理删除后重建同名账号；用户名即使已经验签，也不能证明旧数据仍属于当前UID。

- V32为adhoc_query_history新增owner_uid/request_id/error_code，为semantic_query_audit另增actor_uid；普通USER不能借用V31仅供API Key的owner_uid字段。只有原API Key记录中已存在的owner_uid可复制为actor_uid，旧普通审计和旧SQL历史不按用户名猜测回填。
- 本人历史的列表、单条删除与清理全部带稳定UID；AV仅用于实时准入，不绑定长期归属。改密后同UID可继续复制SQL，同名新UID不能读取或删除旧记录。公开DTO保留本人原始SQL，但所有历史驱动错误统一为固定提示，旧错误同样不回显。
- 新写历史必须正UID，历史UID与审计actor UID一经建立不能重绑，旧NULL也不允许猜绑。数据库触发器拒绝旧binary写缺主体的数据；这不能阻止旧binary的用户名读取，升级必须封流/停止旧metadata副本，完成备份、迁移和全部新副本切换后再开放相关流量，禁止新旧版本混跑。现有数据库及服务本轮未迁移。
- 手动清理每次最多500条，仅删本人的复用历史，不删除security audit、运行receipt、unknown、恢复nonce或其他服务数据；返回{deleted,hasMore}。前端只有明确hasMore=false才显示清空完成，缺字段或旧格式必须失败。当前列表明确只展示最近200条，不把读取窗口称为保留策略。
- JDBC查询/写入/transfer日志仅保留固定操作、数据源ID、经过格式限制的SQLState与数字vendor code；不记录驱动堆栈、SQL、URL或账号。即席/数据源/agent错误使用固定提示，可信requestId供关联。审计插入失败不能把已完成的只读查询改为业务失败。

当前证据：metadata历史8项中7失败、日志3项全部失败，均无error；实际PG个人审计新反例使3项中1失败，报告`output/audit-history-owner/run-20261019-v1/postgres-34737a2b1d994020ac95a6a435d095c0/report.json` SHA256 `20fd2dea367f4aba9c6e96e13fdaa0b455a85c021bdd92d5fef9c5289b0527b2`。新增测试首轮lambda变量名冲突及整合的Mockito泛型编译错误均保留为夹具失败，未算业务RED。新V32四项真实PG、metadata全量、前端和实际auth删除重建整链仍待接续验证。

后续仍需：实际数据库保留策略与有界清扫、告警实际投递/来源事务outbox、全服务关联；语义指标当前table映射重读不等于实际JDBC来源与audit原子绑定，需另行统一typed execution context。没有将这些缺项算作完成。

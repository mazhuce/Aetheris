# P05：AI 可信历史、运行预算与固定安全评估

2026-10-18，本地实现与验证记录。本包不包含前端、security/**、AgentToolClient、其他服务权限协议或生产部署。

## 输入与会话契约

HTTP /api/ai/chat 保留 conversationId/messages/dataMode 和既有 role/content/model 响应结构，处理方式改为有界异步。兼容旧客户端的 messages 字段仅取最后一条 user 问题；浏览器自报的 assistant/system 历史不进入模型上下文。最后一条不是有效 user 问题时返回400。消息列表最多50条、请求总文本最多20000字符、单条/末问题最多6000字符；ChatBI 保留单问句及原500字符上限。

模型历史来自已验签主体本人的持久化会话：最多最近20条 user/assistant，每条最多6000字符，含本次问题总计最多20000字符。数据库中其他 role 也不作为历史加载。这里的“可信”指来源由服务端控制，不代表历史内容不会包含提示注入；任何历史、模型回答或工具结果都不能授予数据权限。

V3__conversation_run_fence.sql 在 ai.chat_conversation 新增 active_run_id UUID / run_deadline TIMESTAMPTZ，以及二者同空/同非空约束。旧 V1/V2 未改；全仓迁移口径由92变93，已通知主代理与 delivery_review。

ChatRunStore 的事务边界：

1. 按 conversationId + owner 原子认领一个运行 UUID。已有未到期运行则拒绝，不追加第二条用户问题；过期可由下一请求接替。
2. 在同一短事务中读取受限持久化历史、插入本次 user、更新标题/时间。事务提交后才调用模型或工具。模型失败时 user 消息保留。
3. 回复写入在另一短事务中按 owner + runId + 数据库截止时间围栏校验；锁定后再次检查期限并插入 assistant，释放时仍校验同一代运行与期限。插入或释放失败整体回滚。
4. 取消仅清理匹配的 runId。旧运行不能写入回复、释放新运行的围栏或覆盖其他人的会话。进程崩溃无需后台自动重放；截止后新请求可接替。

模型/工具 I/O 不持有数据库事务。事务超时5秒。会话删除后，旧运行的回复不能重新创建孤立 assistant。

## 预算与容量

Spring AI 1.0.0 API 经本地缓存 javap 与真实本地 HTTP 替身验证。适配器使用 ToolCallingChatOptions.internalToolExecutionEnabled(false)，每轮显式调用 ChatModel，再调用配置中注册的 ToolCallback。未知名称/类型、异常参数、过量结果停止本轮。只注册原 DataTools，保留检索表/指标、结构、只读查询及 ChatBI 功能。

| 配置 aetheris.ai.budget | 默认值 | 含义 |
| --- | ---: | --- |
| model-calls | 6 | 一轮最多模型调用次数 |
| tool-calls | 10 | 一轮最多工具调用次数 |
| input-tokens | 128000 | 累计输入预算单位 |
| output-tokens | 4096 | 累计输出预算单位 |
| tool-result-chars | 12000 | 单工具结果容量，超出拒绝继续 |
| timeout-ms | 60000 | 从异步接收起累计总时限，含排队 |
| workers | 4 | 每实例固定工作线程数 |
| queue-capacity | 8 | 每实例最大排队数，满后立即429 |

每轮模型请求前计入系统提示、消息、工具定义、调用参数和结果的保守 UTF-8 字节估计及结构开销；供应商 usage 更大时补记差额。输出在发起模型请求前预留，单次 max_tokens 不超过1024及剩余总量。响应超出累计预算时停止，不继续工具/模型调用、不持久化 assistant。

这些单位是本地保守准入估计，不是特定 tokenizer 的精确 token 数，也不是供应商账单硬上限。供应商已开始的一次调用可能产生费用；服务不能承诺取消后供应商不计费。生产 DeepSeek 配置强制 NeverRetryPolicy，防止框架在单次计量内隐藏额外 HTTP 重试；已用本地500响应验证仅一个请求。

可配置 aetheris.ai.estimated-price.input-per-million / output-per-million；默认0表示不返回费用估计。配置后响应 usage 提供 estimatedCost，basis 固定 conservative_local_estimate，数值单位由配置方确定。它不是实时价格、账单或账户总支出限额。没有执行付费 API 调用。

既有按用户窗口限流保留。工作池与队列限制是每实例容量，不是全集群分布式配额；多副本的同会话并发由数据库围栏控制。

## 超时、取消与身份上下文

DeferredResult 处理容器超时、已发现断连/error及completion。超时设置取消标记并 Future.cancel(true)，返回504；容量满返回429。预算在每个模型/工具操作前后、回复提交前再次检查，排队过期的工作不进入业务调用。

worker 只获得已验签 AetherisIdentity、原签名头、受限 requestId 和路径的独立快照；不保留原 ServletRequest，也不复制 Authorization、客户端用户名头或父线程 RequestContext。每个 worker 显式绑定非继承 RequestContextHolder 与本轮预算，finally 清理。现有 AgentToolClient 因此仍能在正常数据工具调用时透传同一主体。

取消/截止后不再发起新的工具或模型请求，已知取消的运行不能写 assistant；数据库还会阻止过期或被替换运行的迟到写入。正在进行的模型/只读查询可能无法被 interrupt 立即终止，工作槽会保留到该调用返回；后续输出丢弃。模型 HTTP 默认连接1秒、读取15秒，既有工具客户端保留其有界连接/读取超时。

**观察限制：** DeferredResult 只能在容器实际观察到断连时处理。浏览器静默关闭、尚未发生服务端 I/O 的 socket 断开，不保证即时可见；本包没有前端显式 cancel 协议。不能声称检测所有浏览器取消，或远端查询已经被强制终止。即使断连尚不可见，总运行截止仍禁止后续调用及过期落库。固定评估中的断连用例触发的是实际 Spring MVC async error 回调，不是全浏览器/socket实测。

## 数据安全边界

模型请求 auth 表或未授权资源时，最终拒绝必须由 metadata 身份、授权、SQL与数据库角色边界执行；提示词中的“不要访问”不是安全控制。本包不修改该协议、不新增共享身份机制。

固定注入评估故意让模型请求 auth.auth_user + 未授权 datasource；真实本地 HTTP metadata 替身返回403，断言请求主体透传、工具只返回 error且没有获得数据。它验证错误与身份链路，不等价于生产数据库授权验收；跨服务真实安全边界由 S08/metadata 工作包另行验收。拒绝后模型编造答案仍可能发生，不能把文本答案当作后端查询成功证据。

## 实际验证

失败基线：ChatServiceTrustedHistoryTest 实际捕获到浏览器伪造 assistant 进入模型，1例失败后修复。最初 mock 重载编译歧义已定位并修正，未算作业务漏洞。现有业务测试未删除或跳过。

- AI模块全量：49例，0失败、0错误、0跳过。
- 固定离线评估：伪造 assistant/system 历史、跨会话拒绝、未知工具、循环工具、独立模型/工具预算、输入/输出超限、耗时/已知取消、费用估计标签、线程主体隔离、有界排队、backend403注入场景。
- SpringAiWireBudgetTest：使用缓存的真实 Spring AI 1.0.0 + 127.0.0.1 HTTP 替身，确认手动两轮调用/单次工具执行，以及生产禁隐藏重试。
- AiCancellationMvcTest：Spring MVC真实异步timeout/error回调；即使模型替身忽略线程中断，assistant complete 不发生。
- ChatRunStorePostgresIT：7个真实 PostgreSQL 用例，0失败/错误/跳过，覆盖V2→V3数据保留、同会话并发仅一次claim、本人会话、失败保留user、过期接替及旧UUID fencing、迟到/重复/删除后写入拒绝、受限历史、事务回滚，以及模型调用确实不在事务内。

最新独立 PostgreSQL 运行 da289d844fd84d18a8ff3bb9a71180de：status passed、cleanup passed。随机容器/凭据、loopback随机端口、tmpfs、postgres:16-alpine既有镜像；结束docker rm并按唯一label核验无残留。未接触真实数据库。

可重复命令（先设置正确 JDK21/Maven PATH）：

~~~powershell
mvn -q -o -f services/ai-agent/pom.xml test
& services/ai-agent/src/test/scripts/audit-ai-governance.ps1
~~~

证据保留于 services/ai-agent/target/ai-postgres-it.log、ai-postgres-report.json 与 surefire-reports。旧一次6例验收 runId=3e8cd1a6562d4c7888c2d98f29139670 亦通过并已清理；最终结论采用上述7例运行。

关键源码 SHA-256（本轮快照，不是发布证明）：

| 文件（services/ai-agent/ 内相对路径） | SHA-256 |
| --- | --- |
| src/main/resources/db/migration/V3__conversation_run_fence.sql | 868186318609B959E200AAC9887952431ED72FDEEE70C14BEC23E628758C74BD |
| src/main/java/com/aetheris/aiagent/service/ChatRunStore.java | 13B3B5815A2AD8B759E5C1DEA27F12BD254E7E39622076299C3680E2C947AF5C |
| src/main/java/com/aetheris/aiagent/service/ChatService.java | 096F9A668CC6A2E6085E3524C242D6F204F0B21F54591E364987F2C733C18AFF |
| src/main/java/com/aetheris/aiagent/service/AiRequestExecutor.java | 6862E46CCA72FC451F7859ECC67E7B539EAFDEB2D006F6F78B48EF015510668E |
| src/main/java/com/aetheris/aiagent/llm/SpringAiLlmClient.java | BF0DA1AB209ECFC429A57F4250F2594A57AD167D2259CE2AAB523624ED6D5708 |
| src/main/java/com/aetheris/aiagent/llm/AiRunContext.java | 6878C1B51AFECCDE698968D8EDA38033D53F691301E28A0CA98C4BDAE083085F |

本包未提交、推送、部署，未调用真实供应商；没有完成前端与全平台 Compose 端到端验收。先应用 V3 再启动新服务；只回退应用时可保留新增可空字段，不能删除包含运行围栏的新字段后继续运行新应用。

## 2026-10-19：稳定 UID 会话归属补充

本节更新归属与验证结论；上文 P05 日期、运行次数和源码摘要保留为历史快照，不能代替本次结果。旧代码按可重用的 `owner_username` 判断归属，同名新账号会继承旧账号会话。当前 HTTP 入口仅从 `TrustedUser` 创建不可变 `ChatOwner`，不接受 DTO、用户名头或 String 用户名桥建立主体。用户名只是展示快照；列表、消息、续聊、删除、运行认领及完成/取消 CAS 全部按稳定 UID。AV 不作为历史归属键：同 UID 改密/AV 更新后仍可访问自己的历史，旧 proof 仍由实时 session 校验拒绝。

V5 `conversation_owner_uid` 新增正数 `owner_uid` 与按 UID/更新时间的索引，不修改 V1—V4。数据库触发器要求新会话非 NULL UID，并禁止既有 UID 变更、清空，以及旧 NULL→任何猜测 UID 的重新绑定。V1 无归属的历史和 V2 曾猜为 `admin` 的历史均保持 NULL，不能按当前用户名、管理员或新账号回填。升级保留这些原始数据但从普通 API 隔离；本包没有提供恢复/认领接口，也不会删除旧 SQL/消息来掩盖归属问题。

部署必须停止或排空旧 AI 实例并阻断旧路由，完成迁移后只启用本版实例。**禁止新旧 AI binary 混跑**：旧 binary 仍可能按用户名读取，不会因增加一列而自动获得隔离；应用回退至旧 binary 也会重新开放旧归属风险。迁移顺序为既有 V1/V2 数据→V3 运行围栏→V4 nonce→V5 UID，不重写旧迁移，不删除现存会话。全库计数、角色收据和部署门禁由总集成包另行更新，本节不代表它们已验收。

运行边界继续保留：短事务按 `conversationId + owner_uid` 认领随机 run UUID、先提交用户提问再调用模型；完成同时检查 UID、UUID 和 live 数据库期限。取消只释放同 UID/同 UUID，不释放其他代运行；删除父会话使用带 UID 的原子谓词，并在事务内清理子消息，迟到完成不能复活已删会话。`Claim` 只能由存储认领路径构造。窗口限流改以 UID 为键，同 UID 改名/AV 更新不重置窗口，同名新 UID 不共享窗口。模型/tool/token 预算与有界物理 worker 保持原实现；本包没有新增跨副本持久每日配额，也不把预算估计称为供应商账单上限。

实际证据位于 `output/audit-ai-owner/run-20261019-v1/`，由主代理串行执行、子代理只读复核结果并按原始字节归档：

| 运行 | 实际结果 | 说明 |
| --- | --- | --- |
| postgres-64133f167d544e8390fdec428812156d | 6 errors，0 failures | 首次 fixture 缺 mapper bean，不能算漏洞 RED；原日志、XML、报告保留 |
| postgres-db4a18a5191c40e2bdf1bd066f49a233 | 6 项，4 failures，0 errors/skipped | 同名新 UID 列表/消息/续聊/删除越权被真实复现；同 UID 新 AV、失效旧 proof 两正例通过 |
| unit-d58d97c53c184655a3a2dc39783c3634 | 16 suites，63 项全绿，0 failures/errors/skipped | 包含原模型/工具预算、取消、实际本地 HTTP/Tomcat、线程上下文等回归 |
| postgres-a55d91c49acb4f5f860d62891dcf7c67 | 2 suites，9+11=20 项全绿，0 failures/errors/skipped | 实际 PG/Flyway/MyBatis/短事务，Ed25519/filter/MVC，离线模型与可切换 session 替身 |

实际 PG 覆盖分阶段 V1→V2→V5 的 NULL 历史不猜绑、新写非空与 UID 不可变、同名不同 UID 的四条 API 边界、同 UID 新 AV、伪造 DTO/旧用户名头无效、其他 UID 对物理 run 的 claim/cancel/complete 拒绝、重启存储/重复完成、期限与旧 UUID 围栏、事务回滚、模型调用在事务外及失败保留用户问题。复核当前全部报告输入摘要（普通117、PG119）和各49个 main class 摘要均匹配；原测试报告 XML 的 suite/count/hash 已逐一核对。PG 报告 `limits` 中残留的 “Six ... cases” 是旧描述，实际计数采用两个新鲜 XML 的 9+11，不改写原报告。

PG 运行报告确认独占 Java 进程退出、自有容器不存在、无卷残留、listener 关闭四项均 true，`aftercarePids=[]`。使用缓存 PostgreSQL 镜像和独立临时库，不接触既有库，不调用付费模型。本次证明库/数据库与 HTTP 过滤器边界，不是完整 auth 删除/重建应用部署、浏览器全链、正式发布或全平台通过；静默浏览器断连和远端实际终止仍受上文取消观察限制约束。

原始字节归档：`output/audit-ai-owner/run-20261019-v1/archive-uid-20261019-v1/manifest.json`。目录保存本次已测试 AI 源码、新 V5、main classes、普通/PG/RED 测试 XML 与原报告；每项分别记录源路径、原始 SHA-256 和归档 SHA-256，不进行换行转换，不生成 BootJar 或发布制品。V5 SHA-256：`AA1BAC9F816291FE6F83C02D35E248375F242389CB4F6C0A939AD29E21CF8FEF`。

# 接入与质量执行的恢复契约

`running` 表示本进程持有有效租约；`unknown` 表示缺少可信的完成结果。两者都占用任务/规则的并发位置。租约到期只会将运行改为 `unknown`，不会将其判为失败或允许自动重放。旧进程的完成更新需同时匹配运行状态、进程所有者与有效租约；过期进程不能覆盖对账结果。

租约有效期120秒，每30秒续期。后台补偿先处理过期项，再续期自己的有效项；不会复活已过期租约。过期状态、最近运行摘要与审计记录在同一事务中更新。数据库短暂不可达可导致保守的 `unknown`，这是避免重复写入的选择。租约不是数据库查询取消机制；人工解除阻塞前必须核实旧进程及远端事务已结束。

接入客户端的网络错误、5xx、空/无效响应、缺失或不合法计数均属结果不确定。400/401/403/404/422 的明确接收前拒绝可记为已知失败。响应丢失后使用不同幂等键也不能绕开 `unknown` 并发位置。相同幂等键只返回原运行；不会另建执行。目标库的跨服务 exactly-once 未被宣称实现。

质量查询完成后，结果和必要的隔离记录在同一数据库事务提交。质量服务不复制违规业务行；隔离表仍是诊断记录。隔离记录失败不会留下“已完成但无诊断”的结果。

新运行保存实际使用的执行定义快照，包含数据源ID、SQL/表达式、目标表及执行选项，不包含数据源账号密码。普通运行列表不输出快照。旧版本没有可靠定义快照时保持空对象，不能使用当前可变定义冒充当时执行内容。仍有 `running` / `unknown` 运行的任务或规则禁止删除，避免CASCADE删掉恢复证据。

新增两个服务的管理员端点：

| 方法/路径 | 行为 |
| --- | --- |
| `GET /api/ingestion/execution-recovery`、`GET /api/quality/execution-recovery` | 最多200条待对账摘要 |
| `GET /api/{ingestion,quality}/execution-recovery/{id}` | 对账所需的原始定义快照 |
| `POST /api/{ingestion,quality}/execution-recovery/{id}/reconcile` | 对一条unknown运行记录保存人工核实结果 |

端点要求网关可信身份且每次向auth核实平台管理权限；普通任务 `execute` 授权不包含人工解除阻塞。请求必须明确确认远端已停止，提供16至2000字依据；接入成功需给核实后的读取/写入行数，质量通过/未通过需给与原始阈值一致的违规数。质量未通过的对账在同一事务补齐隔离记录。竞争对账通过行锁串行化，只允许一次成功，并记录操作者、原状态、结果、依据和时间。审计表不随任务级联删除。

前端数据同步、质量趋势将unknown显示为“待对账”，只向管理员提供“核实结果”；表单先加载原始快照，要求明确选择结果与确认远端结束。后端仍是最终权限与状态校验边界，页面按钮不是授权依据。

V7迁移将升级前的running保守改为unknown并记录审计。上线应先停调度/停写、确认旧工作进程状态并备份；不要在旧实例继续执行时切换数据库。迁移不修改任何业务目标库，也不自动重放旧运行。

验证入口为 `scripts/audit-execution-recovery-acceptance.ps1`。它在一次性PostgreSQL演练V6→V7、租约、并发位置、旧worker、删除保护、人工对账、事务回滚和隔离一致性后清理。正常单元测试使用可控客户端；不调用外部数据接入或付费服务。真实部署停机切换和实际目标库对账仍需独立验收。

## workflow 的不确定结果与质量豁免（2026-10-25）

pipeline 派发后的异常、等待超时或中断、空结果、丢失的轮询对象，以及不同 attempt/fence 的返回，都不能证明物理执行失败。节点与 pipeline 保持 `unknown`，下游标为未执行；不会改变仍在运行的物理 attempt，也不会释放其资源。等待窗口使用单调时钟，避免系统时间调整改变期限。调用前发现任务不存在、停用或不支持的类型仍可明确拒绝。

质量豁免只适用于已完成且有失败结果凭证的质量门禁。凭证保存在当前 `execution_attempt.result_metadata_json`，包含 schemaVersion、kind、resultId、ruleId、passed/failed、违规数、阈值及完成时间；违规数和阈值必须非负且与结果一致。读取时同时匹配当前 instance、task、active attempt、fence、quality_rule_id 和失败终态。旧空凭证、未知/错误/取消/超时、缺失结果及数据库不可用均不能获得豁免。有效失败凭证仍须有当前业务日期的有效显式 waiver 才允许下游继续。凭证只证明该次质量结果，不替代父任务身份和子任务批准链。

Spark cancel 返回 `unknown` 时保留 `spark_resource_allocation.status='admitted'`。终态资源补偿也不能释放 unknown；只有后续确认 cancelled/succeeded/failed/timed_out 等物理终态才释放。测试同时验证未知取消时新 claim 被拒绝，以及确认取消后容量才被释放。

V30 `queued_task_instance_status` 修复真实 PostgreSQL 发现的契约缺口：执行协调器为 Spark 写入 `queued`，而 V4 的 logical instance CHECK 遗漏该状态。V30 仅将 queued 加入现有允许集合，保留其他状态及全部约束；不重写 V4、不修改历史数据、不执行 repair。验收先用原字节 V1–V29 建立旧库，重现原 CHECK 拒绝，再应用唯一一份 V30，核对29份旧 history 的 version/checksum/success 不变、pending=0；非法状态仍被数据库拒绝。该演练只证明明确 V29→V30 路径，不代替任意客户旧版本升级验收。

现有安装实施前先停调度与新提交，核实活动/unknown 任务，导出 workflow Flyway history 和约束定义，并保存可恢复的数据库备份；只有原 history 与仓库一致时才运行正常 Flyway migrate。应用回退可以保留此兼容的 V30 约束；如确需数据库整体回退，先确认新 queued 工作已停止且保存其证据，再恢复迁移前快照并核对原 history、约束和运行记录。不得直接删除 V30 history、把 unknown 改 failed、清空队列或使用 repair 伪造回退完成。本轮只在一次性数据库执行上述升级，未修改现有安装。

验证入口：`py -3 -B scripts/audit-execution-uncertainty.py --output output/audit-execution-uncertainty/<fresh-run>/report.json`。四项真实 PostgreSQL 测试使用普通 workflow runtime 账号，受控质量结果与真实状态/约束/容量表；输出绑定源迁移、源码、compiled classes、runId、脱敏测试报告与清理。它不证明可信执行入口、子质量 v2 来源回查、真实 Spark/Flink、九服务或最终发布。V30前的流水线仅有用户名和节点ID，相关历史报告不获得下述V31能力。

## 已批准 SQL pipeline 的来源与恢复（V31）

`PipelineExecutionStore` 从已验签请求取得原始 UID、username、accessVersion、requestId，明确保存 `kind=pipeline`。先对完整依赖闭包授权，再按稳定顺序锁定定义并封存实际批准 revision、规范 JSON、hash、批准者和拓扑计划；节点数最多512、计划最多16MiB。依赖/定义在预检与封存之间变化会冲突；后台线程不读取 RequestContext，也不根据用户名恢复主体。

run/node 的 actor、计划、节点定义和依赖证据封存后不可改；节点首次插入或补封存先锁父run，与计划封存串行。node 的 instance/attempt/fence 与 attempt 的 pipeline run/node/dispatch fence 在同一事务绑定，随后才可能调用 metadata。普通响应不返回计划 SQL、actor/批准者 JSON、worker ID 或私有依赖凭证。

公共 manifest 的 `parent` 继续仅供 workflow-child 使用，SQL pipeline 保持 null；来源父链由 workflow 数据库绑定及签名 source lookup 证明。lookup 同时检查当前 run/node、worker、dispatch fence、全部原始批准定义、依赖结果、物理 attempt 和有效租约。metadata 排队后、实际建连执行前再次回源及实时授权；不能只扩大 actor 枚举就放行。父租约按节点 timeout+120秒设置，物理 SQL 使用原 timeout+60秒预算，避免被初始120秒派发租约截短。

pipeline 依赖只接受同run冻结节点的原 attempt/fence 成功凭证；显式 retry 还可引用来源链上封存的成功节点。逻辑实例 `success` 对应物理 attempt `succeeded`，两者必须明确映射。其他运行、手动执行或同实例新attempt的成功不能替代当前节点凭证。unknown run 占用目标/业务日期位置，不能通过重新start绕过。

stale 转换的run/node在同一事务更新。自动恢复锁原run，只核对持久化的原attempt/fence、receipt与批准快照；仅对 `attempt_id IS NULL` 的未派发节点重新排队，不依赖展示文案。并发恢复只能产生一次新dispatch fence；全部原节点已成功时，可在最终run完成写入丢失后重新收口。新节点仍使用原UID/AV，经metadata实时授权；原主体被禁用、撤权或替换为同名新账号不能因此取得权限。

显式 retry 始终创建新run，记录当前请求的验签主体，并用 `retry_of_run_id` 指向保留的原run；不会改写原actor。完整批准定义与依赖图必须和来源计划一致，不能续跑时默默采用新定义；已确认失败/取消且依赖凭证有效才允许续跑。对新定义的执行需重新批准并新建完整运行。此处将“原run不可变”与“显式新run使用实际操作者”分开，替代早期接线分析中将所有retry一概复制原actor的未实现建议。

V31必须先核实旧history、备份和活动运行，再通过正常Flyway迁移，并依精确清单为workflow runtime开放新增5个SQL INVOKER helper；不授予跨schema EXECUTE、PUBLIC、SECURITY DEFINER或ALL FUNCTIONS。现有数据库只生成具体计划，不在本轮直接迁移。应用回退可保留新增兼容列；数据库整体回退需停止新工作、保存原封存运行/receipt证据并恢复迁移前完整快照，不能删除history或修补摘要。

V31来源约束验证入口为 `scripts/audit-pipeline-authority.py`，实际五应用SQL链入口为 `scripts/audit-workflow-sql-chain.py`；具体run、原始失败和证据摘要见整改台账。该节只说明V31 SQL节点；V32的quality父来源接线如下。混合Spark/Flink、全部故障注入及最终冻结版仍未完成，禁止退回旧字符串桥接来宣称可用。

## workflow quality父来源与原receipt恢复（V32，2026-10-26）

手动、Quartz和pipeline质量父任务均从验签主体或明确绑定的cron主体封存原UID/AV。父定义批准时固定子规则revision及hash；quality和metadata每次准入回查当前父attempt/fence、批准和租约，同时检查父执行者、父批准者与子规则批准者的相应权限。子任务唯一键绑定原父来源；重复提交只读原result，不新建业务查询。具体字段、路由及升级回滚准备见[子质量契约](workflow-quality-child-contract.md)。

丢失submit响应、断线、排队超时或不明确结果先保留unknown；恢复worker只读取原metadata receipt，不能重发SQL、调用latest、按当前用户名补权限或重新拼执行定义。真正成功的receipt才形成通过/违规计数；已确认失败的receipt记error，缺失计数保持null，不能编造为0。原receipt核对后在同事务更新quality结果、隔离行和审计，再由父attempt确认并封存子结果hash/证据。两个实例竞争只完成一次；任一写入失败整笔回滚，保留原unknown以供下轮只读核对。每轮最多32条、5秒，游标跨轮推进，坏记录不能永久饿死后续来源。

只有原父attempt绑定、已确认违规且计数超过阈值的失败子结果可用同任务/业务日期的正式waiver推进pipeline；unknown、error、缺receipt、错误fence和旧attempt都不能豁免。该行为的真实PG约束测试不代替全部远端故障；六真实应用已验证手动、实际Quartz、quality→SQL pipeline及两类撤权零业务连接，尚未覆盖Spark/Flink、真实浏览器和最终同版九服务验收。


## V34引擎节点成功证据

V33引擎来源与原receipt对账见[引擎契约](workflow-engine-execution-contract.md)。V34保留SQL/quality的Metadata回执要求，按封存attempt/fence、原origin/key/handle及不可替换的引擎receipt/result确认Spark/Flink上游成功；单纯状态success、缺结果或错绑定不能放行。两条真实PG回归先红后绿，当前31项PG与真实Auth五应用92项通过，Spark→Flink→实际SQL writer链已验证；HTTP peers不执行物理引擎业务代码。全部四类节点同一pipeline、实际引擎副作用、进程终止与Flink新attempt生命周期仍在剩余清单。旧V31/V32段落继续保留其原范围，不重算为当前总验。

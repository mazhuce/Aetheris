# Workflow 子质量任务执行契约

2026-10-26。本文说明本轮接线与验收边界；测试结果以整改台账及各次独立运行报告为准。Spark/Flink 不属于本文已接通的执行类型。

## 定义与批准

父 workflow task 的执行类型为 `quality`，引用一个明确的 `qualityRuleId`。批准时，原已验签用户必须具有父任务 `update` 和该规则 `execute`；workflow 经固定签名端点读取规则当前批准状态，将规则 `qualityExecutionRevision`、`qualityDefinitionSha256` 纳入父任务规范定义，再批准该新 revision。

因此父批准不授权规则的任意后续版本。规则换版、撤销批准、父任务改定义或停用，均不能把旧父引用转换成新执行内容。规则批准者仍须具有实际数据源 `query`。普通 actor 须同时具有父任务和质量规则的 `execute`；没有因此获得任意数据源 SQL 能力。

V32 只扩展 quality 类型的规范投影；SQL 投影保持原字节语义。存量 quality 定义不回填批准或身份。现有数据库必须先盘点 history、备份并核实版本，再使用正常 Flyway migrate；不能 repair 旧记录来接受不同迁移字节。

## 运行来源与 HTTP

manual、绑定 generation 的 cron、封存 pipeline 均先在 workflow 数据库锁定批准定义，创建并封存原 UID、username、AV、requestId、instance、attempt、fence、日期与批准者。pipeline 使用自己的 actor kind 和 run/node/fence，不冒充 manual。

父引用严格五字段：`instanceId,attemptId,fence,executionRevision,manifestSha256`。调用者不能提交 actor、SQL、URL、任意父对象或另选规则。

| 接口 | operation / 调用方 | 内容 |
| --- | --- | --- |
| workflow `POST /internal/v2/quality-parent/lookup` | `workflow.quality-parent.lookup`；quality、metadata | 按五字段父引用回查活动封存来源；返回 canonicalManifest/hash/status/lease |
| quality `POST /internal/v2/workflow-children/definition` | `quality.workflow-child.definition`；workflow | 仅返回指定规则的 revision/hash/批准/状态，不返回 SQL |
| quality `POST /internal/v2/workflow-children/submit` | `quality.workflow-child.submit`；workflow | 从父引用还原规则与 actor，原子创建唯一子结果，返回 202 |
| quality `POST /internal/v2/workflow-children/status` | `quality.workflow-child.status`；workflow | 读取同一父引用对应的原子结果，尚无记录返回签名 404 |

以上均 USER FORBIDDEN，采用既有独立签名、audience、operation、nonce 和响应证明。公开网关身份头、共享 token 和用户名输入不能接通这些接口。quality→workflow 地址由服务端配置；Compose 显式传递 WORKFLOW_SERVICE_HOST/PORT。

子 actor 为 `workflow-child`，UID/AV/requestId 与父 actor 一致；parent 六字段沿用既有 auth 契约。quality 现有父 attempt＋规则唯一键防止重复派发。metadata 在准入及物理执行前重新回查父来源，逐项核对父引用、父批准者、原 actor、规则 revision/hash，并严格验证四项授权：actor 的规则 execute、规则批准者的数据源 query、actor 的父任务 execute、父批准者的规则 execute。

## 不确定结果与恢复

workflow 只提交一次。响应丢失后只查询同一父引用，不查 latest(ruleId)，不重发 submit。父 attempt 独立绑定 quality resultId、子 manifest hash 和 metadata receiptId，不能混用三个标识；绑定后不能替换。迟到结果必须仍匹配当前 active attempt/fence。

quality 结果包含原父引用、resultId、manifest hash、metadata receipt、状态、阈值、计数、耗时和结束时间。阈值来自原封存规则。passed/failed 必须具有一致的计数比较、真实耗时、回执和合法时间；error 可以没有计数/耗时，但必须有原失败回执。不能伪造零值补齐。

quality unknown 通过原 metadata status 回查；丢失 receiptId 时也只使用原 source tuple 查回。pending、404、unknown、断线和验签失败均保留 unknown。确认的原结果、quarantine 和对账审计在同一事务写入；写失败则保持原 unknown。封存 workflow-child 不能仅凭人工摘要改成成功或失败。

父恢复也只有 status 路径。它可以凭原子结果关闭同一个 unknown attempt，但没有重新提交权限，不延长物理执行租约。两侧轮询均使用有界批次、每轮时间预算和本进程公平游标；异常来源也前移游标，重启后从头扫描，双实例依靠数据库 CAS 合并完成。

quality waiver 保留失败本义：只有绑定原 attempt 的已确认质量失败、违规计数超过原阈值及有效 waiver，才能将 pipeline 节点记为 waived。unknown、执行错误、缺失凭证不能豁免。依赖准入再次核对该凭证及 waiver，不能把失败改成 succeeded。

## 安装、回退与证据边界

新安装使用全部当前迁移；约定 V31 基线升级只增加 V32，需核对旧 checksum、success、pending 和 SQL 批准投影未变化。runtime 不新增跨 schema 权限；新 trigger 不获任意函数执行授权。

现有环境的具体升级仍需该环境 inventory、备份、停发及恢复验证。回退前必须停止新派发并核清所有运行、unknown 和子结果，保留 V32 证据；不通过删除列或清空运行表回退。恢复旧数据库应使用已验证备份及对应旧制品，不盲目执行逆向 DDL。

数据库约束 producer 使用普通 workflow/quality runtime 角色和受控远端回执；真实 HTTP 密码学测试的权限和来源状态仍是夹具。六应用 producer 才验证真实 Auth/Metadata/Workflow/Quality 及实际业务 reader/writer；两者均不代替 Spark/Flink、浏览器、九类数据源或冻结制品的整体验收。

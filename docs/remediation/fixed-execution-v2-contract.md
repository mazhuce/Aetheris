# 固定执行 v2：第一批冻结契约

2026-10-18。本文冻结 SQL、transfer、quality-query 的授权及存储接口，供本轮实现使用；不是已运行或已验收的证明。与 [接线分析](fixed-execution-integration-map.md) 配套。V31接入已批准SQL pipeline；2026-10-26的V32接入手动、cron、pipeline父任务的固定quality子调用，见[子质量契约](workflow-quality-child-contract.md)和[恢复契约](execution-recovery-contract.md)。V33/V34的Spark/Flink来源及成功证据见[引擎契约](workflow-engine-execution-contract.md)；真实Auth三节点链已验，引擎业务结果仍为受控响应，物理引擎IO与流生命周期不能由SQL/quality链推定完成。

## auth 窄授权

仅允许 `metadata` workload，`POST /internal/v2/permissions/fixed-execution-authorize`，operation `auth.fixed-execution.authorize`，USER **FORBIDDEN**。原七个 auth operation 的 caller/USER 策略不扩大。metadata 必须先向固定来源回查持久化、封存的运行证据；本接口仅信任 metadata 的有限授权断言，不向其他 caller 开放任意主体查询。

严格 JSON，所有下面列出的字段必须出现，只有明确标注 nullable 的字段允许 null；未知、重复、错类型、溢出、尾随 JSON 均拒绝。有限整数不接受字符串、小数或布尔值。字节上限 64 KiB，嵌套上限 12。

```json
{
  "schemaVersion": 1,
  "binding": {
    "origin": "workflow",
    "operation": "metadata.fixed-sql.submit",
    "resourceId": 7,
    "runId": 11,
    "attemptId": 13,
    "fence": 1,
    "executionRevision": 2,
    "manifestSha256": "64 lowercase hexadecimal characters"
  },
  "actor": {
    "kind": "manual",
    "uid": 2,
    "username": "operator",
    "accessVersion": 0,
    "requestId": "request-123"
  },
  "approval": {
    "uid": 3,
    "username": "approver",
    "accessVersion": 0,
    "revision": 2,
    "definitionSha256": "64 lowercase hexadecimal characters"
  },
  "targets": {"sourceDatasourceId": 5, "targetDatasourceId": 5},
  "parent": null
}
```

`uid/resourceId/runId/fence/revision` > 0，AV >= 0。username 与 USER 协议相同，且符合 auth 当前用户名规则；不 trim、不改写。requestId 遵循协议 `[A-Za-z0-9._:-]{1,128}`。actor.kind 仅 `manual/cron/pipeline/workflow-child`；`workflow-child` 必须且只能用于 quality 并具有 parent，其他 kind 的 parent 必须 null。approval.revision 必须等于 binding.executionRevision。

固定来源、运行键和权限集合：

| origin / operation | attemptId / targets | actor 必需权限 | approval 必需权限 |
| --- | --- | --- | --- |
| workflow / metadata.fixed-sql.submit | attemptId > 0；两个 datasource ID 均 >0 且相等 | workflow_task.execute(resourceId) | datasource.query(source) **及** datasource.write(target) |
| ingestion / metadata.fixed-transfer.submit | attemptId = 0；两个 datasource ID 均 >0 | ingestion_task.execute(resourceId) | datasource.query(source) **及** datasource.write(target) |
| quality / metadata.fixed-quality-query.submit | attemptId = 0；source >0，target=null | quality_rule.execute(resourceId) | datasource.query(source) |

`parent` 若非 null，严格字段为 `resourceId,runId,attemptId,fence,manifestSha256,approval`，前四项正整数、hash 同上，approval 与上面批准者字段相同且自身 revision >0。它表示固定 workflow 父任务；额外检查 actor 对该 workflow_task 的 execute，以及 parent.approval 对 binding.resourceId 的 quality_rule.execute。metadata 已验过 quality→workflow 固定回查与父子绑定。此处不接受额外 `resourceType/action/ctx/username` 替代字段，所有权限 ctx 固定为空（带其他范围约束的授权不会被补造上下文放行）。

检查 actor、approval、可选 parent.approval 的实时 `(uid,username,accessVersion,status)`，再逐项查固定权限。任何不活动、AV 不一致或任一权限拒绝，整体拒绝。数据库/鉴权异常返回签名 503，不转换为 allow。禁止根据 username 查新 AV 延续旧批准。已提交的远端事务不承诺可被撤权回滚。

200 签名响应严格为 `{binding,allowed,reason,checks}`。binding 是请求 binding 原值。reason 为 null 或固定安全代码（不返回 SQL、用户名、proof 或内部异常）。checks 按上述顺序返回，最多六项，每项严格 `{subject,resourceType,resourceId,action,allowed}`；subject 仅 `actor/approval/parentApproval`。整体 allow 必须所有检查通过且全部主体活动。主体无效时 checks 可以为空且整体 false。metadata 同时核对 binding、预期的检查集合及所有 allowed，不仅检查 HTTP 200。无效请求 400、非允许 caller/USER 模式由协议 filter 拒绝。

## 来源存储与准入

### 来源 manifest 与 submit/status（冻结，替代接线分析中的建议 payload）

封存 manifest 严格十四字段：`schemaVersion,origin,operation,resourceId,runId,attemptId,fence,executionRevision,definitionSha256,actor,approval,execution,parameters,parent`。schemaVersion=1；运行键和主体沿用 auth 断言；`execution` **必须等于该 revision 的数据库 execution_definition_json**，不是调用者新提交的 SQL 对象。`parameters` 对 ingestion/quality 固定 `{}`，workflow 固定 `{bizDate:严格ISO日期}`并与 instance.biz_date、attempt.parameters_json 一致。parent 对非 workflow-child 为 JSON null；quality 子任务使用冻结父引用。manifest hash 使用 `encode(sha256(convert_to(sealed_manifest::text,'UTF8')),'hex')`，来源从同一次查询读原 canonical 文本和 hash。

metadata 的三个 submit 路径严格请求统一为 `{runId,attemptId,fence,executionRevision,manifestSha256}`，**禁止 execution/SQL/actor/URL 字段**。各 caller 仅进入自身固定 operation。metadata 通过固定来源回查取得上述封存定义，并由固定版本实现编译实际 SQL、日期替换、transfer 默认参数；JDBC 只消费其自身派生的内容。这样实际执行参数无须相信 submit 的任何 SQL 副本。当前第一批只接三种既定操作，Spark/Flink 不借此开放。

ingestion lookup：`POST /internal/v2/executions/lookup` / `ingestion.execution.lookup`，仅 metadata、USER FORBIDDEN，严格 `{runId,fence}`。quality 对应 `{resultId,fence}`；workflow 对应 `{instanceId,attemptId,fence}`，固定各自 operation。200 严格输出 `{canonicalManifest,manifestSha256,status,leaseExpiresAt}`；canonicalManifest 为数据库 JSONB `::text` 原字符串，leaseExpiresAt 为 DB 时钟的整秒 Unix UTC。来源同时核对当前仍批准 revision/hash/approval、active run/fence/lease、封存字段交叉绑定。metadata 重新 hash 原文本、严格 parse、核对来源/运行键/版本，再调用 auth；不采用 manifest 自报 URL。

submit 成功202和 status 成功200的输出严格同型：`{receiptId,origin,operation,runId,attemptId,fence,state,result,errorCode}`，nullable 字段必须显式出现。状态为 queued/executing/succeeded/failed/unknown；queued/executing/unknown 的 result=null，errorCode 为 null 或安全代码；succeeded.result 为具体操作结果，errorCode=null；failed.result=null，errorCode 非空安全代码。transfer 成功结果严格 `{rowsRead,rowsWritten,durationMs}`（非负long）。其余两个操作的结果契约后续接线时明确，不能用任意 Map 放行。

status 请求严格 `{receiptId,runId,attemptId,fence,operation}`，receiptId 可为 null，其他字段必需。metadata 从 workload caller 固定 origin，只按该来源唯一键查；非空 receiptId 另行核对。submit 响应丢失后只轮询 status，不重发 submit；没有回执也不得自动推断远端未接受。客户端超时或未知保持源 unknown 槽位。重复相同来源运行键只返回既有回执，不再执行 JDBC。

ingestion 明确审批 API：`POST /api/ingestion/tasks/{id}/approve` body `{expectedRevision}`，取当前 TypedTrustedUser，要求 ingestion_task.update、source datasource.query、target datasource.write，然后以 expected revision/hash CAS 保存数据库规范定义对应批准者 tuple；不根据 owner/triggeredBy 选择主体。`POST .../{id}/cron-binding` body `{expectedRevision,expectedGeneration}`，另要求 ingestion_task.execute、当前 enabled+cron+批准，CAS 绑定当前 TypedTrustedUser 为 cron actor。expectedGeneration 固定指 **schedule_version**（不能只使用 cron_binding_generation，未绑定状态的cron修改也必须冲突）。`GET .../{id}/approval` 仅有资源view权限的用户可读状态DTO，严格 `executionRevision,definitionSha256,approved,bindingGeneration,scheduleGeneration,cronBound`；两个generation分别对应 cron_binding_generation 和 schedule_version，不返 proof/秘密。定义/cron/status更新使旧绑定失效；绑定动作在保存后单独同事务写入，不通过普通实体输入接受批准字段。auth 对 ingestion 的额外权限查询范围仅 datasource.query/write，不开放 datasource.update/管理/任意主体。

第一批迁移：workflow V28、ingestion V9、quality V9、metadata V30。不修改已有迁移。定义使用独立 execution_revision；批准者、批准版本与 cron actor 分开保存；执行字段改变清批准及 cron binding，展示/最近结果变化不重批。存量批准/actor 为空，不能根据 owner、triggeredBy 或 admin 回填。

具体存储使用 `execution_approval` JSONB+`execution_approved_at`（JSON字段与 auth approval 完全一致）及 `cron_actor` JSONB+`cron_approved_execution_revision/cron_binding_generation`；不把这些字段加成客户端可写 Entity。定义的规范 JSON/SHA 由 PostgreSQL trigger 从白名单执行列计算。cron/status 变化先清绑定；重新绑定为显式后续写入。ingestion/quality 的 schedule_version/outbox 同时观察 execution_revision 和 cron_binding_generation，无批准/无绑定的历史 cron 入库为 enabled=false 的撤销期望。

新运行保存封存 manifest JSON、SHA256、revision、actor/approval 副本、来源 fence、metadata receipt。ingestion/quality 每新 run/result 固定 fence=1，不复用 unknown 行；workflow 使用已有 attempt/fence。数据库禁止修改已经封存的执行/身份/批准证据，但允许 heartbeat、receipt、状态和结果 CAS。只接受当前仍批准的 revision；旧 revision 重试返回冲突，不悄悄改用新定义。

metadata 唯一 receipt 键 `(origin,operation,run_id,attempt_id,source_fence)`，ingestion/quality attempt_id=0，全部 NOT NULL。重复相同 hash 返回原 receipt；相同运行键不同内容拒绝。先持久化 queued，再有界 worker CAS executing，之后才开 JDBC。重启遗留 executing 转 unknown，不再次执行。worker 消费封存内容，准入再次核对来源 lease 与 auth。完成写失败保持 unknown；unknown/executing 不 TTL 删除。同一 receipt 的状态读取、保存结果可重试，JDBC 副作用不可重试。

## 接线与验收边界

其余路径/lookup/submit/status 沿用接线分析中的固定目录，但实现前必须补齐具体 DTO、结果和限额。不能先开放旧执行端点，不能通过 USER 普通查询代替 cron 授权。真实 PostgreSQL 封存/CAS/并发，以及真实签名 HTTP 的撤权、禁用、篡改和结果丢失反例，均为完成所必需。

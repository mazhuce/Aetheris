# S08 内部身份与固定执行边界

状态：2026-10-18 冻结实现方向，尚未完成应用接入和真实链路验收。本文件不能作为整改通过证据。当前共享 `AETHERIS_INTERNAL_TOKEN` 仍是未关闭的边界问题。

## 信任与协议

用户身份与工作负载身份采用不同用途的 Ed25519 密钥。Gateway 独占用户身份私钥，业务服务仅持此用途公钥；其他服务不能签发任意用户。每个服务另持自己的 workload 私钥，接收方按公钥注册表确定 caller。客户端自报的服务名、role、owner、triggeredBy 不提供身份。

协议实现限定为小型共享 Java 库，避免各模块分别维护密码学解析规则；不抽取整个业务安全层。Java 21 原生 JCA 完成签名，不引入外部身份基础设施。

固定 JWS compact 格式，header 只接受 alg=EdDSA、明确 typ 和 kid；不接受算法协商、外部 key URL、未知或重复字段。签名验证使用收到的原始 base64url 字节，不能重新序列化后验证。key purpose 分为 USER / WORKLOAD，两者公钥也不能混用；同一服务的一对 workload key 可签请求和响应，但三种 typ（AETHERIS-USER-V2、AETHERIS-REQUEST-V2、AETHERIS-RESPONSE-V2）及固定字段结构严格域隔离，不能互换。公私钥采用 X.509/PKCS#8，私钥按容器独立挂载；不写公共环境文件或日志。

用户证明包含 version、issuer=gateway、audience、uid、username、accessVersion、issuedAt、expiresAt、requestId。时间统一 epoch 秒，默认用户证明最多120秒、请求/响应最多30秒、未来容差5秒；可配置但用户最大300秒、工作负载最大60秒。只允许正 UID、非负 accessVersion、有界有效期和受限文本。直接 API 要求 audience 等于本服务；受允许服务转发到 metadata 的用户查询同时要求 workload caller 与原 audience 一致。auth 仍实时检查 UID/用户名/版本/活动状态和资源权限，不依赖证明中的角色。

内部请求包含 version、caller、audience、method、request-target、SHA256(raw body)、nonce、issuedAt、expiresAt、operation，以及必需的userProofSha256。后者绑定实际唯一`X-Aetheris-User-Proof`原始ASCII值；没有USER头则为零字节SHA256，禁止省略或只分别验证两种签名。否则首次投递仍可能将有效操作与另一份有效用户证明拼接。caller 必须与公钥注册项一致；receiver 再检查 caller→audience→operation 白名单。签名绑定实际发送的 body 字节，资源、run、attempt、fence 和定义摘要放进该 body。v2 初始端点不使用 query；request-target 为规范 `/internal/v2/...` 路径，拒绝 query、fragment、点段、重复斜杠、反斜杠和编码歧义，避免两端解析差异。

内部响应也由 receiver 的 workload 私钥签名，绑定请求 nonce、receiver/caller、HTTP status、body hash 和短有效期。调用方先验证状态、原始响应字节及签名，再消费回查或授权结果。明文 HTTP 的响应不能因为请求已签名就被当作可信。

缺少密钥/公钥注册、用途错误、过期、未来时间、格式异常、未知 caller/audience/operation、body/path/method 不符均拒绝。v2 接入后没有旧 HMAC 或共享 token fallback；协调切换前保持为未完成，不能留下未保护的旧内部入口。

## 固定定义与主体

固定任务的 execute 权限不等于任意 datasource.query/write。采用 metadata 回查来源服务持久化 run/snapshot 的方式，复用 R03，而非新增一套中央 capability 数据库。

每个执行定义增加独立 executionRevision、当前执行版本批准者 UID/用户名及结构化摘要；workflow 的展示 owner、现有包含展示字段的 digest，以及 ingestion/quality 的 scheduleVersion 均不能替代这些安全字段。执行字段变更必须新版本重新批准，展示/调度字段不改变批准者。存量缺少证据的定义由管理员明确重新确认，不猜测历史创建者或批量补成 admin。

手动请求由服务端验签后在 claim 短事务内写入 actor UID/用户名/accessVersion。cron 绑定明确配置的专用主体，取消 quality 默认 admin；每次触发实时检查该主体的 definition.execute。retry 继承可信 invocation 与不可变定义快照，建立新 attempt/fence 并重新检查权限。pipeline 固定实际节点集合，不能在授权后扩展。客户端提供的 triggeredBy 只可作为展示信息，不能作为授权主体。

metadata 的窄执行入口包括 SQL、ingestion transfer 和 quality-query。请求带来源 run/attempt/fence、executionRevision/摘要以及实际执行参数；metadata 用自己的 workload 凭据向配置中的来源服务回查，不接受请求指定回查 URL。

回查响应只来自持久化快照，必须含资源 ID/类型、批准者、可信 invocation、run/attempt/fence/租约、严格业务日期、源/目标 datasource、目标表、writeMode 以及实际 SQL/body 摘要。接收侧在 JDBC 前确认：

1. caller 只能引用自己服务的 run，run 和 fence/租约仍有效。
2. 实际 SQL、参数和资源与不可变快照完全一致。
3. 执行者仍有 definition.execute。
4. 该执行版本批准者仍有效，且有底层 query/write 或子定义 execute 权限；新增 datasource.write 目录，不能用 datasource.update 代替。
5. 既有 SQL、出站、连接和数据库角色边界仍然生效。

workflow→quality 传递父 attempt 引用；quality 回查父快照中的固定规则绑定，建立受控子 run，不能接受任意 body.triggeredBy 冒充主体。实时权限只保证准入时有效，不能承诺撤权回滚已提交的数据库操作。

## 去重与未知结果

metadata 增加 nonce 与 execution_receipt 小表。nonce 防原签名重放；唯一 `(origin, operation, runId, attempt/fence)` 防改 nonce 后重复副作用。JDBC 前持久化 claim，不跨网络/JDBC 持有数据库事务。超时、进程崩溃或响应丢失记 unknown；同执行重复请求只返回 receipt/冲突，绝不再次写库。409 不代表可盲目重试。活跃或 unknown receipt 不能按短 TTL 清除，人工对账沿用有审计和停止确认的 R03 边界。

## API Key 与通用查询

serving 不再持有任意 owner 用户签名能力。纯 key 使用专用 `published-api-assets/{assetId}/query` 窄入口，仅接收 serving workload、keyId、assetId/version、metadata 目标摘要和受限查询参数，不接受任意 SQL、datasource 或替换目标。

metadata 重新读取当前发布资产并解析目标，再向 serving 回查 key 活跃、稳定 owner UID 和配额准入证据，实时检查 owner 的 data_service.execute 及原有目标 query 权限。API Key 证明不能用于通用 agent/table/metric 查询或管理入口；key、owner、资产版本或目标变化均拒绝。

AI 和普通用户查询同时要求原 Gateway 用户证明与允许的 workload 转发关系。P05 worker 只显式传递不可变证明与 requestId，不转发 Cookie/JWT，不用服务主体替代用户。用户证明过期后停止后续工具请求。

## 实施及验收顺序

1. 共享协议库与同一套固定测试向量：跨用途、错误 key/caller/audience/method/path/body、未知/重复字段、时限和旧协议全部拒绝。
2. 各服务密钥/注册表与身份 v2 接入；明确缺配置失败行为，密钥准备及新旧协调切换工具先在隔离环境验证。
3. 定义批准者/revision、可信 invocation、来源回查和 metadata receipt/窄执行入口，包含旧数据需明确重新确认的升级路径。
4. API Key 资产窄入口、AI 转发、其余权限/通知/血缘/维护内部入口全部切换，关闭旧共享入口。
5. 真实隔离链路：获授权普通用户固定执行成功、相邻资源拒绝、篡改/过期/撤权拒绝、两副本并发仅一次副作用、响应丢失 unknown 不重放、cron/pipeline/retry 正常及资源清理。

回查可以隔离某个凭据持有者伪造其他 caller/admin；同一服务的代码、私钥和数据库全部失陷时，不能靠该服务自己回查证明定义未被伪造。覆盖更强威胁需要独立审批签章，不在本次最小实现中引入。

现有服务与真实凭据未在此设计阶段切换。生产切换须先分发公钥、准备独立私钥和经审阅的存量批准清单，停止旧 worker/调度后统一切 v2；旧 unknown 保留对账，不自动重跑。

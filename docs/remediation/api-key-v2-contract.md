# 纯 API Key v2：资产专用查询契约

2026-10-18。auth、serving准入和metadata专用查询已实现并通过分包回归，serving V11已通过真实PG。五真实应用v2整链已通过92/92项，见下方冻结运行记录；本文不是完整平台发布通过证明。S06历史四应用报告只作历史证据。

## 边界与现有语义

Gateway仅为规范的已发布API POST路径允许纯key进入serving。serving不持USER私钥，不用用户名、旧HMAC或伪造TrustedUser进入通用query。普通用户保留原USER路径。

当前DataScope是资源context匹配，不是行级SQL过滤。table.query实际授权整表允许字段，默认最多前50列；字段/未知/无法表达的受限grant不能变成ALL。metadata加载真实table/metric/domain后构造context；auth按现有匹配引擎判定。未来行列策略需要独立可执行计划，本包不声称已有。

## 已冻结的auth断言

`POST /internal/v2/permissions/published-key-query-authorize`，operation `auth.published-key-query.authorize`，仅metadata、USER FORBIDDEN。原通用权限目录不扩大，不接受任意actions/context/SQL。

严格顶层四字段，16KiB、深度8、未知/重复/尾随/错类型/溢出拒绝：

```json
{
  "schemaVersion": 1,
  "binding": {
    "admissionId": "7feb99a1-1801-46b1-9bba-d8254406de99",
    "keyId": 3,
    "assetId": 11,
    "assetVersion": "v1",
    "admissionSha256": "64 lowercase hexadecimal characters",
    "parametersSha256": "64 lowercase hexadecimal characters",
    "requestId": "request-123"
  },
  "owner": {"uid": 7, "username": "key-owner", "accessVersion": 2},
  "target": {"resourceType": "table", "resourceId": 13, "domainId": 5, "domainCode": "warehouse"}
}
```

UID/key/asset/target ID>0、AV>=0；username沿auth现规则；requestId沿128字符协议；assetVersion为至多32字符的v正整数文本。domainId/domainCode必需出现但可null。target只允许table/metric，不接fieldId/workspaceId等由调用方拼造的context。

先实时验证owner UID/name/AV及active，再固定两项：`data_service.execute(assetId,ctx={})`和实际`table.query/metric.query(targetId,ctx={tableId或metricId,可选真实domainId/domainCode})`。响应严格`binding,allowed,reason,checks`；成功reason=null且owner两项均allow；主体失效返回SUBJECT_INACTIVE/空checks；权限拒绝返回PERMISSION_DENIED/两checks；依赖异常签名503，不泄漏底层原因。

## serving准入约束

1. 新增key及application的稳定UID/AV列；旧记录保持NULL并拒绝执行，不凭用户名回填。create/request取当前TypedUser；approve保留申请者tuple并重新验证其活动状态，reviewer不能替换owner。rotate锁/CAS防并发双签发，并保留旧owner tuple，不能静默刷新AV。
2. 仅解析现有六字段查询参数`dimensions,timeGrain,startDate,endDate,columns,limit`，默认空list/null/100显式归一化，沿现有维度/字段/日期/limit约束，不能接SQL、URL或目标引用。
3. 向metadata真实`POST /internal/v2/published-api-assets/resolve`取得当前严格五字段`id,assetCode,version,targetType,targetRef`。resolve只返回已发布契约、serving-only、USER FORBIDDEN，不等于执行授权。
4. key验证、有效期和配额CAS、不可变admission INSERT在同一短事务。admission封存key/owner tuple、上述五字段资产、规范参数/hash、requestId及30秒expires；只存引用/封存参数，不存key秘密、bearer或USER证明。
5. metadata-only签名redeem按admission UUID一次CAS领取，再核对活跃key/owner tuple/资产参数绑定及有效期；其他领取或过期拒绝。领取响应丢失不能当成可重新执行的证明。

本批保留现有“配额准入先于后续资源授权”的计数口径，拒绝后不补回quota；key随已封存AV失效，显式新申请重建身份，不通过轮换或用户名复用续命。两项均需在UI/升级说明中明确，现有数据库实际实施前另行确认。

## metadata查询约束

专用固定key-query入口只接admission引用和已绑定资产/参数hash，不接owner、任意target、SQL或URL。通过固定serving origin签名回查，验证canonical admission/hash/领取、参数和时间；重新读取五字段发布契约并逐项一致，拒绝废弃、版本或目标变化。随后加载真实table/发布metric/domain，调用上面的auth窄断言，严格核对binding与两项检查。

提取现有表查询的字段白名单/安全引用/JDBC部分为无HTTP身份依赖的内部服务；指标对同一已加载发布定义编译执行，避免授权后按code重新解析另一个版本。新执行器单独持有不可变的已授权asset上下文，不给MetadataSecurity/AuthPermissionClient添加username fallback。读账号、SQL守卫、出站策略及owner审计继续生效；修正metric审计将sourceTableId误记datasourceId的现存缺口。

API为有界只读查询，客户端禁止自动重发执行POST；若采用同步专用入口，领取/查询/响应丢失返回安全失败，不凭缺响应重用已消费admission。未来如果增加metadata receipt/status，必须单独保证持久唯一claim、executing不可重投和unknown状态，不能仅为HTTP重试增加重复执行窗口。

## 本批冻结的准入编码

采用同步单次只读入口，不新增metadata receipt。metadata的`POST /internal/v2/published-api-assets/key-query`（`metadata.published-key.query`）与serving的`POST /internal/v2/published-key-admissions/redeem`（`serving.published-key.redeem`）请求均精确三字段：`admissionId,admissionSha256,parametersSha256`。两端仅接受对方workload、禁止USER。redeem响应精确`canonicalAdmission,admissionSha256`。

canonicalAdmission是不可变UTF-8 JSON文本，对原字节计算SHA256，不依赖PG jsonb的格式。精确十字段为`schemaVersion,admissionId,keyId,owner,asset,parameters,parametersSha256,requestId,createdAtEpochMillis,expiresAtEpochMillis`。owner沿三字段契约，asset沿五字段契约。parameters依次编码`dimensions,timeGrain,startDate,endDate,columns,limit`，紧凑JSON、null显式、Unicode直接UTF-8；数组沿原顺序去重，默认空数组、limit=100。metadata按这个唯一编码重建parameters hash。维度最多8、列最多50，沿现有标识符约束；timeGrain归一化为null或none/day/month/year；日期必须真实YYYY-MM-DD且起始不晚于结束，limit为1–100整数。未知、重复、尾随、错类型和过长内容拒绝。

created按DB `clock_timestamp()`向下取整为毫秒，expires=created+30000；这是领取及JDBC开始准入截止，不承诺中途强制取消。metadata在鉴权后、开始JDBC前再检查期限。redeem、rotate、revoke统一先锁key再领取admission，避免锁序死锁；先提交的撤销阻止后续领取。key/application UID与AV成对NULL或完整，创建必须完整，禁止事后按用户名补绑。mine按UID过滤新申请，旧NULL申请仅管理视图可见，不能审批或轮换。审批实时验证在DB短事务外，事务内再锁申请比对原tuple/status。

serving V11及metadata V31分别增加usage/query audit的nullable `api_key_id,admission_id,request_id,owner_uid`，用来关联真实owner、key、单次准入和请求。失败认证可以为空，不生成虚构主体；不保存key秘密或proof。连同workflow V29及ingestion/quality各V10，当前共114份源迁移；此前109验收保留为历史指纹。

## 必须验收

真实PG验证UID/AV旧行拒绝、申请者不替换、rotate并发仅一次、quota/admission整事务、领取并发及过期、快照不可变。真实签名HTTP验证caller/USER模式、hash/资产版本/owner替换、无签/错nonce/改body拒绝，领取响应丢失不重放。

真实Gateway→serving→metadata→auth→业务reader至少覆盖table和metric成功、两权限逐项撤销、真实域匹配、field/未知范围拒绝、owner禁用及AV变化、资产废弃/改变目标、quota/过期/撤销、任意SQL与相邻路由拒绝、owner/key/admission/requestId审计和完整自有资源清理。现有历史S06和auth单测均不能代替该整链验收。

## 114迁移五应用冻结记录

`scripts/audit-published-key-v2.py`，run `ef26519341b847bfb6c0d1fb11387c8b`；报告 `output/audit-published-key-v2/run-20261018-v6/report.json`，SHA256 `339339f9500761694e4ef081611da7793cddefe3bc10c39632a47efe3dc9d91d`。92/92项、114份迁移、五个本次源码JAR、真实PG/Redis、源码末指纹匹配、五项清理均通过。另行CIM/监听/Docker精确标签核对无残留，记录于同目录 `cleanup-independent.json`。

实际无JWT/无Cookie key查询返回三行中文业务数据，指标SUM=60；细粒度授权可用，撤权/禁用/AV/配额/撤销/过期/域不匹配/field范围/伪造身份/原始SQL/相邻路由等负例按约拒绝。六次成功查询的owner/key/admission/requestId及真实datasource归属核对无错误；三个服务的互验nonce落入实际数据库。reader没有平台schema/table读取权限。

此前失败运行保留：112源首次API资产管理503暴露metadata管理data_service的窄生命周期授权缺口，已补auth与Gateway回归；其后脚本错误分别为未采用服务器规范指标编码、未采用规范domainCode、PG布尔输出未显式转JSON。均修正验收数据/解析后全量重跑，不更改拒绝断言、不将失败报告改为通过。此记录仅覆盖本机五应用发布查询段，不覆盖workflow/pipeline/Spark/Flink、浏览器、九引擎或生产部署；后续源码改变必须另验并记录新指纹。

## 2026-10-19当前源码回归

2026-10-19新增当前源码回归：run `158c87fbe5be4103b6c49396376965b9`，`output/audit-published-key-v2/run-20261019-v1/report.json` SHA256 `fe554eb2d161d1771291b2f7e9f4231a45ee35b4837d3d2f0ddce59a90976dbe`，102/102项通过。保留原92项；另增真实普通查询/只读写入拒绝、无副作用、管理员与本人审计范围、伪造角色不扩权及可信requestId/安全错误落库10项。owner共11条已发布查询审计中6成功5失败，另一管理员新增2条普通审计，全局汇总与本人汇总按真实DB核对一致。当前114迁移、五JAR与源码末指纹匹配，5项内建清理及独立清理全0。它覆盖后续metadata SQL adapter与P04修改后的纯key查询回归，仍不是workflow/pipeline、浏览器、九引擎或生产部署验收。

## 已有环境的升级与回滚准备

这是实施方案，尚未操作现有数据库、安装密钥或部署。正式实施前先完成当前源迁移清单、九schema角色与平台整链验收，再核对实际Flyway history。先按现有备份脚本导出并在一次性数据库恢复验证；另以只读统计记录`service_api_key`与`service_api_key_application`的总数、状态及UID/AV空值数量，不导出key_hash、秘密或proof。

- V11保留旧行NULL，禁止靠用户名补绑。现有同名用户可能已换UID；无法从旧key摘要可靠恢复原主体。升级后旧key和旧申请均不能执行、审批或轮换，应由实际申请者重新登录并提交新申请，管理员按新流程审批。不要为减少中断而使用管理员UID替换owner。
- key issuance不能混跑旧版与新版serving：新INSERT需要完整UID/AV，旧代码应安全失败。迁移与全部新版serving就绪期间暂停签发；待纯key的真实表/指标、权限撤销及审计验收后再恢复流量。S08其余执行器尚未完成前，不把这个分包单独部署到完整现有平台。
- 降权、禁用、改密等导致accessVersion变化后，旧key不会因重新启用账号或rotate而续命。显式重新申请生成新tuple；轮换只更换秘密并保留原tuple。配额在资源授权前消耗，拒绝请求也不退回计数。
- admission仅保存封存引用与参数，不保存key秘密；过期24h后由每replica每小时最多1000条清理，不代表全cluster每小时总1000。业务usage/audit保留期另按P04制定，不能用admission清理代替审计保留。
- 回滚先暂停纯key流量与签发，保留新admission/usage/audit证据；不得直接切回依赖旧HMAC或裸用户名的应用。优先回到同一v2协议的已验证制品；若必须恢复历史数据库，先在隔离恢复副本核对数据与角色，再由用户确认实际停机恢复窗口和目标。不要盲目Flyway repair、删列或删新记录来迎合旧代码。

这次只新增代码、迁移和可重复隔离验证。真实凭据及数据源AES主密钥未轮换，主密钥重加密属于独立准备项，不能与API key重签发混为一次操作。

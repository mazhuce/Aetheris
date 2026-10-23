# Identity v2 定点复核与用户名兼容修复

采集日期：2026-10-18；用户名最终验证时间：19:27:03–19:27:07 +08:00。本轮最初只读检查 gateway/auth 与共享 AuthProtocolClient、USER 中继；随后获准修改 auth 用户名规则、创建/登录/bootstrap 接入及测试；确认 R3 后另获准仅改 system requestId DTO 上限及对应 MVC 测试。未运行现有服务、真实业务数据库或攻击程序；普通 auth 测试实际执行。并行实现中的其他文件以本轮采集时源码为准，不将本文视作全平台或 S08 完成证明。

## 已确认问题与处理

| 编号 | 结论与精确前提 | 当前证据 / 处理 |
|---|---|---|
| R1 | 原创建 DTO 的 `[^|\\p{Cntrl}]{1,64}` 与 bootstrap 的 `isBlank` 检查允许嵌入空格、NBSP、Cf 等用户名，协议拒绝签发对应 USER。首次空库使用此类 bootstrap 名可造成管理员不可用（P1 部署阻断）；管理端创建此类名字造成单账户不可用（P2）。旧登录还会 trim，使输入映射到另一个名称。 | 已修。共用 [UsernameRules.java](../../services/auth/src/main/java/com/aetheris/auth/validation/UsernameRules.java:6)，DTO [CreateAuthUserRequest.java](../../services/auth/src/main/java/com/aetheris/auth/dto/CreateAuthUserRequest.java:10)、[LoginRequest.java](../../services/auth/src/main/java/com/aetheris/auth/dto/LoginRequest.java:7)，直接调用兜底 [AuthUserAdminController.java](../../services/auth/src/main/java/com/aetheris/auth/controller/AuthUserAdminController.java:67)、[AuthController.java](../../services/auth/src/main/java/com/aetheris/auth/controller/AuthController.java:69)，bootstrap [DataInitializer.java](../../services/auth/src/main/java/com/aetheris/auth/config/DataInitializer.java:38) 落库前明确失败。 |
| R2 | 原 Gateway 在非 auth 转发中保留 Authorization 和 Cookie，接收服务可拿到跨服务可重用的全局 bearer。前提是接收服务或其请求日志能读取实际头；仅持有 workload 私钥本身不等于已得到 bearer，也不是 Ed25519 验签绕过。 | root 接手修复。本轮后续静态读取已见 [JwtAuthFilter.java](../../apps/gateway/src/main/java/com/aetheris/gateway/config/JwtAuthFilter.java:124) 外层统一转发包装：非 `/api/auth/` 去 Authorization/Cookie，覆盖 public/OPTIONS/pure-key 分支，且清空 `getCookies()`。本代理未运行其捕获回归，实际 wire 结果由 root 另交付。 |
| R3 | 协议和 Gateway 允许 128 字符 requestId，auth 拒绝事件原样转发，system DTO 和数据库列仅允许 64，合法 65–128 字符 ID 的 DENIED 审计会失败。权限拒绝仍生效，不构成放行（P2 审计完整性）。 | [Rules.java](../../libs/security-protocol/src/main/java/com/aetheris/security/protocol/Rules.java:14)、[JwtAuthFilter.java](../../apps/gateway/src/main/java/com/aetheris/gateway/config/JwtAuthFilter.java:251)、[InternalV2Controller.java](../../services/auth/src/main/java/com/aetheris/auth/controller/InternalV2Controller.java:132)、[PermissionEventRequest.java](../../services/system/src/main/java/com/aetheris/system/dto/PermissionEventRequest.java:21)、[V5__permission_event.sql](../../services/system/src/main/resources/db/migration/V5__permission_event.sql:14)。已修 Java 边界：root 新增 [V7__permission_event_request_id_128.sql](../../services/system/src/main/resources/db/migration/V7__permission_event_request_id_128.sql:2) 扩列至128，本代理将 DTO 同步128并补签名 MVC 回归。历史 V5 未改，ID 不截断。实际 PG 扩列由 root 的105源隔离联验另验证，本轮未执行 PG。 |

## 绑定与限制的静态核对

本轮没有确认其他签名身份伪造旁路，具体已核对以下约束；这不是对尚未接入应用的保证：

- Gateway 入口剥离旧身份、internal token 及三种 proof 头。正常用户请求分别签发目标服务 audience 与 gateway 权限检查 audience，未用终端自报服务名选择 caller。
- [WorkloadProofFilter.java](../../libs/security-servlet/src/main/java/com/aetheris/security/servlet/WorkloadProofFilter.java:98) 将唯一实际 USER 头的 SHA256 绑定到请求证明，并核对服务器固定 method/path/body/operation、注册 caller 和 ACL；随后进行 USER audience、实时会话和 DB nonce 检查。
- [RoutePolicy.java](../../libs/security-servlet/src/main/java/com/aetheris/security/servlet/RoutePolicy.java:36) 仅由已认证 caller 取服务器预设 USER audiences。auth 对 metadata 固定允许 metadata/serving/ai-agent，其他 caller 默认只接受自身 audience；payload 不能自行扩展中继目录。
- [AuthV2SecurityConfiguration.java](../../services/auth/src/main/java/com/aetheris/auth/config/AuthV2SecurityConfiguration.java:38) 的资源目录、mutation service 目录与七个固定 POST operation 分开约束；session/version 禁止 USER，管理/资源查询要求 USER。metadata 中继仍须在业务端从可信资源生成 context；权限判定结果不替代业务资源、run/snapshot、receipt 授权。
- [AuthProtocolClient.java](../../libs/security-protocol/src/main/java/com/aetheris/security/protocol/authorization/AuthProtocolClient.java:97) 固定 auth POST 路径及 DTO，不接受任意 username 作为权限主体；底层 [SignedHttpClient.java](../../libs/security-protocol/src/main/java/com/aetheris/security/protocol/transport/SignedHttpClient.java:201) 验证响应 caller/receiver/nonce/status/body。auth 当前内部 DTO 使用本地严格 JSON mapper，拒重复/未知字段及标量强转。
- auth 必需 registry/workload 私钥文件，未配置启动失败；注册两类 filter 覆盖全部 DispatcherType、禁止 async。部署挂载、真实 Gateway→auth→system 路径、数据库 nonce 和部署启动仍归 root 独立联验。
- [PermissionAuditClient.java](../../services/auth/src/main/java/com/aetheris/auth/audit/PermissionAuditClient.java:21) 使用2 worker、64 queue、16KiB body、短 transport deadline；失败、过期、满队列有计数且不改变权限决定。这是有界 best effort，不是审计必达保证。

## 用户名契约与存量兼容

新规则按 Unicode code point 检查：拒 `Character.isISOControl`、`isWhitespace`、`isSpaceChar`、FORMAT（Cf）、孤立 surrogate，并保留历史 `|` 禁令。长度仍为 Java `String.length()` 的1–64 UTF-16单元，与历史 Auth Size64 一致；不是64个 Unicode code point。共享协议上限128且没有 pipe 禁令，因此 Auth 是刻意更窄的子集。

保留大小写、中文、emoji 和组合字符；不 trim、NFC/NFD 归一化或改名。32个普通 supplementary emoji 恰为64单元；ZWJ/Cf 不接受。旧含首尾空格输入现在明确拒绝，不再尝试映射到去空格后的另一个账户。刷新及存量实体不被本包重写；已有不兼容名称仍需上线前独立清点。

只读预检方法（本轮没有连接真实库执行）：用只读 DB 凭据仅读取 `SELECT id, username FROM auth.auth_user ORDER BY id`，在同一 JDK21 上对每行调用公开纯函数 `UsernameRules.isValid(username)`，报告 UID 与违反类别即可；不读取 password_hash、不输出完整账号数据、不 UPDATE/TRIM。SQL regex 不足以精确代替 JDK Character Unicode 分类。不兼容记录应暂停该账户切换并人工确认映射及相关 grants/audit/API-key owner 引用后另立迁移，不能自动重命名。已有用户时 bootstrap 仍只做 count 后返回，本包不隐式扫描或修复数据。

## 实际验证与源码指纹

1. 原实现先运行 `UsernameBoundaryRegressionTest`：3例、3失败，分别证明 DTO 接受不兼容名称、bootstrap 接受 Cf、登录 trim；没有编造生产利用结果。
2. 修复后定点运行 UsernameBoundaryRegressionTest、UsernameRulesTest、AuthControllerTest、DataInitializerTest、AuthUserAdminControllerTest，exit0。
3. 绝对 `JAVA_HOME=C:\Program Files\Java\jdk-21`，普通模块 `mvn -q -o -f services/auth/pom.xml test`，exit0。最新21份 `*Test.xml` 合计 **142 tests / 0 failures / 0 errors / 0 skipped**。未计入旧 `*IT.xml`，本轮未跑真实 PG IT。

新测试3+5例覆盖各类 Unicode 边界、合法名称的真实 JDK Ed25519 USER 签发/验签、无自动归一化、直接创建兜底、bootstrap 落库前失败及已有数据不修改；原登录 trim 测试改为拒绝且无账户查找/令牌交互的回归。

本包8个生产 Java 文件、3个测试 Java 文件逐项SHA256如下；对按路径排序的 `{path:hash}` 使用紧凑 JSON（`,`/`:`）再 SHA256 聚合：`f010b45566916c60b26ad462e402bc0efb483eac622706ffba998865355777ff`。

| 文件（相对 services/auth） | SHA256 |
|---|---|
| src/main/java/com/aetheris/auth/validation/UsernameRules.java | 3f59c5f4dad546a3a7d7a6d6770965402ef4c6b3cf3c5703da03f398fc739706 |
| src/main/java/com/aetheris/auth/validation/UsernameValidator.java | cdd8b1fc39eb64e4333503b0da90b8a952a6816746347575baee04326bd0db41 |
| src/main/java/com/aetheris/auth/validation/ValidUsername.java | add53d527ad496794f079e3e24cc8010c784b955dbfe6c25e224b14627befec4 |
| src/main/java/com/aetheris/auth/dto/CreateAuthUserRequest.java | fe2fb495eb9728629c0e5db7a077bdd739a43eced0f7b28a32a246fd5ca1ec05 |
| src/main/java/com/aetheris/auth/dto/LoginRequest.java | 94614a4b71615f2f8f78b7895d2f1263f66edb4a80d4bbcc16ca7fbe87d5e367 |
| src/main/java/com/aetheris/auth/config/DataInitializer.java | 1a0bd437bc3711ed05e78739458bf161ee0201b2ee4a08425487bb2e4e7ca42b |
| src/main/java/com/aetheris/auth/controller/AuthController.java | 3c337dba8c623539542c32ab18547b7791f21d15e1c60102539e475afcd16b3f |
| src/main/java/com/aetheris/auth/controller/AuthUserAdminController.java | 7b698e282e2270738acbe71ecf687c14612e6340124f7ffac37671874ba82f6d |
| src/test/java/com/aetheris/auth/validation/UsernameBoundaryRegressionTest.java | 807345dcce465cf7597eb011ed3bb54ca5da4adc046087201c0edb7139ac2a15 |
| src/test/java/com/aetheris/auth/validation/UsernameRulesTest.java | 995c2e7a631139580a159dfca228a3395d13d46634eafbc1ec5f5a8bc8c7b148 |
| src/test/java/com/aetheris/auth/controller/AuthControllerTest.java | 566ce47fe15ad1e0ed31a251531523f78dbbaa15ceb78dc3e040e3150d16b8ac |

补充 R3 验证：原 DTO 先运行新增 `SystemV2MvcTest#authPermissionEventPreservesFullProtocolRequestIdBoundary`，实际1例1失败（合法65字符得到400）；修复后 `mvn -q -o -f services/system/pom.xml test` exit0，19:31:29–19:31:32 +08:00 的13份普通 Test XML合计 **46 tests / 0 failures / 0 errors / 0 skipped**。新方法使用实际 JDK Ed25519、auth workload caller、固定 operation、无 USER，经 filter/MVC 验证64/65/128字符完整保存且签202，129字符不保存且签400；nonce/session 与业务 service 为测试替身，不是实际 Tomcat/auth/PG 链。DTO SHA256 `394484ce740f99ff1976c502c61c992c706d8b6b903b6df365ec55138576a535`，MVC测试 SHA256 `b3e060c37dc09bb73f07611e77b2f7bdb921195f2b2425be594b8598b0418274`。

用户名源码、system两个小修文件及两模块 target 已释放给 root；本文文档也随交付释放。root 并行修复和真实链证据应另记，不能追溯回填成本轮已运行结果。


## 独立追加：metadata 六条 USER 查询 v2 静态复核（2026-10-18）

本段只读核对当前冻结源码，不重复 auth/system 已完成工作，不改 src/target/测试，不运行构建、现有服务或数据库；root 的全量 Maven 正在独立执行，其结果不计为本代理验证。**本轮未确认新的鉴权旁路或权限异常被改写为局部502的缺陷。**

| 核对点 | 实际证据与结论 |
|---|---|
| 主体不可回落 | [MetadataSecurity.java:30](../../services/metadata/src/main/java/com/aetheris/metadata/security/MetadataSecurity.java:30) 只读 `TrustedUser.ATTRIBUTE`，旧 `aetheris.identity` DTO 属性不作身份；退休 IdentityVerifier 没有 Spring bean、所有 verify 返回 null。IdentityInterceptor 写入的 AetherisIdentity 只是兼容展示属性，不反向获得授权。MetadataWebConfig 未注册旧 InternalTokenInterceptor。 |
| 原USER与中继 | [AuthPermissionClient.java:125](../../services/metadata/src/main/java/com/aetheris/metadata/security/AuthPermissionClient.java:125) 取当前 ServletRequestAttributes 的 TrustedUser，严格比对 username，再转发 `compactProof()`，不重签、不从裸头或任意用户名生成主体；无上下文/错主体 fail-closed。metadata inbound 默认 USER audience=已验caller；auth [AuthV2SecurityConfiguration.java:62](../../services/auth/src/main/java/com/aetheris/auth/config/AuthV2SecurityConfiguration.java:62) 对 metadata 固定允许 metadata/serving/ai-agent，因此可转发原serving/AI证明而不扩大任意audience。 |
| 六路由/严格body | [MetadataV2SecurityConfiguration.java:24](../../services/metadata/src/main/java/com/aetheris/metadata/config/MetadataV2SecurityConfiguration.java:24) 仅列举六个POST/query-free路径、server-fixed operation/caller，均 USER REQUIRED；[InternalV2QueryController.java:22](../../services/metadata/src/main/java/com/aetheris/metadata/controller/InternalV2QueryController.java:22) 严格重复/未知/尾随字段与数值、Textual coercion；类型化positive ID/限长SQL及嵌套Bean Validation后调用旧实现；controller另要求可信workload+对应operation+user。filter仍绑定唯一实际USER哈希、原始body/path/method并检查实时会话/nonce。 |
| 资源权限沿用 | search/list 通过 visibility 的ALL/域IDS/逐项OPAQUE过滤；describe要求table.view（[InternalAgentToolController.java:137](../../services/metadata/src/main/java/com/aetheris/metadata/controller/InternalAgentToolController.java:137)）；SQL要求datasource.query（:191）；table query要求table.query（[InternalTableQueryController.java:51](../../services/metadata/src/main/java/com/aetheris/metadata/controller/InternalTableQueryController.java:51)）；metric query要求metric.query（[InternalSemanticMetricController.java:53](../../services/metadata/src/main/java/com/aetheris/metadata/controller/InternalSemanticMetricController.java:53)）。domain context由持久化同一Domain生成，不接终端ctx。未见v2适配绕过这些门或先调用JDBC再授权。 |
| 异常状态 | 三个权限异常在 [MetadataSecurity.java:92](../../services/metadata/src/main/java/com/aetheris/metadata/security/MetadataSecurity.java:92) 直接继承RuntimeException；不会被v2局部IllegalArgument/IllegalState handler匹配。全局 [MetadataAccessExceptionHandler.java:22](../../services/metadata/src/main/java/com/aetheris/metadata/security/MetadataAccessExceptionHandler.java:22) 分别401/403/503，优先于Exception兜底500。旧查询实现的权限门也位于业务SQL try/catch外。列表visibility/批量故障仍采用OPAQUE逐项或空集fail-closed，可能200空列表，并非所有权限依赖故障均承诺503。 |
| 启动/dispatcher | 必需registry/workload私钥、固定auth origin，load(metadata,...,null,null)无USER私钥；非root context启动拒绝。两个filter均`/*`、所有DispatcherType、async=false、按+20/+21排序；Direct API `/api/metadata/**`全部PROTECTED。auth出站ACL仅四个所需operation，连接500ms/请求2s，等待2.5s后取消，无跨请求权限缓存。 |

边界：原 six delegate 的业务SQL/字段错误可继续以安全error body或局部502/400返回，这是既有查询协议，不等于吞掉权限拒绝。旧写/血缘/asset/固定执行等路径在当前枚举policy下不可达，属于root已知的分段切换状态；**S08未完，不可据此部署**。实际完整HTTP/应用/数据库链仍待后续验收；本轮仅阅读测试源码，不声称额外测试通过。

本次六核心文件（MetadataV2SecurityConfiguration、InternalV2QueryController、AuthPermissionClient、IdentityInterceptor、MetadataAccessExceptionHandler、MetadataSecurity）按仓库相对路径排序`{path:sha256}`紧凑JSON再hash，聚合SHA256为 `7b27b724a211746f1c294b931046f070839a3266cbbf4922a33a54b9146f4a70`。本段文档随交付释放，不占用metadata源或target。

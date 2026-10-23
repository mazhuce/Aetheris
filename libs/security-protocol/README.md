# Aetheris security-protocol v2

独立 Java 21 jar：`com.aetheris:security-protocol:0.1.0-SNAPSHOT`，包名 `com.aetheris.security.protocol`。运行时只依赖 Jackson；Boot 3.4.7 parent 仅管理构建和依赖，没有 Spring 运行时或自动配置。10个应用的pom/Docker构建已接入库依赖并通过本机reactor compile；应用安全配置仍未切换。

本库只做密码学认证和请求/响应绑定。**通过库测试不代表 S08 业务闭环完成。**

## 密钥与类型

`KeyPurpose.USER` 只属于 gateway，与 WORKLOAD 私钥物理独立。每个服务一对 WORKLOAD 密钥可以签请求和响应；对应的三个 typ 严格分离：

| API | typ | KeyPurpose | 注册表归属匹配 |
|---|---|---|---|
| `sign(UserProof)` / `verifyUser` | `AETHERIS-USER-V2` | USER | issuer = gateway |
| `sign(WorkloadRequestProof)` / `verifyRequest` | `AETHERIS-REQUEST-V2` | WORKLOAD | caller |
| `sign(WorkloadResponseProof)` / `verifyResponse` | `AETHERIS-RESPONSE-V2` | WORKLOAD | receiver |

注册表由部署方提供，不能从请求、远程 jku 或用户输入建立。相同公钥不得跨 purpose/caller 注册。signer 构造会验证配置 kid/purpose/caller 以及私钥与注册公钥的实际签名对应关系；verifier 只需公钥，没有签发 API。注册表及验签结果不可变，`Verified` 只能由验证器创建。库不会输出 token、正文或私钥到异常消息。

私钥是 PKCS#8 PEM `PRIVATE KEY`，公钥是 X.509 PEM `PUBLIC KEY`；`KeyMaterial` 拒绝缺失、过短、错误算法、错误 envelope 和非规范编码。文件读取及挂载权限由应用/安装程序负责。

## Typed API

```java
var keys = new KeyRegistry(List.of(new KeyRegistry.Entry(
    "workflow-2026-01", KeyPurpose.WORKLOAD, "workflow",
    KeyMaterial.parseX509Pem(publicKeyPem))));
var clock = Clock.systemUTC();
var verifier = new ProtocolVerifier(keys, clock, TimePolicy.defaults());
var expected = new ExpectedRequest(
    "metadata", "POST", "/internal/v2/executions/sql",
    BodyHash.sha256(receivedRawBytes), "execution.sql",
    BodyHash.userProof(exactSingleUserHeaderOrNull));
var authenticated = verifier.verifyRequest(receivedCompactProof, expected);
// authenticated.caller() 是注册公钥归属；业务层仍必须检查 caller ACL、nonce、run 和权限。
```

`ExpectedRequest` 必须来自本服务实际收到的 HTTP method、原始规范路径、raw body bytes 以及服务端固定 operation，不能照抄证明自报的值。`ExpectedResponse(receiver, caller, requestNonce, status, rawBodySha256)` 必须绑定实际响应字节与实际 HTTP status，并使用保存的原请求 nonce。先验证再解析业务响应，不能因为请求已认证就信任响应。

`ProtocolSigner(kid, purpose, caller, privateKey, registry, clock, policy)` 提供三个 typed `sign` 重载。proof record 自身是待签/待验数据，只有 `Verified<T>` 表示已通过认证；不要把 JSON 直接反序列化为 Verified 或缓存请求外长期授权。

用户字段：`version, issuer, audience, uid, username, accessVersion, issuedAt, expiresAt, requestId`。无 role。`verifyUser(token, expectedAudience)` 精确匹配 audience；metadata 用户转发时应先验证 workload，再要求用户 audience = 注册 caller，并执行允许转发关系 ACL。不能把 expectedAudience 从未验证用户 payload 读取。

请求字段：`version, caller, audience, method, requestTarget, rawBodySha256, nonce, issuedAt, expiresAt, operation, userProofSha256`。资源、run、attempt、fence、定义摘要必须包含在 raw body 中。

`userProofSha256` 必须绑定实际收到的唯一 `X-Aetheris-User-Proof` 原始ASCII值，不去空白、不重编码；无用户头时固定为零字节SHA-256。不能只分别验workload和USER签名，否则两份各自有效的证明仍可能被错误拼接为另一用户执行同一操作。接收层先用实际头计算摘要并验证请求绑定，再验USER及实时会话。字段必需，缺字段的早期v2请求拒绝，没有兼容回退。

响应字段：`version, receiver, caller, requestNonce, status, rawBodySha256, issuedAt, expiresAt`。请求与响应字段集合不同，不能互换。

## Wire 规则

- 三段无 padding 的 canonical base64url JWS compact；签名固定为 JDK Ed25519。header 只允许 alg=EdDSA、typ、kid。禁止未知/重复字段、算法选择、外部 key URL；不支持旧 HMAC。
- 对收到的 `headerSegment + '.' + payloadSegment` 原始 ASCII 字节验签。允许 JSON 字段排序/空白变化，但签名必须对应实际字节，绝不重新序列化后验签。
- JSON 为严格 UTF-8 object；拒绝重复/未知/缺失字段、浮点/字符串整数、数值溢出、尾随对象和过深结构。uid > 0，accessVersion >= 0；epoch 秒为正且不超过 9999-12-31T23:59:59Z。
- 默认用户 TTL <= 120 秒、workload TTL <= 30 秒、issuedAt 未来容差 5 秒；expiresAt 等于当前秒即失效，没有过期宽限。TimePolicy 可降低/调整，硬上限分别 300/60/10 秒。没有 Math.abs 溢出处理。
- 服务标识 `[a-z][a-z0-9-]{0,63}`；kid 最多 64 字符；operation 最多 96 字符；requestId 最多 128 字符。用户名最多 128 UTF-16 单元，拒绝空白、控制字符、format 字符和孤立代理项，支持中文。
- requestTarget 长度 <= 1024，每段 <= 128；必须 `/internal/v2/...`，仅 ASCII 字母数字及 `._~-/`。拒绝任何 `%` 编码、query、fragment、双斜杠、尾斜杠、点段、反斜杠。v2 端点必须不使用 query；不要先 URL decode/normalize 再验签。
- method 固定大写 GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS，状态 100..599，rawBodySha256 为 64 位小写 hex。空 body 对应零字节 SHA-256，不是 null。
- nonce 是 16..64 字节的 canonical base64url；`Nonce.random()` 生成 24 随机字节。**生成 nonce 不等于防重放。**
- compact 总长度 <= 12288；header/payload/signature 各自编码长度 <= 768/8192/86。HTTP body 限额、超时、流式压缩/Content-Encoding 策略由接入层负责，签名两端必须使用相同 raw byte 定义。

## 接入层不能省略的检查

1. caller→audience→operation 白名单；未知 audience/operation 即使字符串格式合法也必须拒绝。本库只匹配调用方传入的期望值，不内置平台业务操作目录。
2. 用户 UID/username/accessVersion/active 实时校验及资源 RBAC；服务不能仅凭已验签 username 请求任意管理能力。
3. nonce 数据库唯一 claim；执行 receipt `(origin, operation, runId, attempt/fence)` 去重；unknown 不自动重发。库重复验证同一 token 会成功，这是刻意不承担数据库状态职责。
4. 固定定义 snapshot 回查、可信批准者与 invocation、lease/fence、严格业务日期、资源/SQL/body 对比，以及数据库最小权限。
5. API-key 只能进入绑定发布资产的窄只读接口，不能被包装成 USER proof。
6. HTTP 禁止未保护旧入口、重定向到其他 audience、签名 body 与实际发送 body 不一致；安装切换不能有旧协议 fallback。

## 固定测试向量与验证

`src/test/resources/vectors/v2-bound-user-fixture.json` 是当前固定向量，含三对随机生成后冻结的真实 Ed25519 测试 keypair、epoch 和三种 compact proof。早期`v2-fixture.json`原样保留，其缺少用户绑定的请求只用于拒绝回归。**这些私钥公开，仅用于测试，严禁部署。** 向量签名使用纯 JDK 独立生成器 `GenerateVectors.java`，测试逐字节重现签名；可复制至各模块做跨应用一致性验证。生成器拒绝覆盖已冻结文件。测试不读取任何安装密钥，不调用付费 API 或现有服务。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path=$env:JAVA_HOME+'\bin;'+$env:Path
mvn -o -f libs/security-protocol/pom.xml test
```

本包的构建日志和 source/fixture SHA-256 见同目录交付记录；没有构建或部署整个平台。

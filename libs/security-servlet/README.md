# security-servlet：Servlet 身份边界组件

`com.aetheris:security-servlet:0.1.0-SNAPSHOT`，Java 21 / Boot 3.4.7 dependency management。依赖现有 security-protocol、Spring web；Jakarta Servlet 6 API 为 provided，PG/Tomcat驱动仅test scope。没有应用自动配置或额外运行时数据库驱动。**Gateway/auth/system正在接入v2；本库已补真实Tomcat/PG分项联验，当前完整S08业务链仍未完成。**

## 唯一可信用户属性

```java
TrustedUser user = TrustedUser.require(request);
UserProof proof = user.proof(); // uid / username / accessVersion，不含 role
String exactForwardedToken = user.compactProof();
```

两种 filter 共用 `TrustedUser.ATTRIBUTE` 和不可变 `TrustedUser` 类型。实例只在密码学认证及实时 SessionValidator 均通过后创建，原始 compact 只用于显式转发，禁止日志输出或延长证明有效期。业务 adapter 只能读取此类型；不能从 `X-User-*`、旧 HMAC、请求 body 的 role/owner/triggeredBy 或未经验证的 JSON 建立主体。工作负载另由 `WorkloadProofFilter.trusted(request).workload()` 读取，不能替代用户。

## WorkloadProofFilter

构造注入 audience、ProtocolVerifier、该 receiver 的 WORKLOAD ProtocolSigner、RoutePolicy、NonceStore、SessionValidator、Clock、TimePolicy，以及可选 responseLimit（256 bytes..1 MiB，默认1 MiB）。构造时签名/验签一个内存 probe，提前拒绝错 purpose、错 receiver 或不匹配注册表。

`RoutePolicy.resolve(actualMethod, actualRawPath)` 返回 Optional<Route>，固定 `Route(operation, Set<String> allowedCallers, UserMode)`；UserMode 为 FORBIDDEN / OPTIONAL / REQUIRED。没有匹配 route 即拒绝，期望 operation 不能从证明 payload 获取。allowset 深复制。应用构造时应使用完整的固定路由映射；此库不能替业务判断哪些 caller 可执行哪个操作。

仅 `/internal/v2/...` 规范路径进入认证，所有旧 `/internal/**` 拒绝，旧 token 无 fallback。raw URI 不做 URL decode/reencode；拒绝 query（包括空 query）、百分号、点段、重复/尾斜线、反斜杠、上下文前缀和非 REQUEST dispatch。还查看容器 servletPath/pathInfo 以识别已被容器解码为 internal 的候选路径，但这些值绝不作为签名期望路径。需应用部署在 root context；非 root context 不兼容，应明确配置而非默默绕过。

wire 沿用 `X-Aetheris-Workload-Proof` / `X-Aetheris-User-Proof` / `X-Aetheris-Response-Proof`。每个必须唯一，拒绝重复/超长/非 ASCII；请求不接受 response proof。body 解码前限制 1 MiB，拒绝压缩，拒绝重复 Content-Length/Content-Encoding 以及 Content-Length+Transfer-Encoding。未知长度 body 边读边限额，不先无界 readAllBytes。ServletInputStream 的读取期限仍需容器配置；本同步 filter 没有声称防止慢速请求无限占用容器线程。

先按实际 method/path/raw body hash、服务器 operation，以及 `BodyHash.userProof(实际唯一 USER header或null)` 验证 workload。该摘要绑定是必需字段，不能只分别验证两个 token；首次投递换成另一有效用户也必须失败。caller 来自注册公钥并匹配 route allowset。若允许/要求用户，再以 expectedAudience=已认证 caller 验证 USER，并用 UID/name/accessVersion 调用 SessionValidator 检查当前状态。不是把用户 proof 自报 audience 当期望值。

认证后必须调用 `NonceStore.claim(caller,nonce,expiresAt)`。接口实现必须在服务所有副本间原子持久化，true 只代表首次 claim；生产不能使用测试 ConcurrentHashMap 实现。可用构造时绑定 receiver 的 DB adapter，短 nonce TTL 不能删除业务 execution receipt。重复返回已签 409，store 异常返回已签 503，均不执行 chain。SessionValidator 异常返回未签503，失效用户拒绝且不 claim；这类未认证响应不能当作可信业务结果。

请求 wrapper 提供相同 raw bytes 的重复读取，隐藏旧 `X-Internal-Token`、`X-Aetheris-Identity`、`X-User-*`、Cookie、Authorization 和原始 proof headers。可信对象需从属性读取，不能通过改属性覆盖。内部禁 startAsync/read/write listeners、session 和响应 cookie；长任务应返回 202 receipt 后短轮询。

响应在有硬限额的 buffer 中完成，flush 不提前发送；不用 ContentCachingResponseWrapper。禁止下游覆盖 response proof、Content-Length、Transfer-Encoding、Location，禁压缩/非UTF8 writer。仅完成后按实际 raw bytes/status/原nonce/caller/receiver 签名，再写真实 response。有效应用4xx/5xx保留原状态且签名；异常丢弃所有 partial body/headers，返回固定安全500 JSON，不暴露 exception/SQL。超大输出固定500，redirect变已签502，不跟随目标。HEAD/204/205 按实际无实体语义签空 body。签名生成失败不发送业务 body，返回未签503。

真实Tomcat body读取超时可能已经提交或关闭响应，未签拒绝分支必须保留该connector结果，不能再次reset造成容器500；此时没有通过认证或进入业务chain，不伪造可验签业务响应。实际测试观察header超时EOF、body超时408，并要求无severe容器异常；不能把已签业务状态与这种未认证连接终止混淆。

## DirectUserProofFilter

构造：`DirectUserProofFilter(audience, verifier, SessionValidator, DirectRoutePolicy)`。针对 `/api/**`，RoutePolicy 显式返回 PROTECTED / PUBLIC / REJECT；null 或异常不能放行。登录/refresh/logout、health、serving pure-key 窄路由等排除，必须由服务配置按实际 method/rawPath 明确列出，不在库中猜测。非 `/api` 路径不归本 filter；内部路由必须另由 WorkloadProofFilter 保护。

PROTECTED 仅接受一个有界 `X-Aetheris-User-Proof`，以本服务 audience 验签并实时检查 UID/name/accessVersion。没有旧 HMAC、JWT 或角色头 fallback。成功后与 forwarded USER 使用同一 TrustedUser 类型，并隐藏旧头；PUBLIC 路径保留原有请求，需应用自己的登录/refresh/logout处理。没有默认 PUBLIC 策略。

## 安装边界与验证

应用接入由后续包负责：filter 必须在旧身份适配器/Controller 前生效；Workload filter 映射应覆盖 REQUEST/ASYNC/FORWARD/INCLUDE/ERROR，使内部非 REQUEST dispatch 被拒绝，不能经转发绕过。容器必须禁用内部路径压缩、配置请求读取期限/并发和 header 限额。不得将本 filter 与会改写已签 body 的 response compression filter 组合。业务仍需 RBAC、固定定义快照、execution receipt、nonce DB adapter和权限变化检查。

原始测试使用Spring mock servlet执行实际filter、JDK Ed25519和当前protocol library，没有mock verifier/signer；原24并发nonce claim使用测试Set，单独不构成真实DB证明。后续PostgresNonceStoreIT及TomcatProofWireIT补了真实PG、多接收器、loopback HTTP和SignedHttpClient联验，证据及限制见仓库`docs/remediation/nonce-store-acceptance.md`、`servlet-wire-acceptance.md`。测试SessionValidator仍是本地测试状态，不表示真实auth/RBAC完成。

首次离线解析因本机 Servlet6.0 缓存仅记为 `public` repository ID，而当前合法 Maven 配置为 `aliyunmaven`，被 Resolver 拒绝；未伪造 repository ID 或编辑 cache 元数据。获准按当前 Maven 配置正常核对固定 jakarta.servlet-api:6.0.0 来源后成功（Maven记录 pom/jar 0B），随后离线复验。日志和摘要保存在 ignored target。

2026-10-18 当前包 `mvn -B -o -f libs/security-servlet/pom.xml package` 最终51项通过（Workload46、Direct5），无失败、错误或skip。首次受控依赖核对构建为49项，随后补齐 exact1MiB/HEAD/204/205与两filter共存回归，最终离线51项；不把首次49项覆盖成最终计数。`target/servlet-first-build.log` 保留实际依赖处理和首次结果，`target/servlet-offline-package.log` 与 `target/servlet-delivery.json` 保存最终验证和源/制品摘要。没有修改现有应用、root pom、Dockerfile或共享配置，没有启动数据库/容器或应用服务。

上述51项是首次包历史。追加4项Postgres store安全测试及1项connector已提交读超时回归后，当前普通单测56项通过，另两个显式真实PG/Tomcat IT通过；各run原样保留，不回填旧包摘要。真实接入仍须应用自身固定ACL/实时权限/receipt及升级切换。

## PostgresNonceStore

注入`javax.sql.DataSource`、固定receiver-owned schema与Clock；只接受平台9个schema之一，路径不能来自请求。`claim`以`(caller,nonce)`主键和INSERT ON CONFLICT完成已提交唯一claim，无内存fallback、不承认外部事务未提交的结果。要求独立autocommit connection、2秒statement timeout，连接借用/建立期限须由应用DataSource另外限制。代码不自行建表或修改权限。

`src/main/resources/internal-request-nonce.sql.template`保留为结构模板；当前九schema已有各自增量Flyway迁移，全部104源真实新装/gate已通过。应用仍须使用自己的数据库角色，不能共享schema；其余应用注册/有界维护仍需接线验收。

`purgeExpired(limit)`一次最多10000行，仅删签名到期再超过24小时的nonce；不涉及execution receipt。维护用SELECT/INSERT/DELETE即可，不要求UPDATE；并发清理可能选同一批旧行，允许后到批次删0并留待下轮。应用还需配置有界维护调度、监控容量和时钟，不将模板或方法存在当作这些运行条件已实现。


2026-10-18追加：服务器Route可显式给某个caller配置不可变USER audience中继集合，三参Route默认仍只接受caller自身。仅auth权限判定配置metadata可中继metadata/serving/ai-agent，其他入口不自动放宽；原USER值仍由workload摘要绑定，并检查实际账户。实际auth MVC发现Spring异常处理调用setHeader(name,null)时旧buffer NPE，根因回归先红后修：setHeader null移除、addHeader null忽略，保护头仍不能改。现60普通单测通过；早期56单测+2实际IT保留为历史，新增内容须另行实际应用联验。

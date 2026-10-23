# SignedHttpClient：固定目标与双向签名传输

状态：transport 是独立接入组件，不代表服务端过滤器、数据库 nonce/receipt、实时用户授权或 S08 全链路已完成。没有共享 token、Cookie、JWT、任意 header 或请求指定 URL 的兼容入口。

## API 与配置边界

`com.aetheris.security.protocol.transport.SignedHttpClient` 只依赖 JDK 21 和现有协议 core；`Response.readJson` 使用模块已有 Jackson。构造注入 caller、WORKLOAD signer、verifier、Clock、TimePolicy、固定 `Map<String, URI>` audience→origin、每 audience 的 operation allowset 和 Limits。两张表及每个 allowset 都复制为不可变对象；origin 只能是无 userinfo/query/fragment/路径前缀的 HTTP(S) origin，不能从请求或远程响应建立。

```java
try (var transport = new SignedHttpClient(
        "workflow", installation.workloadSigner(), installation.verifier(), clock,
        TimePolicy.defaults(), Map.of("metadata", URI.create("http://metadata-service:8081")),
        Map.of("metadata", Set.of("execution.sql")), SignedHttpClient.Limits.defaults())) {
    var request = new SignedHttpClient.Request("metadata", "POST",
            "/internal/v2/executions/sql", "execution.sql", exactSerializedBytes);
    var verified = transport.send(request);             // MVC/blocking adapter
    int actualStatus = verified.status();               // signed 4xx/5xx remain unchanged
    byte[] authenticatedBody = verified.body();
    // WebFlux: use sendAsync(request), adapt the returned CompletableFuture and propagate cancellation.
}
```

`Request` 不接受 URL 或 headers map；raw body 在构造与 accessor 时复制。用户证明只能通过额外显式 String 参数传入 `X-Aetheris-User-Proof`，限制 12288 个可见 ASCII 字符，不自动采集当前请求上下文，也不转发用户 Cookie/JWT。其原始值的SHA-256（无头则零字节hash）纳入请求证明必需字段`userProofSha256`；接收方必须按实际唯一头重算，不能只验两份独立签名。用户证明验签、audience/转发 ACL、用户活动状态及其与执行主体的业务绑定仍由接收方完成。

wire header 固定为 `X-Aetheris-Workload-Proof`、可选 `X-Aetheris-User-Proof`、响应 `X-Aetheris-Response-Proof`；请求 Content-Type/Accept 固定 application/json、Accept-Encoding=identity。三种证明 header 不能互相替代。path/method/body/op 由 core 严格验证并签名，每次显式调用生成独立随机 nonce；业务定义、run、attempt、fence、参数等必须位于传入的精确 body bytes。

只有通过 `verifyResponse` 后才创建 `Response`，绑定配置 receiver、本地 caller、保存的原请求 nonce、实际 HTTP status 和接收到的完整 raw bytes SHA256。响应 bytes 及已验签 proof 不可变。应用可以读取 bytes 或显式调用 readJson；无响应证明、重复/超长证明、错 key/nonce/status/body/caller/receiver、编码响应和任何 3xx 均失败。签名有效的 401/403/409/500/503 原样交给应用映射；签名无效的错误响应属于 transport UNTRUSTED_RESPONSE，不能伪装业务 401。

## 限额、deadline 与关闭

默认 request/response 各 1 MiB、并发上限 32、连接 2 秒、总 deadline 10 秒。请求/响应硬上限均为 16 MiB，并发配置最大 256，总 deadline 最大 60 秒。容量满立即 BUSY，不排无限队列。

BodySubscriber 先检查声明 Content-Length，再逐批核对 ByteBuffer 剩余字节；超过上限取消订阅，绝不先无界 readAllBytes 再检查。只保存受限原始 body，不自动解压；无限 chunked body 必须在字节上限或总 deadline 处失败。独立 deadline 覆盖响应 body 流读取；不能仅依赖 JDK 的响应头 timeout。取消返回的 CompletableFuture 或关闭 transport 都会取消底层请求；同步调用被中断也取消请求，并将 InterruptedException 返回调用方。

每次 exchange 创建独立 HTTP/1.1 HttpClient，不复用连接；每实例共享受控 virtual-thread executor 和单个 deadline scheduler。请求完成、失败、超时、取消均 shutdownNow 该 HttpClient；transport.close 取消所有活跃调用并关闭 scheduler/executor。调用方必须关闭 transport。该实现明确有建连、TLS 握手及 selector 创建开销，尚无高吞吐性能结论；后续 P03 实测后才能评估连接复用。

## JDK 重试边界

JVM 必须在启动时设置以下参数，库不修改全局属性；构造和发送均检查真实 JVM input arguments 与当前属性一致：

```text
-Djdk.httpclient.disableRetryConnect=true
-Djdk.httpclient.redirects.retrylimit=1
```

禁止 enableAllMethodRetry=true。仅在运行时 setProperty 不足以满足启动契约，因为 JDK MultiExchange 将相关值缓存为 static final。系统联网属性和语义见 [Oracle Java 21 java.net.http 文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html)。这里的 retrylimit=1 在当前 JDK 21 实现表示总尝试一次：MultiExchange.responseAsyncImpl 在 I/O 前先增加 attempts，大于 max_attempts 即失败。

本机 JDK 21.0.7 src.zip 已核对：disableRetryConnect 只禁 ConnectException 重试；ConnectionExpiredException 对 GET/HEAD 仍有独立分支。真实 ServerSocket 反例中，只有 disableRetryConnect=true 时，GET/POST 两次显式调用实际收到三次请求，即使每次使用 fresh client 也会重发。因此不能仅凭 NEVER redirects、fresh client 或单次 body publisher 宣称无重试。transport 另外限制总尝试为 1，并保留拒绝重复订阅的 publisher。该结论绑定当前 JDK 实现，升级 JDK 时必须重跑实际 wire 计数测试；nonce/receipt 仍是业务接收方必要的副作用兜底，不能由网络重试设置替代。

## 验证范围

测试仅启动随机回环端口的 JDK HttpServer/ServerSocket；随机 Ed25519 keys 只在测试内存，不读取安装私钥，不连接已有服务，不调用付费 API。覆盖实际请求验签、双向绑定篡改、已签错误状态、redirect 目标零请求、请求/响应限额、无限/慢速 body、总 deadline、取消、并发容量、独立 nonce、close 与 selector 线程释放，以及断连 GET/POST 实际 wire 次数。core、configuration 与 transport 的最终测试计数以本轮 Maven 报告为准。

2026-10-18 本机 Java 21.0.7 最终 `mvn -B -o -f libs/security-protocol/pom.xml package`：145 项全部通过（core 115、configuration 6、transport 24），无 skip。原 GET/POST 断连反例保留不变，补齐 retrylimit=1 后收到次数从 3 转为严格 2。日志、源摘要、测试摘要与 JAR digest 在 ignored `target/transport-final-build.log`、`target/transport-delivery.json`。

默认 offline install 插件 3.1.4 因缺少本机缓存的 resolver-util:1.9.22 失败，不能记作成功。使用已完整缓存的 install-plugin:3.1.2 显式 `install-file` 安装同一已测 JAR/当前 POM 成功，没有修改 POM、联网补依赖或手工复制 Maven cache；target 与安装 cache JAR digest 一致。最终 Temurin 容器尚未运行同一 wire 验收，也未验证基础镜像锁定、应用服务端接入及生产部署，不能据此扩大结论。

后续独立复核发现用户证明未与操作绑定，新增两个实际HTTP wire用例先红（均缺签名摘要），修复后全量**152例**通过：原145例加2个wire绑定、4个真实USER置换/移除/插入/格式负例和1个旧固定向量缺字段拒绝。当前字段与构造API以README为准；旧145例及其JAR摘要是历史证据。root日志为`output/audit-user-proof-binding-{red,green,package,install-approved}.log`，不是全平台验收。

# Servlet 真实 HTTP / PostgreSQL 联验

范围：`libs/security-servlet` 库在两个 embedded Tomcat root context 内的实际 HTTP 接收行为，以及同一隔离 PostgreSQL 的 nonce 原子去重。不是业务应用接入、真实 auth/RBAC、部署或 S08 完成证明。

## 可重复入口

宿主必须使用绝对 Java 21 JDK；不要使用 Oracle javapath。先执行模块普通 `test` 编译 IT 并运行当前 60 项单测，再执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path=$env:JAVA_HOME+'\bin;'+(($env:Path -split ';' | Where-Object {$_ -notmatch 'javapath'}) -join ';')
mvn -q -o -f libs/security-servlet/pom.xml test
python scripts/audit-nonce-store.py --with-servlet-wire
python -m unittest discover -s scripts/tests -p test_nonce_store_audit.py -v
```

显式 wire 模式严格要求原 `PostgresNonceStoreIT` 一项、新 `TomcatProofWireIT` 一项均成功、零 failure/error/skip，并要求新的 Surefire XML、精确类名/方法名和真实 Tomcat 清理标记。默认不加开关仍只运行原 Nonce IT。source/class 指纹变化、空测试、跳过、超时、非零 Maven 退出、数据库保留时间/权限不符或清理失败均不通过。

Tomcat embed-core 为 Boot 3.4.7 管理的 test-scope 10.1.42。主库已缓存，首次离线预检发现 annotations-api 不完整，随后经现有 Maven 仓库补齐该依赖；不能称所有依赖此前均离线可得。Surefire 显式启动 `-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1`。

## 实际覆盖

| 部分 | 断言 |
| --- | --- |
| 正常工作负载 | SignedHttpClient → 真实 Tomcat → WorkloadProofFilter → 真实 PG nonce store → 原始 UTF-8 body 回显 → actual status/body/nonce/receiver/caller 响应验签；USER hash 来自实际唯一证明 |
| 双实例去重 | 24 次同一 nonce 并发发向两个 Tomcat、两个 store 实例；恰好一次业务链执行及 201，其他 23 次均为逐个验签的 409，拒绝 body 也核对 |
| 直接身份 | Workload 后 Direct 两过滤器实际注册；有效身份、缺失、重复、错误 audience、旧身份、测试会话禁用/不可用；可信属性不可移除，原身份 header 不交业务链 |
| HTTP 边界 | 原始百分号路径、query、旧 internal、缺 workload proof、gzip、超过 1 MiB body 拒绝；公开路径 forward 到 internal 被真实 FORWARD 映射拒绝 |
| 同步响应 | 禁止 startAsync 和输入流 ReadListener；业务先写私密片段再抛异常仍返回固定且签名的 500，不泄漏局部 body/异常文本 |
| 容器约束 | root context；两过滤器均映射 REQUEST/ASYNC/FORWARD/INCLUDE/ERROR；每接收端线程上限 16、队列 64、连接 32、accept backlog 16、输入超时 2 秒、header 上限 32768；实际不完整 header/body 超时且不进入业务链 |
| 清理 | 两个端口不可连接、owned executor/connector 线程终止、owned Tomcat 临时目录删除；独立 label/name 校验删除本次 PG 容器及空网络，无 volume |

SessionValidator 是本测试中可切换的本地状态，私钥由 JDK Ed25519 内存生成，不读取应用密钥。PG 仅 `127.0.0.1:<随机端口>/aetheris_nonce_audit`，新 `auth` schema + 独立 `nonce_wire_runtime`；原 IT 继续使用 `metadata` schema。runtime 仅 SELECT/INSERT/DELETE，没有 UPDATE。

PG 容器仅 cached `postgres:16-alpine` / `--pull never`、随机 owned label/name/network、512 MiB / 1 CPU / 128 pids、tmpfs，无现有服务或数据库访问。输出在 `output/audit-nonce-store/<uuid>`；随机 fixture 密码仅经环境变量传递，日志保存前再次去敏。

## 历史与回归

- `db3f5a20dca94f5eb9456de9e2c1791b`：首次两 IT 均通过；事后日志复核发现 body timeout 的 committed/reset 严重异常，因此不能当作最终清洁通过证据。保留原报告不回填。
- `7284c8e8b8bc42afaca73aabfa577e6b`：收紧真实 HTTP timeout 断言后，原 IT 通过，Tomcat IT 失败，完整清理；当时未打印 body status，不能从该记录推断 body EOF。
- `b5a8f2f5b06641f2b85ded30b46087a5`：精确红证据，header timeout 直接关闭，body timeout 实际 HTTP 500。WorkloadProofFilter 在读取 body IOException 后对已提交响应 reset，Tomcat 记录 IllegalStateException。原 IT 通过，Tomcat IT 失败，source/class 前后一致，完整清理。生产代码修复由主代理负责，测试不接受 500 代替超时拒绝。

主代理随后仅在生产 `unsigned` 错误响应入口增加 committed 检查：已提交则保留 connector 响应，不 reset 或补写；尚未提交仍固定拒绝。新增单测模拟 connector 先提交 408 再读取失败，保证不验签、不 claim nonce、不进业务链且无签名。独立联验代理未修改生产代码。

## 最终实际通过记录

run：`41aef74e759548f1ae2b26a93d880ac2`，UTC `2026-10-18T10:53:21.522854` 至 `10:53:34.617818`。

- 宿主真实 JDK `21.0.7+8-LTS-245`，Tomcat `10.1.42.0`；协议依赖 JAR SHA256 `117eeff2cd82211935f58a34533912b959ad9be90daf064d88b195cd96423be1`。
- 56 单测通过：Direct 5、Store 4、Workload 47；新增加的 connector committed 超时回归包含在 47 内。原 PG IT 1 项通过（1.111s），新 PG/HTTP IT 1 项通过（4.955s），全部零 failure/error/skip。脚本单测 8 项通过。
- 24 个重复请求：双接收端共计一次 201/业务链及 23 个签名 409；auth nonce 表最终 5 行，wire runtime 无 UPDATE 权限。metadata 原测试保留时间/并发 purge 验收仍通过。
- 不完整 header 超时直接关闭；不完整 body 超时实际 HTTP **408**，未签名，且没有严重容器异常。未放宽断言接受 500。两个端口关闭、owned connector/executor 线程退出、owned temp 删除断言全部通过。
- PG image ID `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`，RepoDigest 同 hash（完整仓库名记录于 JSON）；system identifier `7697959230356742179`，database OID `16384`，仅本次 `auth`/`metadata` schema。
- 执行前后全部记录的 Java 源、pom、SQL template、audit 脚本/测试及编译 class 指纹一致。PG 容器、网络、volume 清理均通过；另用相同唯一 label 再查，三类资源均不存在。

证据目录：`output/audit-nonce-store/41aef74e759548f1ae2b26a93d880ac2/`。包含去敏 Maven 日志、两个 IT XML、三个 unit XML、`report.json` 和独立 `post-run-evidence.json`。旧成功/失败证据均保留原样。最终报告 SHA256 `a2beef8639a9549aebe989bdbe0682a59f4af581e7e9ceb7dfc5f015b92b8c17`；post-run evidence SHA256 `e22c55bfb2c257e3a43c243d496f4da80e551e87efbde0668d9cdf4c03ed4c7b`。

| 最终运行文件 | SHA256 |
| --- | --- |
| security-servlet/pom.xml | `daffa1a7593a7528dad6c506eeb0e07b9051dc38bb0663dbc2bb7a6453e76ebe` |
| TomcatProofWireIT.java | `7cb41a8dc73bc1d0c14970737607c86fdaf6b944350b63b651dcdb07bafd6ec5` |
| WorkloadProofFilter.java（主代理修复） | `36ccfc36fc663d15036d82ecccf08aed70d9a682ad96584fef3ac3568edc1490` |
| audit-nonce-store.py | `e23b0537c2a4231cf5c5b27f14cccbf489a8e93251dd4a564135709e8d9dd501` |
| test_nonce_store_audit.py | `716d53c9c6e56adecf3de4e278f71b2abbaaf733218badbafd00873fc973c4f8` |
| WorkloadProofFilter.class | `b1b08bfcf2b65565d830a5ff7fae439d18f3ec229f01a4f5efaee3962b45df9b` |
| TomcatProofWireIT.class | `31f98bdf0abf86a11a318827ad455f327bb218fc5d5ed900506038ce199c35dd` |
| tomcat-embed-core-10.1.42.jar | `c0ca6acafe5ad63cd5de16ec8894318a7b53ea11e3db1bc217fd5f2a9746a790` |
| tomcat-annotations-api-10.1.42.jar | `bf1b0dbfece00d44d05e0329be2b8c8ba61b7dc7f041c27c0c83891e9fe5f5e5` |

## 结论边界

2026-10-18 21:16 当前版本复验：run `e32ed49394ec4aadb9b97a77e11c93d7`，60普通单测及原nonce/真实Tomcat两项IT通过，报告SHA256 `8201d661db12bb9b17ade076acfe2fd5320d1b6d863375744207e345d77bcce2`。源码与class结束指纹一致；24重复请求仍1业务+23签409，header关闭/body408、零严重异常、自有线程/端口/temp及PG容器/网络清理全过。`cleanup-independent.json`另核对本轮三次run资源及本模块JVM均0。

本次先有两轮失败：`6f559cccbaed4e9c9dfaa6d0bee5786b`、`3e29b8b936764795b2c9d62e5f56b663`。失败在HTTP客户端仍未关闭时，executor收尾等待恰2秒即取活动数，第二轮诊断显示断言读取2、随后诊断已0且所有owned worker均在TaskQueue等待。仅修测试生命周期：先close客户端，再最多5秒要求executor活动与队列稳定100ms均为0，保留线程栈失败诊断；请求自身5秒超时上限、生产2秒connector配置、零严重异常与最终线程终止要求均未放宽。没有修改生产库代码或将失败旧报告改为通过。

全部 dispatcher 已真实注册，实际触发 REQUEST 与 FORWARD；不能将注册检查写成每一种 dispatcher 都已产生业务场景。未运行真实 auth、RBAC、业务控制器、Gateway、Cron、应用容器、TLS/反向代理、跨机器网络或正式 release。Tomcat 的 ThreadLocal/RMI 深度泄漏扫描因未配置 add-opens 发出提示；本测试证明 owned connector/executor 线程退出与端口关闭，不能声称 JVM 所有隐藏 ThreadLocal 均已扫描。nonce 唯一不等于业务 receipt；未知结果、副作用幂等及撤权语义仍须后续业务链联验。

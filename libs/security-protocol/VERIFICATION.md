# S08 协议库第 1 包验证记录

2026-10-18，本地 JDK 21，独立模块 `libs/security-protocol`。仅此目录发生本包写入；没有修改 root/module pom、Dockerfile、应用配置、既有服务或共享台账，没有启动现有服务，也没有使用安装密钥。

## 实际执行

| 阶段 | 命令/范围 | 实际结果 | 本地日志 |
|---|---|---|---|
| 先行测试基线 | `mvn -q -o -f libs/security-protocol/pom.xml test` | exit 1；API 尚未实现，编译缺类型。这不是运行期行为失败证据 | `red-baseline.log` |
| 初版行为测试 | 同上 | exit 0；68 例 | `implementation-test.log` |
| 配置缺陷反例 | `-Dtest=KeyMaterialTest#registryRejectsMalformedEncodedPublicKeyAtStartup test` | exit 1；1 例失败，坏编码 EdECPublicKey 注册未被拒绝 | `registry-regression-red.log` |
| 修复及向量 | 全量 test | exit 0；修复为注册时重解析并快照标准 X.509 公钥 | `key-and-vectors-test.log` |
| 最终全量 | `mvn -o -f libs/security-protocol/pom.xml test` | **115 例，0 failure、0 error、0 skipped，BUILD SUCCESS** | `final-test.log` |
| 打包 | `mvn -o -f libs/security-protocol/pom.xml -DskipTests package` | exit 0；复用已完成测试，生成普通 jar，没有 Boot repackage | `package.log` |

最终测试分布：BoundaryTest 30、FixedVectorsTest 5、KeyMaterialTest 12、ProtocolTest 68。全量日志结束时间为 2026-10-18T18:00:39+08:00。

实际覆盖：三种 typed proof 正向验签；caller 归属和 USER/WORKLOAD 物理隔离；REQUEST/RESPONSE typ 隔离；signer key/purpose/owner 绑定；算法混淆和旧 HMAC；重复/未知/缺失字段；UTF-8、base64url 非规范末位/padding、溢出/浮点/字符串整数；时间边界和硬 TTL；路径歧义、body/method/audience/op；响应 nonce/status/receiver/caller/body 替换；PKCS#8/X.509/Ed448/过短/尾随 DER；160 个任务在 8 线程共享 signer/verifier 下完成；纯 JDK 独立重现固定向量签名。

已检查 jar 内容：仅主代码和 Maven 元数据，没有测试私钥、向量、测试类或 Spring 运行时。源码尾随空白扫描为空。源码、fixture、日志和 jar 的 SHA-256 清单见 `verification.json`；target 与 *.log 按仓库既有规则忽略，仍保留在本机供复核。

## 交接边界

本包可供根接入，但 **S08 尚未闭合**。调用方仍须实现 caller ACL、可信 HTTP 字节取样、实时主体/资源授权、nonce 唯一 claim、execution receipt/unknown、run/snapshot 回查、API-key 资产绑定及旧入口关闭。协议库有意无数据库、HTTP 或业务授权依赖，不会使重复 token 自动失效，也不会阻止应用错误地传入从 payload 自报值构建的 ExpectedRequest。

固定向量私钥是一次性公开测试 fixture，不能作为安装密钥。真实 keypair 必须独立生成并按容器用途挂载。本包没有安装到全局 Maven 仓库、没有构建整个平台、提交、推送或部署。

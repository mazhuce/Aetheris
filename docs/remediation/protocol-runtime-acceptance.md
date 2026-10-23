# security-protocol 在实际 Temurin 21 JRE 中的运行兼容性

2026-10-18 独立运行验收，**仅证明冻结协议库及其既有测试在指定缓存 JRE 镜像中可运行**。不是平台、S08 全链路、应用 Docker 构建、正式签名 release 或生产部署验收。

## 实际结果

Run：`422e41459d064008aae94720fd4a25b6`。容器 exit **0**；完整 152 项 JUnit 测试实际运行：

| 指标 | 数量 |
|---|---:|
| found | 152 |
| succeeded | 152 |
| failed / skipped / aborted | 0 / 0 / 0 |
| containersFailed / containersSkipped / containersAborted | 0 / 0 / 0 |

JUnit 初次静态 discovery 为 51 项；动态测试工厂执行后最终 found=152，与既有 Surefire 七份 XML 合计 152 一致。helper 对空发现、数量不符、跳过、终止、容器失败均返回非零，不把未执行测试算通过。新增验收工具自身的 **6 项安全测试也全部通过**。

- 实际系统：Linux amd64。
- 实际 `java.vendor`：Eclipse Adoptium。
- 实际 `java.runtime.version`：`21.0.12.1+1-LTS`；specification version=21。
- 测试执行：2026-10-18T10:30:42.828853109Z 至 10:30:48.952911758Z。
- 完整 run（含宿主编译、Docker 准备及清理）约 2026-10-18 10:30:40–10:30:50 UTC。

缓存镜像：`docker.1panel.live/library/eclipse-temurin:21-jre`。实际按 inspect 得到的 image ID 启动，避免 tag 漂移；没有拉镜像。

- Image ID：`sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5`
- RepoDigest：`docker.1panel.live/library/eclipse-temurin@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5`
- 另有同 digest 的 `eclipse-temurin` alias，机器报告保留实际完整 RepoDigests。

## 输入与执行方法

工具：`scripts/audit-protocol-runtime.py`；JUnit Launcher helper：`scripts/security/RunProtocolTests.java`。

1. 只读现有 `libs/security-protocol/target/classes`、`target/test-classes` 和 Surefire XML，不重新编译或修改协议源码、原 target、POM 或应用。
2. 核对宿主 XML 152 项全成功、无 skipped，且所有 XML 的 classpath 一致；仅复制其中精确 **11 个 cached dependency jar**，另加 cached `junit-platform-launcher:1.11.4`。不运行 Maven、不补下载依赖。
3. 使用绝对宿主 `JAVA_HOME=C:\Program Files\Java\jdk-21` 的 javac，以 `--release 21` 编译新增 helper，输出到独占 ignored bundle。
4. helper 选择 `com.aetheris.security.protocol` 整包并实际执行，包含已有签名/验签、userProofSha256、configuration、HTTP transport、断连不自动重放、取消和线程释放测试；不筛选、禁用或改写任何测试。
5. 测试 stdout/stderr 在 helper 执行期间抑制；仅输出固定 JSON 摘要和异常类型计数，不打印证明、fixture 私钥、异常正文或完整 test output。

进程启动显式携带：

```text
-Djdk.httpclient.disableRetryConnect=true
-Djdk.httpclient.redirects.retrylimit=1
```

容器 isolation：`--network none`、只读 root、`10001:10001`、512 MiB、1 CPU、256 pids、drop ALL capabilities、no-new-privileges；仅只读挂载本 run bundle，`/tmp` 为 128 MiB tmpfs。测试可使用容器内 loopback 临时 HTTP listener，但无外部网络。没有挂载工作区、真实 secrets、Docker socket 或现有服务；只包含明确公开的测试 fixture。

## 可追溯证据

机器报告：`output/audit-protocol-runtime/422e41459d064008aae94720fd4a25b6/report.json`。

- report SHA-256：`B86355282D5600C0AF7C4639F685EF8F057AFD96581C59A982B9D8F4E6B98BB1`
- helper source SHA-256：`52882FFF7A5DAF4AC368B828DC952B523482940883FD28300C5E78B465075D6F`
- audit script SHA-256：`40C008CD534E0DA593680A58A6F3A7DFDA3E82F11E524E9F385819F4C8F9736B`
- audit safety test SHA-256：`75D3D3D51C17AEE24D1C9283F71C33AC860876525516BC0E9D21A1969FD1D8A0`

机器报告逐项保留运行源码、pom、fixture、编译 class/resource、宿主 Surefire XML、12 个 jar 及新 helper class 的 SHA-256；运行末尾实际复核 `sourceHashesMatchAtEnd=true`、`artifactHashesMatchAtEnd=true`。文档更新不被混入运行代码 hash，源码冻结原因与范围保持清晰。

## 清理与限制

finally 先核对随机 name/owned label，删除本次临时容器，再核对独占 bundle 的绝对目标位于本 run 输出目录后删除。报告中 `containerRemoved=true`、`bundleRemoved=true`；随后独立 Docker 查询再次确认本 run label 容器为 0、bundle 不存在。未创建额外网络、命名卷或安装密钥。

本次不包含应用 image build、十服务启动、nonce/receipt 数据库、业务 RBAC、真实 JWT/API-key 链路或密钥安装/轮换；不能从库测试成功推断这些阶段已完成。JDK21 HTTP 不重放的结论仅限当前冻结实现、指定启动参数及既有测试场景，不能替代业务写入 receipt/unknown 防重放。

复跑：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
python -B -m unittest discover -s scripts/tests -p test_protocol_runtime_audit.py -v
python -B scripts/audit-protocol-runtime.py
```

默认使用全新随机输出目录，拒绝覆盖旧结果。冻结测试数或依赖更新后应先重新审阅 baseline，不能直接沿用本次 152 项结果。

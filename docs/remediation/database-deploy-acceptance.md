# 数据库部署 gate 隔离验收

## 当前104源：九schema nonce与datasource.write

2026-10-18 UTC 11:16:37–11:18:51，run `2be0a29ec0484050b97ae780bfa166bd`，固定104份migration / 9runtime / fresh receipt / 实际Compose identity，**1正23负通过**。报告 `output/audit-database-deploy-gate/run-20261018-104-v2-corrected/report.json` SHA256 `59435d457baada2d04ea79c661ee4d82f052899d8032cefd36c1d460195b411c`。

新增九个服务各自schema的nonce表与索引、auth V10独立`datasource.write`目录，仅sys_admin关联，不给viewer/现有自定义grant自动扩权。全部104份迁移实际执行，末尾sourcehash一致、DB前后指纹一致，迁移凭据退休且四项cleanup全true。11项工具安全单测通过；保持固定104份预期，没有改成接受任意数量。

首轮run `8dbb12cf6f4940f892e1e6dac13dd422`在AI迁移失败：新SQL误写`ai_agent`，实际服务schema是`ai`。该首次失败报告与清理结果保留；仅修本轮尚未部署的新迁移，再完整复验，不repair历史数据库。11:28:38 UTC独立复核专属container/network/volume/JVM均0、私密fixture不存在，证据在同目录`cleanup-independent.json`。

本结果仍使用UNSIGNED合成11镜像manifest证明gate边界，没有验签或启动其应用镜像；不代表九应用启动、历史实库升级或生产发布。下面完整保留此前94/93源历史，不将历史结果改写成104源。

## 当前94源与最终V28：独立复验

2026-10-18，run `6a18eda05e6e411696c634f42eea5bb1`，**10:55:42–10:57:55 UTC**，exit 0 / passed。显式使用 `C:/Program Files/Java/jdk-21`，工具核验实际JDK21；运行前核对CLI和现有隔离/清理实现，没有修改gate、roles或SQL通过门槛。

实际完成九schema的94份源迁移，含最终metadata V28（SHA256 `636b6b2e13fe0343b8fead33b28968354d5c6184ef1e74127c9bb44f978412b4`）。九runtime `ai/auth/governance/ingestion/metadata/quality/serving/system/workflow` 的真实登录/权限/Flyway核验通过并生成fresh receipt。**1正例 + 23负例全部通过**，11项工具安全单测也全部通过。

正例调用计数 `prepare=1, runner=4, verify_receipt=2, compose_identity=1`，绑定记录为 verified-not-deployed。23个负例全部exit1、无verified记录、隔离数据库指纹不变；其中10个配置覆盖负例在verify_wiring即拒绝，四种live函数调用均0；最后“实际Compose指向另一数据库”负例真实调用compose_identity=1后拒绝。没有用mock替换gate或身份函数。

本轮真实Compose版本 `2.38.2-desktop.1`；仅使用已缓存postgres镜像 `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`，未拉取镜像。两个独立Compose project/tmpfs/随机回环端口均由工具创建。受测数据库identity为 systemIdentifier `7697959837255741475`、databaseOid `16384`、database `aetheris_deploy_gate_audit`。

正负例前后，受测隔离库的业务哨兵行、inventory、role/ACL/Flyway history与角色凭据聚合指纹完全一致。107个受测源码文件（其中94份SQL）结束摘要一致。迁移凭据退役通过；工具核验containersRemoved/networksRemoved/volumesAbsent/privateFixturesRemoved四项均true。10:58:48 UTC额外独立只读核对：本轮标签容器0、网络0、两项目卷0、私密fixture不存在、对应Java进程0。没有连接、修改或重启现有数据库。

机器证据目录：`output/audit-database-deploy-gate/run-20261018-94-v28-review/`，含report.json、脱敏run.log及独立cleanup-independent.json。

- report SHA256：`2a2d2b4ce38a9c99475f0f6c4293fd26c1c5e571c13f0de8e6142be94591b55b`
- run.log SHA256：`e38b4a47bd63bd53b17bee743d227b625ee1e908bc5fe59c463cc37108aa94f8`
- fresh receipt SHA256：`8803f6a2275e66336f6bb21beaf642dd4713593e72104871e19b271ba4e2148e`
- deploy_gate.py SHA256：`8885fca1f63a21aac4c20fa0bbefe0b68df436aaa3d5d6f1cb83983ed721064c`
- audit脚本 SHA256：`d438cb74321e5715111677f7009ae1575f660fc5a2ffaffc78753b79117c042c`
- 11项安全单测文件 SHA256：`d1f5d22721bff06d272bf79a480df2e26715678b04f434a89520e2ce883af8fc`

manifest仍为明确UNSIGNED的合成11 image digest fixture；没有拉取、验签或启动这些应用镜像。本结果证明当前94源数据库部署gate准入/拒绝边界，不是正式release、九应用启动、现有环境升级或生产部署通过证明。

以下至文末为原93源历史原文，逐字节保留，不回填为当前94源结果。追加前历史全文SHA256：`5966992193b0819bdc55bbcd717c2a91d1f1a66c2240414ac5d0abaa4d038668`。

---

2026-10-18，验证当前 `deploy_gate.py`、实际 Compose service 映射和 schemaVersion 2 migration source 绑定。**本报告是 gate 验收，不是签名 release、九应用启动或生产部署通过证明。**

后续S03新增metadata V28，源迁移现为94份，工具冻结计数已同步94。下述run仍绑定当时93份源；新源必须生成新receipt及新验收run，不能用此历史passed覆盖当前变化。当前gate会因旧receipt/source不一致而阻断，而非自动承认新迁移。

## 当前冻结源码复验：配置覆盖边界

根修复 Spring/Hikari/JVM 配置覆盖入口后，重新执行独立完整联验。最终 run：`049d14e1a2da4060a6bc228c5dd2b393`，2026-10-18 **10:16:16–10:18:26 UTC**，exit 0、状态 `passed`。

**1 个正例 + 23 个负例全部通过**。保留下节原有 1+13 案例，再增加以下 10 个经真实 `docker compose config` 合并的 runtime 环境覆盖案例：

| 新增案例 | 实际环境键/值类别 | 实际结果 |
|---|---|---|
| Hikari JDBC URL | `SPRING_DATASOURCE_HIKARI_JDBC_URL` | exit 1，无记录，live 前阻断 |
| Hikari username | `SPRING_DATASOURCE_HIKARI_USERNAME` | 同上 |
| Hikari password | `SPRING_DATASOURCE_HIKARI_PASSWORD`（纯合成值） | 同上 |
| config location | `SPRING_CONFIG_LOCATION` | 同上 |
| additional config location | `SPRING_CONFIG_ADDITIONAL_LOCATION` | 同上 |
| JDK DB host | `JDK_JAVA_OPTIONS=-DDB_HOST=unverified` | 同上 |
| Spring 小写别名 | `spring_datasource_hikari_jdbc_url` | 同上 |
| Spring 点别名 | `spring.datasource.hikari.jdbcUrl` | 同上 |
| Spring 连字符别名 | `SPRING-DATASOURCE-HIKARI-USERNAME` | 同上 |
| JVM argfile | `JAVA_TOOL_OPTIONS=@/unreviewed/overrides.args` | 同上 |

这里的“live 前阻断”有实际调用证据：child 只用 `sys.setprofile` 观察真实函数调用，不替换函数、返回值或数据库。10 个新案例全部在真实 `verify_wiring` 抛出 ValueError，`roles.prepare / roles.runner / roles.verify_receipt / gate.compose_identity` 调用次数均为 **0**。断言不只是检查非零退出。验收 harness 随后照常读取原数据库验证未变，不把这些独立验证读取算作 gate 的准入操作。

正例对应调用次数为 `prepare=1, runner=4, verify_receipt=2, compose_identity=1`；不同真实 Compose 数据库反例为 `prepare=1, runner=4, verify_receipt=1, compose_identity=1`，确认真实远端身份检查实际执行。所有 23 个负例均无 verified 记录，原业务行、完整 inventory/role/ACL/Flyway history 及角色凭据聚合指纹不变；末尾 source hash 完全一致。

本次仍完整执行九 schema 的 **93 份迁移**、九 runtime 登录/权限与 fresh receipt，使用合成 11 image digest manifest；所有应用仍未启动。数据库 system identifier `7697949674842378275`、OID `16384`、database `aetheris_deploy_gate_audit`。

当前机器报告：`output/audit-database-deploy-gate/run-20261018-config-boundary/report.json`。

- report SHA-256：`235DE3B9E24187F3B243FCEE86272100EF5947281301439E93C02704FCDF2239`
- sanitized run.log SHA-256：`ECE1E9D48F32F8E6C021EB0DE7A2B80EDD1D431FECD0C4A1C328E78929D485AD`
- frozen `scripts/database/deploy_gate.py` SHA-256：`8885FCA1F63A21AAC4C20FA0BBEFE0B68DF436AAA3D5D6F1CB83983ED721064C`
- acceptance script SHA-256：`5D0554B88E8AC6A552406FDDA9634005816188C0B36E016A6170C39563EF5A51`
- new safety test SHA-256：`9FD5EE98EC414CCF0926F2531EA9A8A98F177B0930144CE70D11A1C1AA228E09`

新工具安全单测 **11 例全部通过**。migration credentials 退役通过；`containersRemoved/networksRemoved/volumesAbsent/privateFixturesRemoved` 全部实际核验为 true。没有回填或覆盖旧报告；旧 passed 只绑定其旧 source hashes，不能用来证明本次新 gate。

## 首次源码联验：历史证据

正式 run：`a5750deff5ed4fb89aedf33e2a9de80a`，2026-10-18 10:11:30–10:13:12 UTC。命令 exit 0，状态 `passed`。

- 真实 PostgreSQL：仅已缓存 `postgres:16-alpine`，image ID `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`；无镜像拉取。
- Docker Compose `2.38.2-desktop.1`；真实 base + roles override + 合成 digest image override + 一次性隔离 override 经实际 `docker compose config --format json` 合并。
- 九个 schema 使用当前全部 **93 份迁移**；独立 owner/migration/runtime roles，迁移账号运行后退役，实际完成九个 runtime 登录、权限及 Flyway source/history 检查后生成 fresh receipt。
- 合成 manifest 明确含 `UNSIGNED gate acceptance fixture; NOT a release`，schemaVersion 2 绑定 93 source SHA-256 和 11 个格式合法的合成 image digest。没有获取、拉取、验签或运行这些应用镜像。
- 正例调用真实 gate；`gate.compose_identity()` 未被替换，实际通过 `docker compose exec` 从容器 PostgreSQL 读取 cluster system identifier、database OID 和 database name，与 operator JDBC endpoint 的实时 inventory 一致后生成 `verified-not-deployed` 记录。
- 本次数据库 identity：system identifier `7697948449555931171`、database OID `16384`、database `aetheris_deploy_gate_audit`。

机器报告：`output/audit-database-deploy-gate/run-20261018-second/report.json`。SHA-256：`0CEEADA36311CADB103F8F87675833530E7C873F0157B1E2D4C9428A206F9E13`。同目录 `run.log` 仅保留阶段与最终状态，不含解析后的 Compose、密码或完整异常输出。报告记录 frozen gate/roles/release/Compose/runner/93 SQL 的实际 source hashes，末尾复核完全一致。

## 正反例

| 案例 | 结果 |
|---|---|
| 正确 v2 manifest、93 sources、11 images、真实 fresh receipt、对应 secret 挂载、Flyway off、同一 Compose DB | exit 0，生成绑定记录 |
| schemaVersion 1 | exit 1，无记录 |
| receipt 缺失 | exit 1，无记录 |
| receipt 超过 24 小时 | exit 1，无记录 |
| runtime password 文件值变化 | exit 1，无记录 |
| manifest source digest 与 checkout 不符 | exit 1，无记录 |
| receipt source binding 与 checkout 不符 | exit 1，无记录 |
| auth runtime 使用共享 DB account | exit 1，无记录 |
| 缺少 roles override | exit 1，无记录 |
| 实际 Compose image digest 与 fixture manifest 不同 | exit 1，无记录 |
| quality runtime Flyway 开启 | exit 1，无记录 |
| secret 文件内容相同但实际挂载文件路径不同 | exit 1，无记录 |
| JVM spring.datasource override | exit 1，无记录 |
| 实际 Compose 指向第二个真实 cluster/database，operator/receipt 仍为原库 | exit 1，无记录 |

每个负例之后均重新读取原真实数据库：完整 inventory（包含 role 属性、membership、ACL、对象及全部 Flyway history）、system_config 业务行以及本临时集群 runtime role 密码 verifier 的聚合指纹。全部与基线相同；报告仅记录聚合 SHA-256，不输出 verifier、业务行或密码。运行末尾再次比较一致。

“source 变化”案例通过修改私密 fixture manifest/receipt 的 source binding 实现，没有改动仓库中已冻结的 SQL 迁移。不同数据库案例启动第二个随机 Compose project 的 PostgreSQL；未 mock 数据库、receipt 校验、inventory 或 compose identity。

## 隔离与清理

工具：`scripts/audit-database-deploy-gate.py`。新增安全测试：`scripts/tests/test_audit_database_deploy_gate.py`；首次联验时 9 例通过，当前扩展版 **11 例全部通过**。

- 使用绝对 `C:\Program Files\Java\jdk-21`，先实际核验 specification version=21；清除继承的应用凭据/JVM override，PATH 排除 Oracle javapath。
- 复制 runner 源到本 run 的 ignored `configs/database/private/deploy-gate-<uuid>/runner`；仅进程局部修改 `roles.RUNNER`，独立 target，不和其他验收共用 `scripts/database/runner/target`。
- 两个 project 均为随机 `dg-<uuid>-primary/other`，仅执行 `up --no-deps --no-build --pull never postgres`；应用、Redis 和 Prometheus 均未启动。
- PostgreSQL 512 MiB、1 CPU、128 pids、loopback 随机 host port、256 MiB tmpfs；重置 postgres/Redis 卷引用、顶层命名卷和 base 的固定 network name/ipam，避免触碰 `aetheris_default` 或既有数据卷。
- finally 先再次 retire migration credentials，然后按本 run 精确 label 验证并删除容器/网络，确认无对应 project volumes，删除本 run 私密目录及独立 runner/target。
- 正式 run 的 `containersRemoved/networksRemoved/volumesAbsent/privateFixturesRemoved` 均为实际查询得到的 **true**。无付费调用、真实密钥读取、现有库连接、提交或部署。

正式 run 前有一次隔离 fixture 配置失败：`04e72946df404f3fb25a68e6dce9e797`。原因是清空顶层命名卷后未同步去掉未启动 Redis 的 `redisdata` 引用；仅修新验收工具生成的 fixture 后重跑。该失败发生在 Compose config 阶段，未启动容器，四项 cleanup 也全部 true；没有修改 gate、roles 工具或应用掩盖失败。

## 复跑

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
python -B -m unittest discover -s scripts/tests -p test_audit_database_deploy_gate.py -v
python -B scripts/audit-database-deploy-gate.py
```

工具默认生成新的随机 report 子目录，拒绝覆盖已有报告。环境需已有 Docker Compose、缓存 PG16、JDK21、Maven 和 runner 离线依赖。报告中run严格要求当时93份frozen migration，后续工具已更新94；新增迁移后应先审阅并更新验收基线及receipt，不能沿用本报告。

## 未覆盖与已上报事项

1. 本次没有实际执行 deploy-remote、Cosign 验签、应用镜像 pull、九应用启动或回滚；只有 gate 输入为合成 manifest。不能据此宣称真实发布 provenance 或整个平台可部署。
2. 首次联验发现的 Spring/Hikari/JVM 配置覆盖入口已由根修复；当前冻结 gate 的 10 个新增真实 Compose+gate 案例均确认在 live 前拒绝。此结论覆盖本报告列明的准入配置入口，不外推为所有未来 Spring 配置、自定义 entrypoint 或实际应用启动行为已验收。gate 文件修改权始终由根持有，本代理未改其实现。
3. 机器报告与私密 record 不能替代真实部署阶段的 fresh receipt、当前 source/image 签名和再一次 live admission；正式 run 私密 fixture 与绑定 record 已清理，公开只保留报告摘要及 SHA-256。

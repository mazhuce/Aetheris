# scripts

恢复与核对日期：2026-10-18。初始索引从非 NUL 历史版本 `80c72d8` 恢复；以下补充以当前脚本接口为准。脚本存在、静态测试通过和真实环境验收是不同层次。

| 脚本 | 用途 |
| --- | --- |
| `wsl-hive-test-server.sh` | 在 WSL 中启停 HDFS + Hive Metastore + Spark Thrift Server 测试栈 |
| `windows-wsl-hive-relay.ps1` | 建立 Windows 到 WSL 的临时端口转发，供 Docker 内的服务访问 Hive |
| `spark-smoke-test.py` | 通过真实平台身份验收 Spark 查询、权限和专用测试分区重跑，见 [验收说明](../docs/spark-sql-smoke.md) |
| `gen-warehouse-data.sh` | 建立 ods/dwd/dws/ads/dim 五层库并生成电商域测试数据，详见 [warehouse-layers.md](../docs/warehouse-layers.md) |
| `warehouse/*.sql` | 上述脚本调用的分层 DDL 与加工 SQL，按序号执行 |
| `maven-mirror-settings.xml` | Maven 国内镜像源配置 |


## 当前交付与验证入口

| 脚本 | 当前用途 |
| --- | --- |
| `check-document-integrity.py` | 检查全部已跟踪 Markdown 的 UTF-8、非空/NUL 以及核心文档相对链接 |
| `check-workflow-contract.py` | 解析 workflow YAML、检查 Action SHA 与发布门禁连接；CI 显式安装 PyYAML 6.0.2 |
| `verify-flyway-migrations.py` | 检查每服务源迁移版本唯一性；不替代真实历史数据库升级演练 |
| `release-evidence.py` | 创建 v2 context、实际执行并绑定 producer、运行离线检查、聚合及 strict 内容校验 |
| `platform-release-acceptance.ps1` | 以显式 context/manifest 聚合当前运行证据；刷新验收页摘要 |
| `platform-release-acceptance-strict.ps1` | 重开并校验聚合报告实际引用的 context、manifest、checks 和运行报告 |
| `datasource-runtime-acceptance.ps1` | 九类数据源完整结果与清理门禁；`-KeepStack` 只用于诊断，不能得到发布通过 |
| `backup-postgres.ps1` / `restore-postgres.ps1` | 容器内 archive 文件加 docker cp 二进制链路、退出码检查、清理及 ShouldProcess |
| `verify-ci-run.py` | 有界等待指定提交的最新完整 CI push run，绑定 run ID 与 attempt 并检查必需 job |
| `release-manifest.py` | 收集 11 个镜像 digest 与九服务迁移源 hash、验证 CI/build，并按真实 Compose service 名生成 override |
| `deploy-remote.sh` | 验签并强制数据库 roles/live收据门禁，按 digest 启动，兼容时按上一签名清单回滚 |
| `database/deploy_gate.py` | 内存校验 resolved Compose、实际数据库 cluster/OID、源/secret/receipt，保存私密准入绑定 |
| `verify-image-signatures.sh` | 对清单中的全部应用镜像验签；兼容旧 tag 状态的回滚验证 |
| `base-image-inventory.py` | 不拉取镜像的默认引用清单检查，供后续同版本 digest 锁定使用 |
| `audit-workflow-engine-authority.py` | 自有隔离PG中逐步升级V32→V33→V34，检查可信引擎来源、派发唯一性、默认Runner、容量、原句柄及引擎成功证据放行下游；Auth及引擎业务结果为受控HTTP响应 |
| `audit-workflow-engine-chain.py` | 默认新鲜构建五个实际JAR与隔离PG/Redis，验证真实Auth至workflow的手动/Quartz/pipeline引擎来源、有界完成及无界意外结束不推进SQL；引擎HTTP结果为受控响应，不执行物理Spark/Flink；显式复用JAR需提供原留存manifest及独立核定的SHA256 |
| `audit-workflow-quality-child.py` | 六个实际JAR与隔离PG/Redis中的SQL/quality手动、Quartz、pipeline及撤权零reader连接；不覆盖物理Spark/Flink |

## 本地脚本回归的依赖顺序

从仓库根目录使用 JDK 21 及已安装 `requirements-audit.txt` 的 Python 3.12 环境，必须显式设置 `JAVA_HOME`；PATH 上有 Java 不满足真实密钥生成测试的输入契约。脚本单元测试使用自有临时协议 fixture 检查准入规则，不依赖宿主碰巧留存的报告，也不代表真实协议运行验收。实际 `audit-protocol-runtime.py` producer 仍严格读取当前 `libs/security-protocol/target/surefire-reports`、编译类及固定依赖；应用的 `clean/package -DskipTests` 会删除这些报告，运行该 producer 前必须重新实跑协议测试。

依赖包含固定版本的`tzdata`，供Windows上的`ZoneInfo('Asia/Shanghai')`使用。不要以本机Conda已有的时区数据代替独立Python环境的依赖准备；引擎整合脚本在创建容器前检查这一依赖。官方数据来源见[Python tzdata项目](https://github.com/python/tzdata/releases/tag/2026.5)。

引擎整合脚本默认从当前源码新鲜构建五个服务，提供新的 `--output output/audit-workflow-engine-chain/<fresh-run>/report.json` 即可。只有需要复用实际六应用 producer 留存的 JAR 输入时，才同时提供 `--artifacts-manifest` 和先前独立记下的 `--expected-manifest-sha256`；只提供其一会在建容器前拒绝。原 producer 报告、JAR 字节、完整 main 源码集合、当前类/资源及共享库均会核对；坏锚点或源码不匹配不能自动回退，不能重算 manifest 摘要使旧 JAR 通过。所有运行检查重新产生，旧运行结果不复制。公开 Flink 正例使用 bounded；streaming 自然 FINISHED 必须失败且下游 SQL 无 attempt、无业务行。该脚本仍使用受控 Spark/Flink HTTP 响应，不宣称物理引擎已执行。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
mvn.cmd -q -o -f libs/security-protocol/pom.xml test
if ($LASTEXITCODE -ne 0) { throw '当前协议测试失败，停止脚本回归' }
python -B -m unittest discover -s scripts/tests -p 'test_*.py' -v
if ($LASTEXITCODE -ne 0) { throw '脚本回归失败，保留原报告并停止' }
python -B -m unittest discover -s tests -p 'test_spark*.py' -v
if ($LASTEXITCODE -ne 0) { throw 'Spark脚本回归失败，保留原报告并停止' }
```

`MissingHostSurefireEvidence` 表示依赖准备未完成，不能复制归档XML到target或放宽校验。保留原失败、重新实跑并分开记录脚本测试和Spark跳过项；这些检查不替代真实运行producer或发布验收。

## v2 发布证据

先冻结源码、运行镜像和脱敏配置，再建立 context。源码或工作树在运行中变化会阻断；不要在多个代理仍修改源码时生成正式 context。制品参数使用实际 SHA-256 摘要，配置指纹来自去除秘密值后的部署契约，不能把密钥作为命令行参数或写入报告。

`release-evidence.py create-context` 必须提供 `--run-id`、一个或多个 `--artifact 名称=64位摘要`、`--config-fingerprint 64位摘要` 及 `--output`。先执行 `--help` 检查完整接口。

每个 producer 使用以下结构包装；报告必须在命令执行期间重新写出，并且报告内的执行时间也必须处于该窗口。命令失败、旧文件不变、复制旧报告、错提交/运行、摘要变化或缺少必需证据都会阻断。

```text
py -3 -B scripts/release-evidence.py run --context artifacts/release/RUN/context.json --manifest artifacts/release/RUN/evidence.json --name GATE --report 本次报告路径 -- 实际producer命令及参数
```

| GATE | producer | 常用报告路径，隔离运行时应显式确认 |
| --- | --- | --- |
| `r1-empty-rebuild` | `r1-rebuild.ps1` | 对应 FixtureOutput 下的 acceptance-manifest.json |
| `r2-cdc` | `r2-cdc-acceptance.ps1` | artifacts/r2-cdc/r2-cdc-acceptance-report.json |
| `r3-managed-lifecycle` | `r3-managed-stream-acceptance.ps1` | artifacts/r3-lifecycle/r3-managed-stream-report.json |
| `r4-event-time-quality` | `r4-flink-event-time-quality-acceptance.ps1` | artifacts/r4-event-time/r4-event-time-quality-report.json |
| `r4-r5-control-plane` | `r4-r5-control-plane-acceptance.ps1` | artifacts/r4-r5-control-plane/runtime-acceptance-report.json |
| `r5-maintenance-runner` | `r5-maintenance-runner-acceptance.sh` | artifacts/r5-maintenance-runner/r5-maintenance-runtime-report.json |
| `datasource-nine-local-runtime` | `datasource-runtime-acceptance.ps1` | artifacts/datasource-smoke/runtime-acceptance-report.json |
| `r3-ha-runtime` | `r3-ha-acceptance.ps1` | artifacts/r3-ha/r3-ha-lifecycle-report.json |

producer 原来的隔离环境参数、源重置保护和真实故障注入条件继续有效。包装器不会自动选择危险重置旗标，也不会把跳过故障注入当作 HA 成功。

```powershell
pwsh -NoProfile -File scripts/platform-release-acceptance-strict.ps1 -ContextPath artifacts/release/RUN/context.json -EvidenceManifestPath artifacts/release/RUN/evidence.json
pwsh -NoProfile -File scripts/platform-release-acceptance-strict.ps1 -ValidateExisting -ReportPath artifacts/platform-release/platform-release-acceptance-report.json
```

无 context 的旧无参发布命令现在非零退出。`-OfflineOnly` 可以产生诊断结果，但不具备 Docker 运行证据时不会通过发布。所有非通过状态都非零。context、manifest、checks 附件必须一起保留，strict 不再扫描目录寻找旧成功；旧格式报告只作历史资料。脚本行为测试并不宣称上述八类引擎本轮已真实通过。

## CI、签名清单与部署

Build Images 可以由 main/tag push 触发，但构建/签名前必须通过同一 commit 的最新完整 CI push run；CI 尚未出现、queued 或 in_progress 时有界等待。相同提交已有合法成功运行可以复用，新失败不能被旧成功掩盖。检查 run attempt、全部必需 job，并在发布清单形成与部署选择时重新核查。

每个矩阵镜像记录完整 digest；schemaVersion=2 清单绑定 repository、commit、CI run/attempt、build run/attempt/ref、11 个应用镜像与九服务全部迁移源 hash，随后使用既有 `build.yml` OIDC 身份签名。部署指定成功的 `build_run_id`，从该次构建的附件取回清单与 bundle，再验证 GitHub 状态及签名。`image_tag` 和 `cosign_identity` 只作一致性检查，不能自行选择另一套镜像。

远端需要 Docker Compose、Python 3、cosign、Git、base64、JDK21/Maven及准备好的离线依赖缓存。部署目录需是选定 release commit 的 checkout；`git pull --ff-only` 后如果提交不一致会阻断。部署使用实际服务名的 digest override，并强制加入 database-roles override；拉镜像前校验经操作人准备的私密配置/就绪收据、实际 Compose cluster/OID、runtime账号和secret文件，详见[数据库部署要求](../docs/database-roles-and-migration.md)。没有收据或迁移准备则阻断，不自动改现有数据库。

动态 SSH 值先在 runner 校验，任意目录文本以 base64 传输并在带引号的变量中使用；GitHub token 只用于 runner 的只读 API 查询，不传给主机、不写入清单。真实 SSH key 继续从 GitHub Environment Secret 交给固定 SHA 的 SSH Action。

`.aetheris-last-good-release.json` 原子保存前一次已通过健康检查的清单与签名 bundle 原始字节，只含公开发布元数据。回滚必须复验签名和同一数据库源/收据，继续使用独立 runtime 账号；旧 tag 或跨迁移源不自动回滚。无法取得可信兼容版本时保留失败并要求人工处置。镜像回滚不等于数据库迁移回滚，部署不执行全局 image prune。

`cosign_policy=disabled` 保留为明确的紧急签名旁路；CI、run/attempt、提交和镜像清单结构校验仍不能跳过。production Environment 的审批和分支保护需要在 GitHub 设置中实际配置，仓库里的 YAML 不能证明这些远程策略已经启用。

## 浏览器会话与退出后的批准任务验收

`audit-browser-session-runtime.py` 在本轮自有 PostgreSQL/Redis、Auth/System、两个 Gateway 和独立 Chromium 中执行。它重新构建前端与 Java 制品，缓冲真实 HTTP 响应以覆盖迟到 Set-Cookie、双标签页、服务重启、Redis/数据库故障及撤销重试；不读取用户浏览器 profile。需要 JDK 21、Maven、Node 24、Playwright 包和可启动的 Chromium，路径由调用者显式提供。不得使用付费 AI 凭据或现有业务数据库。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
python -B -X utf8 scripts/audit-browser-session-runtime.py `
  --output output/audit-browser-session-runtime/新的唯一报告名.json `
  --node C:/path/to/node.exe --playwright C:/path/to/node_modules/playwright `
  --browser C:/path/to/chrome.exe
python -B -X utf8 scripts/audit-workflow-sql-chain.py `
  --output output/audit-workflow-sql-chain/新的唯一报告名.json
```

后一个执行器默认启动 Auth/System/Metadata/Workflow/Gateway 五个实际服务，检查手动 SQL、真实 Quartz 和两节点 SQL pipeline 的业务行、writer、原主体/批准与结果凭证。执行人和批准人退出浏览器后，已批准任务仍须按原授权执行；另有撤权后零新增执行/写入断言。它不是 Spark/Flink 混合流水线、全产品或八类正式 producer 验收。

这些 identity 派生执行器只在唯一 run 目录内更新 `progress.json`，结束后创建不可覆盖的显式报告和 `report.json`。已有路径会拒绝；失败报告不得复用为新成功。读取报告时同时检查完整 case 集合、源码首尾、迁移集合、制品/配置摘要和自有资源清理。机器环境失败、夹具失败、真实业务失败分别保留，不能因后次成功删除前次记录。

## 内部协议密钥准备

`security/installation.py generate --directory configs/security/private/新目录`仅准备10个服务独立WORKLOAD与Gateway独立USER key，不覆盖、安装或轮换已有密钥。设置明确的JDK21 `JAVA_HOME`后执行；公钥注册表与各服务私钥须分别分发。Windows ACL审阅、应用接入、数据库防重放和协调切换仍是后续条件，详见[协议安装约束](../docs/remediation/protocol-installation.md)。数据源AES主密钥不属于此工具范围。

## PostgreSQL 备份与恢复

脚本使用容器内权限受限的临时目录，`pg_dump -Fc -f`、`pg_restore --list` 和 `docker cp` 保留字节；不会把二进制 archive 放入 PowerShell 文本管道。`docker exec` 不接受 `-T`，只有 `docker compose exec -T` 有该选项。

通过 `AETHERIS_POSTGRES_CONTAINER`、`POSTGRES_DB`、`POSTGRES_USER` 显式指向目标。先在一次性数据库验证中文、二进制、约束、序列和业务数据，再按已有数据库的备份及回滚流程执行。

```powershell
pwsh -NoProfile -File scripts/backup-postgres.ps1 -OutputDir ./backups -WhatIf
pwsh -NoProfile -File scripts/restore-postgres.ps1 -BackupFile ./backups/example.dump -Clean -WhatIf
```

`-OutputFile` 可指定备份文件，覆盖需要 `-Force`；restore 可提供 `-ExpectedSha256`，使用单事务及 `--exit-on-error`，`-Clean` 仍受 ShouldProcess 控制。归档检查、复制、原生命令或清理失败均不能报告完整成功。Mock docker 的行为测试证明脚本控制流，不替代真实 PostgreSQL 恢复演练。

## 依赖来源固定与当前镜像清单

关键 Actions 已根据 2026-10-18 对官方 GitHub 仓库的 `git ls-remote` 查询固定到实际 commit，记录见 [Action pin 来源](../.github/workflows/action-pins.json)。固定 Action 源码不自动等于所有下载工具和基础镜像都已经完成供应链核验。

[基础镜像清单](../.github/workflows/base-image-inventory.json)按当前已跟踪 Dockerfile 和 Compose 的默认值生成，包含文件与行号；开发本地镜像另列，不冒充可从公共仓库拉取的依赖。检查不启动 Docker、不拉取镜像。

```powershell
py -3 -B scripts/base-image-inventory.py --check
```

当前需解析并审核的外部默认引用为：

| 默认引用 | 当前状态 |
| --- | --- |
| `clickhouse/clickhouse-server:24.8.14.39-alpine` | 同版本 digest 待解析与核验 |
| `cockroachdb/cockroach:v24.3.6` | 同版本 digest 待解析与核验 |
| `docker.1panel.live/library/eclipse-temurin:21-jre` | 同版本 digest 待解析与核验 |
| `docker.1panel.live/library/maven:3.9.9-eclipse-temurin-21` | 同版本 digest 待解析与核验 |
| `docker.1panel.live/library/nginx:1.27-alpine` | 同版本 digest 待解析与核验 |
| `docker.1panel.live/library/node:22-alpine` | 同版本 digest 待解析与核验 |
| `docker.1panel.live/library/redis:7.4-alpine` | 同版本 digest 待解析与核验 |
| `flink:1.20.1-java17` | 同版本 digest 待解析与核验 |
| `mariadb:11.4.3` | 同版本 digest 待解析与核验 |
| `mysql:8.0.40` | 同版本 digest 待解析与核验 |
| `postgres:16-alpine` | 同版本 digest 待解析与核验 |
| `postgres:16.9` | 同版本 digest 待解析与核验 |
| `prom/prometheus:v2.54.1` | 同版本 digest 待解析与核验 |
| `quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z` | 同版本 digest 待解析与核验 |
| `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` | 同版本 digest 待解析与核验 |
| `tabulario/iceberg-rest:1.6.0` | 同版本 digest 待解析与核验 |
| `trinodb/trino:476` | 同版本 digest 待解析与核验 |
| `zookeeper:3.9` | 同版本 digest 待解析与核验 |


锁定步骤：

1. 在获准的网络中对清单每个引用执行 `docker buildx imagetools inspect IMAGE`，保存 registry manifest/index digest、实际镜像仓库、平台列表和查询时间。此步骤只读取 registry 元数据，不进行大构建。
2. 按当前实际目标 `linux/amd64` 检查对应 platform；需要 arm64 时单独验证，不能默认等价。对镜像站与发布者官方源逐项核对同一版本的来源和内容，不凭同名 tag 推断镜像相同。
3. 将确认的同版本引用改为 `image:version@sha256:...`；不顺便升级 Maven、Java、Node、Flink、Spark 或数据库主版本。无法确认的条目标记阻断，不能填猜测摘要。
4. 先检查 Compose 展开与 Dockerfile 参数，再做少量代表镜像构建和隔离 smoke；确认现有连接器、健康检查、非 root 用户与回滚兼容后逐项推广。

本轮建立了准确清单和执行顺序，尚未把这些基础/依赖镜像全部改成 digest；不应据此宣称基础镜像供应链整改已完成。

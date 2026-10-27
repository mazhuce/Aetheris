# Aetheris 全仓库审查报告

**审查日期：2026-10-08**  
**源码基线：`6adaa6e2961e6fdf9c84abc64e1ad2911ab77525`（2026-10-03）**  
**工作方式：审查，不修改业务代码、不迁移现有数据库、不启动或停止未知业务进程。**

## 1. 结论先行

**当前版本可以干净编译、通过现有测试，但不建议直接作为具有不可信用户、细粒度数据隔离要求的生产平台发布。**

主要阻断不是代码格式，而是：

1. **只读 SQL 防线确实能被绕过。** 对真实 `JdbcService.query()` 的隔离 H2 调用执行了删除，临时表由 2 行变成 0 行。
2. **身份与授权没有在所有入口闭合。** AI 工具调用丢失用户主体；SQL、表、字段、指标、看板及列表入口的权限检查不一致；撤销最后一个范围授权反而恢复全读。
3. **启动与交付链存在确定阻断。** serving 的 Flyway 版本重复；CI smoke 的数据源主密钥不符合代码要求；发布镜像与部署 Compose 的镜像名称不一致。
4. **执行结果不确定性处理不统一。** SQL 网络异常可触发重复写入；流水线等待超时被标记为确定失败；接入/质量崩溃后的活跃记录缺乏恢复机制。
5. **“报告通过”不等于当前版本运行通过。** 发布聚合会优先寻找历史 passed，忽略更新失败；备份脚本在 PowerShell 7 不可用；真实恢复和 HA 未在本次复测。

本报告归并为 **35 组问题、风险及兼容性缺口：P0 1 组、P1 21 组、P2 11 组、P3 2 组**。这不是“35 个均已利用的漏洞”；每项都标注证据等级和适用前提。依赖公告命中另列，不把一个公告在多个模块的重复命中重复算成业务漏洞。

项目并非没有工程基础：已有密码哈希、AES-GCM、部分签名身份验证、内部令牌、权限服务 fail-closed、数据库原子抢占、执行租约/fencing、不可变发布快照、Git 信任校验、容器加固及运行证据校验。主要问题是这些机制**局部有效，但跨入口、跨服务和故障恢复链路尚未一致落地**。

## 2. “完整”范围及证据边界

### 2.1 覆盖方法

| 层次 | 本次实际完成 | 不能据此宣称 |
| --- | --- | --- |
| 全仓库盘点 | 1,227 个受跟踪文件，逐文件 SHA-256、大小、分类；基线清单随报告附带 | 未跟踪文件、Git 历史、外部部署资产全部审过 |
| 文本扫描 | 1,217 个可 UTF-8 解码文件，包括含 NUL 的源文件；初始统计约 110,108 行 | 这些行均逐行人工签核；行数均为业务代码 |
| 接口静态核对 | 提取 351 个 HTTP 方法映射、280 处前端 API 调用；未发现方法/路径不匹配候选 | HTTP 状态、DTO 字段、权限、分页、语义和真实调用均正确 |
| 人工审查 | 全部服务、前端主要交互、核心跨服务调用链、迁移、执行状态机、CI/CD、运维和历史证据 | 每个分支、每个页面、每个数据库方言都运行验证过 |
| 自动化执行 | Maven 全模块 test 和隔离 clean verify；前端测试/类型/构建；两组 Python unittest；Compose 配置解析 | 单测通过即服务能启动、升级、联调和承受生产负载 |
| 隔离探针 | 真实项目类＋临时 H2/mock/自建回环 HTTP 服务；不访问现有业务库 | 已在 PostgreSQL/Hive/生产环境完成相同攻击 |
| 依赖审查 | 测试 classpath 的 421 个 Maven 坐标查询 OSV，另查询 1 个打包辅助依赖；核对生产 JAR；npm audit | 所有公告均可利用，或全部许可证/容器系统包都已审完 |

初始 1,213 个文本文件的分类后来补正为 1,217 个可解码文件；差异来自含 NUL 等内容，不能沿用初始“非文本”判断遗漏源文件。其余 10 个 PNG/PDF 已纳入文件清单，但**未逐图、逐页作视觉或 PDF 内容审查**。

自动扫描产生的 1,672 个风险位置是人工核对的候选，**不是 1,672 个问题**。未收集 JaCoCo/前端分支覆盖率，不给出虚构的覆盖率百分比。

### 2.2 证据等级

- **R：隔离实际复现。** 调用项目真实代码或脚本函数，输出随报告保存；说明是否使用 mock。
- **S：静态确认。** 源码/配置中的缺陷或缺口明确，但没有全运行栈复测。
- **V：公告版本命中。** 包版本处于公告范围；是否存在利用前提另行分析。
- **U：未运行验证。** 部署环境、外部服务、负载或完整恢复演练尚缺。

优先级不是 CVSS：**P0** 是立即隔离/阻断发布；**P1** 是正式发布前修复；**P2** 是功能契约、隔离策略或可靠性补齐；**P3** 是低风险工程与兼容性改进。需要内网直连、管理员权限、特定数据库能力等条件的项，不描述为无条件公网攻击。

### 2.3 未完成的运行验证

- 未用空白 PostgreSQL 完整启动全部服务、逐历史版本升级或演练迁移回滚。
- 未启动本次专用 Docker 栈，未执行完整浏览器/HTTP 端到端权限矩阵。此前 `docker compose ps --format json` 返回空。
- 未运行九类真实数据源全功能矩阵、当前源码的湖仓重建、CDC、Flink HA、savepoint 升级、Runner Linux 恢复或真实备份恢复。
- 未验证生产 TLS、Ingress/防火墙、云 IAM、外部 LLM 数据处理政策、生产密钥轮换及实际灾备 RPO/RTO。
- 未完整扫描 Git 历史、通用敏感值或所有未跟踪文件。有限格式扫描未命中 JWT/provider key/private key，**不代表完全没有密钥泄露**。
- 未执行容器系统包漏洞扫描、许可证合规审计、长期稳定性/并发/容量压测。

这些不是“已通过”，也不是自动推导的“已失败”。本次交付是**全仓库审查及证据报告，不是全功能运行验收证明**。

## 3. 实际执行结果

| 检查 | 结果 | 解释 |
| --- | --- | --- |
| 原仓库 Maven 全模块 `test` | 546 测试，93 套件；失败/错误/跳过均 0 | 测试通过 |
| 隔离基线 `mvn -f pom.xml -s scripts/maven-mirror-settings.xml clean verify` | 10 个服务模块及根聚合均 SUCCESS；再次 546 测试通过 | 从当前提交导出的源码干净构建成功，不依赖原 target |
| 原仓库 `clean verify` | Windows 无法删除 gateway JAR，文件被进程占用 | 环境限制；没有停止该进程，不算源码构建失败 |
| `apps/web`: `npm test` | 24 个文件、83 测试通过 | 不是浏览器端到端测试 |
| `apps/web`: `npm run type-check` / `npm run build` | 均成功 | 有大 chunk 警告，见 F34 |
| `py -3 -m unittest discover -s scripts/tests -p test_*.py` | 96 测试通过 | 含静态契约/历史报告测试，不全是真实运行测试 |
| `py -3 -m unittest discover -s tests -p test_*.py` | 25 测试：21 通过、4 跳过 | 跳过项依赖 Linux/WSL 恢复能力 |
| `docker compose config --quiet` | 成功 | 配置能解析，不证明容器健康 |
| R1/R2 运行证据内容校验 | 通过 | 校验仓库中既有报告和相关摘要，不是本次重建/CDC |
| 聚合发布报告内容校验 | 通过 | 校验既有 JSON 的内容约束，不证明新鲜度和本次部署 |
| R3 HA 内容校验 | `artifacts/r3-ha/r3-ha-lifecycle-report.json` 通过 | 不要与另一路径的 failed 报告混淆 |
| R3 HA 新鲜度审计 | exit 2；`manual_review_required` / `historical_pass_requires_rerun` | 本次最终校验时约 108.68 小时，超过默认 24 小时 |

后端隔离构建分布：gateway 29、auth 86、system 19、metadata 128、quality 19、ingestion 17、governance 21、workflow 122、AI 29、serving 76。**不能把重复执行的同一批测试加总成更高覆盖量。**

## 4. 问题索引

| ID | 优先级 | 证据 | 问题 |
| --- | --- | --- | --- |
| F01 | P0 | R/S | SQL 只读与写入守卫可被换行绕过 |
| F02 | P1 | S/U | 平台内部 PostgreSQL 以平台凭据注册成可查询数据源 |
| F03 | P1 | S/U | AI 数据工具链不携带用户、不做用户资源授权 |
| F04 | P1 | S/U | auth/AI/多数 system API 的身份头缺少服务端验签 |
| F05 | P1 | R | 撤销最后一个范围授权反而恢复全读 |
| F06 | P1 | S | 看板使用 dataset 权限类型，列表只查空 ID |
| F07 | P2 | S | serving 权限检查永久使用空上下文 |
| F08 | P1 | S/U | SQL、指标及表查询入口绕过更细资源/字段范围 |
| F09 | P1 | S/U | 禁用、降权、改密不统一使已有 JWT 失效 |
| F10 | P1 | R | serving 两个 V3 迁移阻断 Flyway |
| F11 | P1 | R/S | CI smoke 主密钥不是合法 Base64 32 字节密钥 |
| F12 | P1 | R/S | JDBC URL 自动生成路径与危险参数验证不闭合 |
| F13 | P1 | R/S | Flink health 接受调用者指定任意 HTTP(S) 地址 |
| F14 | P1 | R | 单 Schema 同步误标其他 Schema 为 stale |
| F15 | P1 | S/U | SQL 网络不确定结果当作失败重试，可能重复写 |
| F16 | P1 | S/U | 流水线等待远端超时被错误归类为确定失败 |
| F17 | P1 | S/U | ingestion/quality 的 running 记录缺少崩溃恢复 |
| F18 | P1 | S/U | Flink HTTP 请求无明确超时，poll deadline 不够 |
| F19 | P1 | R/S/U | PowerShell 7 备份/恢复命令不兼容且二进制链路不安全 |
| F20 | P1 | R/S | 发布验收选择旧 passed，未绑定版本与新鲜度 |
| F21 | P1 | S/U | GHCR 发布名称和 Compose 部署名称不一致 |
| F22 | P1 | S/U | 构建不等待 CI，镜像先推送再扫描，部署无验签门 |
| F23 | P2 | S | 业务日期缺少真正日期校验却直接替换 SQL |
| F24 | P2 | R | domain code 授权在列表与单条判定中不一致 |
| F25 | P2 | S | 多服务 GET 缺少资源范围过滤，管理平面全读 |
| F26 | P2 | S | 网关 may-mutate 与服务 may-manage 的委托契约矛盾 |
| F27 | P2 | S | 本地 Spark 制品直接 approved，可本人发布 |
| F28 | P2 | S/U | Runner 重启后不重建存活进程的超时监控 |
| F29 | P2 | S | `.env.example` 的 maintenance/Flink 配置未完整传入容器 |
| F30 | P2 | S/U | 内部表/语义查询方言与宣称多数据源范围不一致 |
| F31 | P2 | S | 前端 useRequest 有过期响应覆盖和 loading 竞争 |
| F32 | P2 | S | 前端仅 admin/viewer 门禁未反映委托 RBAC |
| F33 | P1 | V | 实际生产包仍包含大规模陈旧依赖及公告命中 |
| F34 | P3 | R/S | 前端公共包体积偏大，缺少体积门禁 |
| F35 | P3 | S | 报告生成脚本使用 Python 3.12 语法，CI 基线为 3.11 |

## 5. 身份、授权与 SQL 边界

### F01 — SQL 只读和写入守卫能被换行绕过

**P0｜R/S｜影响：破坏数据、绕过危险函数限制；具体副作用取决于数据库和连接账号。**

定位：

- `services/metadata/src/main/java/com/aetheris/metadata/service/JdbcService.java:103–106,545–573`
- `services/metadata/src/main/java/com/aetheris/metadata/service/SqlWriteGuard.java:92–96`

两处使用 `String.matches(".*\\b...\\b.*")`，既没有 DOTALL，也不是搜索式 `find()`。输入含换行时，整个字符串匹配不能跨行，禁用词就可能全部检测失败。只读路径允许 EXPLAIN/WITH 等开头，且 `query()` 没有设置数据库连接只读。

隔离实际输出：

```text
READ_GUARD REJECTED: SELECT pg_sleep(1)
READ_GUARD ACCEPTED: SELECT\npg_sleep(1)
READ_GUARD ACCEPTED: EXPLAIN ANALYZE\nDELETE FROM review_probe
WRITE_GUARD ACCEPTED: INSERT INTO t\nSELECT pg_sleep(1)
ACTUAL JdbcService.query ROWS_AFTER_DELETE=0
```

删除只发生在新建进程内的 H2 临时表。还确认 `SELECT 1 INTO OUTFILE '/tmp/review-not-executed'` 被守卫接受，但**没有执行文件写入**。不推导所有数据库上都能以同一 SQL 成功删除或写文件。

**建议：**立即限制自由 SQL/AI 数据工具暴露面；部署独立只读账号。短期修复换行检测并补充副作用语法禁用，长期采用方言感知 AST 白名单；拒绝 `EXPLAIN ANALYZE` 写入、写 CTE、SELECT INTO/OUTFILE、危险函数等。驱动支持时设只读事务/连接，但它只能是纵深防御。新增测试必须断言数据库数据未变化，而不只是断言字符串被拒绝。

### F02 — 默认内部数据源使用平台数据库账号

**P1｜S/U｜与 F01 组合可放大到平台控制面破坏。**

定位：`services/metadata/src/main/java/com/aetheris/metadata/config/ManagedDataSourceInitializer.java:21–28,51–57`；`services/metadata/src/main/java/com/aetheris/metadata/controller/DataSourceController.java:139–152`；`services/auth/src/main/java/com/aetheris/auth/service/PermissionDecisionService.java:92–96`；`docker-compose.yml:74–77,129–131`。

初始化默认开启，读取平台 `DB_USER/DB_PASSWORD` 注册“Aetheris PostgreSQL”。默认 Compose 的 `DB_USER`、`POSTGRES_USER` 都是 `aetheris`，不是专门的业务只读角色。viewer 的角色默认包含 datasource 查询能力。于是即便修好只读 SQL，普通 SELECT 也可能读取平台 schema 中的账号密码哈希、权限和配置；加密数据源密码不能解决平台库整体可读问题。

**前提：**内部数据源存在、用户被允许查询该数据源、数据库角色能读相应表。本次未在真实 PostgreSQL 导出账号或修改权限。

**建议：**分离迁移、服务运行、交互只读、任务写入账号；平台控制库不得进入普通用户数据源目录；受管数据源按白名单 schema/view 暴露，禁止查询 auth/配置等控制面 schema。README:43 的“agent 使用只读账号”应由启动约束或账号权限实测保障，不是只保留文字承诺。

### F03 — AI 工具链丢失用户权限上下文

**P1｜S/U｜影响：受限用户通过 ChatBI/dataMode 访问未获授权的数据。**

定位：`services/ai-agent/src/main/java/com/aetheris/aiagent/controller/ChatController.java:97,113–122`；`services/ai-agent/src/main/java/com/aetheris/aiagent/service/ChatService.java:127–151`；`services/ai-agent/src/main/java/com/aetheris/aiagent/agent/AgentToolClient.java:33–43,51–90,99–127`；metadata 的 `/internal/agent/**`、`/internal/semantic/**` 控制器。

用户名在控制器主要用于会话归属和限流，`chatBi()` 只接收 question；工具客户端只有共享内部令牌，不携带终端用户。表检索、结构读取、SQL 和指标查询均没有足够信息执行原用户资源授权。**内部令牌证明服务身份，不证明用户有权读这个表/指标。**

**建议：**定义签名的 on-behalf-of 用户执行上下文，端到端传递主体和请求 ID，metadata 在每次检索、describe、query、metric 处授权。提示词、返回行数限制和 `/internal` 路由隔离均不能替代数据权限。补“直接 API 被拒、同用户 AI 也必须被拒”的差分测试。

### F04 — 部分服务直接信任普通身份头

**P1｜S/U｜前提：攻击者能直接访问业务服务端口/内网，或内部某服务已被控制。**

定位：`services/auth/src/main/java/com/aetheris/auth/config/WebConfig.java:18–19`；`services/auth/src/main/java/com/aetheris/auth/controller/AuthUserAdminController.java:49–65`；`services/auth/src/main/java/com/aetheris/auth/service/AdminGuard.java:26–27`；`services/ai-agent/src/main/java/com/aetheris/aiagent/controller/ChatController.java:69–81`；`services/system/src/main/java/com/aetheris/system/config/WebConfig.java:19–21`。

auth 的拦截器仅注册到 `/internal/**`，公开管理 API 的 AdminGuard 使用请求头中的 username 决策，未先验证网关签名。AI 也按 `X-User-Name` 判断会话主体。system 只给审计/权限事件安装签名管理门，其他 API 仍存在头部信任边界不一致。

默认 Compose 不把这些服务端口直接发布到公网，因此不是“默认外网无需登录就能管理”。但不应把同一内部网络永远可信作为安全前提。

**建议：**所有非公开业务 API 统一验证签名身份/mTLS，再读取可信主体；内部 API 校验服务身份；禁止在验签前使用 username/role；建立逐服务的“无签名＋伪造管理员头”拒绝测试。

### F05 — 撤销授权反而扩大读权限

**P1｜R｜影响：撤销/停用最后一个范围授权可能解除用户隔离。**

定位：`services/auth/src/main/java/com/aetheris/auth/service/PermissionDecisionService.java:29–31,86–96,356–367`。

现有设计是“没有范围授权→角色全读；有范围授权→范围收窄”。真实决策类＋mock 授权存储复现：只有 table 10 授权时 table 20 被拒；移除这最后一条授权后 table 20 被允许。

```text
BEFORE_REVOKE_OTHER_TABLE_ALLOWED=false
AFTER_REVOKE_OTHER_TABLE_ALLOWED=true
```

这不是缓存问题，而是当前规则的必然结果。兼容历史全读可以理解，但与管理员对“撤销”的通常安全预期冲突。

**建议：**显式区分兼容全读模式与受限模式；撤销授权后不自动退出受限模式。用显式 ALL 授权表达全读，缺少授权默认拒绝；迁移前评估历史账号，不直接静默改变全部业务权限。增加权限单调性测试：撤销 allow 不得增加允许集合。

### F06 — 看板权限类型错误，列表不按记录过滤

**P1｜S｜影响：dataset/dashboard 权限混用、错误放行/误拒、列表范围不正确。**

定位：`services/serving/src/main/java/com/aetheris/serving/controller/AnalyticsController.java:59–63,103–113`。

看板列表、单条、published slug 使用 `"dataset"` 而非 `"dashboard"`，单条把 dashboard ID 当 dataset ID 判断。列表只做一次空 ID 检查，然后返回完整列表，不使用资源可见集合。存在范围授权时可能整页被拒；放行时则未逐记录过滤，不能声称它按看板/空间权限正确收敛。

**建议：**使用正确资源类型，解析 slug 对应的真实看板和 workspace；列表用 visibility/allowedIds 过滤，详情与 published 复用同一授权逻辑。测试同 ID 的数据集与看板、不同 workspace、可见一条不可见一条的列表。

### F07 — serving 永久传空权限上下文

**P2｜S｜影响：空间/域继承授权误拒或无法表达正确范围。**

定位：`services/serving/src/main/java/com/aetheris/serving/security/ServingSecurity.java:16–17`；`services/auth/src/main/java/com/aetheris/auth/service/PermissionDecisionService.java:303–350`。

`require()` 永远传 `Map.of()`，缺少 workspaceId/domainId/domainCode 等。决策引擎需要这些键匹配祖先授权。不能把这种缺失直接全部归类为越权：不少情形会 fail-closed 误拒；但它确实让 advertised 空间/域继承模型在 serving 端不能正确执行。

**建议：**从数据库资源对象取得上下文，不能由用户任意指定可信归属；引入类型化 AuthorizationTarget，明确哪些资源的哪些 ctx 必填，缺失时可观测地拒绝。

### F08 — 细粒度授权能从其他查询入口绕开

**P1｜S/U｜影响：表/字段/指标范围不是统一的数据访问边界。**

定位：`services/serving/src/main/java/com/aetheris/serving/controller/VersionedServingController.java:34–46`；`services/serving/src/main/java/com/aetheris/serving/service/MetadataQueryClient.java:25–50`；`services/metadata/src/main/java/com/aetheris/metadata/controller/DataSourceController.java:144,254–265`；`services/metadata/src/main/java/com/aetheris/metadata/controller/InternalTableQueryController.java:41–50`。

- 指标 code 查询只验 `metric,null,query`，不定位具体指标归属/ID。
- 表查询只验 table 权限，列名存在性检查不等于 field 权限检查；默认投影直接取最多 50 个字段。
- 自由 SQL 与 preview 只验 datasource；table/field 收窄不必然收窄该 datasource 的自由 SQL 能力。
- serving 调 metadata 的内部客户端未传用户，metadata 无法补上原用户的数据范围判断。

**建议：**所有数据入口统一到一个授权执行器；指标先解析真实资源，字段做 allowed projection/脱敏，preview 同样授权。自由 SQL 不能靠表 API 的字段门禁保护，应限制为独立的特权能力或使用有数据库级隔离的只读视图/账号。补直接查询、preview、serving、AI 四入口一致性测试。

### F09 — 账号生命周期没有统一撤销既有令牌

**P1｜S/U｜影响：禁用/降权/改密后部分路径仍可使用旧令牌。**

定位：`apps/gateway/src/main/java/com/aetheris/gateway/config/JwtAuthFilter.java:127–148,159–180`；`services/auth/src/main/java/com/aetheris/auth/controller/AuthProfileController.java:101–108`；各服务 GET 拦截逻辑。

网关检查 JWT 签名、有效期和 jti 撤销状态，通常不读取账号最新状态/令牌版本。已调用 auth 决策引擎的路径会拒绝 disabled 用户，不能说所有接口都绕过禁用；但 AI、一些 workflow/system GET 等未询问最新主体状态。改密码控制器也明确旧 JWT 在到期前有效。旧 role 仍来自 JWT，可能使基于 role 的剩余逻辑滞后。

**建议：**引入用户 tokenVersion/sessionVersion，禁用、密码修改、角色安全变更时递增；网关按有界缓存核对活跃状态与版本，敏感事件立即失效缓存。保留 Redis 不可用时拒绝的良好边界。测试已经签发的 token，而非仅测试下一次登录失败。

## 6. 启动、连接与数据正确性

### F10 — serving 的 Flyway 迁移版本重复

**P1｜R｜影响：迁移开启时 serving 启动被阻断，下游健康依赖也受影响。**

定位：`services/serving/src/main/resources/db/migration/V3__semantic_dataset.sql`；`services/serving/src/main/resources/db/migration/V3__service_api_keys.sql`。

真实 Flyway 对该目录 `info()` 返回 `Found more than one migration with version 3`。即使后端 546 个测试和 Maven verify 通过，重复迁移仍存在；这些检查没有覆盖真实服务启动迁移。

**建议：**先核对部署环境 schema history，再安排唯一版本和升级策略；不要对已应用迁移随意改名/改 checksum。增加空白 PostgreSQL 全服务启动，以及从实际旧版本 schema 升级测试。H2 `info()` 已足以确认重复版本，不等于整个 PostgreSQL 迁移 SQL 已验证。

### F11 — CI smoke 的数据源主密钥非法

**P1｜R/S｜影响：metadata 初始化失败，跨服务 smoke 无法正常开始。**

定位：`.github/workflows/ci.yml:75–88`；`services/metadata/src/main/java/com/aetheris/metadata/service/DataSourceSecretService.java:24–35`。

CI 设置 `smoke-datasource-master-key-that-is-long-enough`，而服务要求标准 Base64 解码后恰好 32 字节；字符串含 `-` 等非法字符。严格 Base64 解码已复现拒绝。这不是“长度看起来足够”即可使用的密钥。

**建议：**CI 运行时生成 `openssl rand -base64 32`，传入步骤环境，禁止记录真实生产密钥；启动前用与服务相同的校验器验证。修复后仍要处理 F10，不能把主密钥修正当作完整 smoke 已通过。

### F12 — JDBC URL 与危险参数校验存在旁路

**P1｜R/S｜前提：有创建/测试/修改数据源权限；潜在危害取决于加载的驱动。**

定位：`services/metadata/src/main/java/com/aetheris/metadata/service/JdbcService.java:59–68,400–449,576–625`。

显式 URL 与 connectionParams 有黑名单，但 host/databaseName 自动生成的最终 URL 未统一再校验。真实 `buildUrl()` 接受：

```text
jdbc:postgresql://127.0.0.1:5432/review?socketFactory=review.not.executed.Factory
H2 INIT URL ACCEPTED
```

前者把 databaseName 内的参数带入 URL；后者黑名单列了 INITSQL 却没有 H2 INIT。只复现“被接受”，**没有连接到恶意服务器、没有加载恶意类、没有执行 H2 初始化脚本**。

**建议：**按驱动建立参数允许列表，结构化构造/编码 host、database/service 名称；所有生成路径最终统一验证；禁止 H2 INIT、脚本和任意类工厂等高风险能力。对数据库连接实行出站网段政策，必要时隔离驱动进程。

### F13 — Flink health 是调用者可控出站请求入口

**P1｜R/S｜影响：已登录用户可能探测 workflow 可达的内网/回环 HTTP 服务。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/controller/ManagedSparkController.java:90–124`；`services/workflow/src/main/java/com/aetheris/workflow/service/FlinkSqlExecutionClient.java:267–284`。

`GET /api/workflow/spark/flink/health?endpoint=...` 只校验 HTTP(S) URL 结构，不限制 host/IP，未要求 mayManage。workflow 的签名身份拦截器对 GET 不问管理权限。服务端向目标发送 `/v1/info`，可从成功/HTTP 状态等观察可达性。

隔离探针直接调用真实控制器，目标是本探针自建的 `127.0.0.1` 随机端口 HTTP 服务，记录到 `/v1/info` 并返回 HTTP 200。**这不是登录后的网关端到端复现，也没有访问真实云 metadata 或内网资产；固定 `/v1/info` 也不等于能读取任意 URL 路径。**

**建议：**health 只允许已登记集群 ID，不接受任意 endpoint；限制管理权限与出站目的地。自建 Flink 常用私网地址，不能机械禁止全部私网，应使用显式 allowlist、解析后 IP 校验、重定向与 DNS 重绑定策略。

### F14 — 单 Schema 同步错误标记其他 Schema

**P1｜R｜影响：元数据误标失效，影响表查询、目录与下游治理。**

定位：`services/metadata/src/main/java/com/aetheris/metadata/service/MetadataSyncService.java:54–57,95,100–140`。

`existing` 按整个 datasourceName 读取；`seen` 只包含本次扫描 Schema；扫描成功后 `markStale(existing,seen)` 标记所有未见表。真实同步类＋mock discovery/storage 复现：仅同步 schema_a，schema_b 中未扫描的表被改为 stale。全库采集还限制最多 50 个 Schema，截断后同样不能代表其余 Schema 已不存在。

**建议：**stale 只应用于成功、完整扫描的 Schema 范围；部分失败按 Schema 保留；扫描截断/分页时禁止全库 stale。使用 datasourceId 做稳定关联，不把可改名的 datasourceName 当主身份。

## 7. 执行状态机、重试和恢复

### F15 — SQL 不确定结果被自动重试

**P1｜S/U｜影响：已提交的 INSERT INTO 可能重复写入。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/service/SqlExecutionClient.java:56–89`；`services/workflow/src/main/java/com/aetheris/workflow/service/TaskExecutionService.java:616–662`；`services/metadata/src/main/java/com/aetheris/metadata/service/JdbcService.java:119–144`。

metadata 执行/提交成功后，响应丢失、代理超时或客户端网络异常可能只被归类为 `SqlExecutionException`。workflow 记录 failed 后根据 maxRetries 再 claim/execute。任务允许 INSERT INTO，并非所有 SQL 都是按分区 overwrite 的幂等操作。数据库内的执行抢占只能防本地并发，不能证明远端副作用只发生一次。

**建议：**对提交后未知结果使用 unknown 并停止自动重提；传执行幂等键，在执行端持久化提交/完成状态。只有确认未提交、已回滚或任务声明且验证幂等时才自动重试。新增“远端写入成功但丢失响应”的故障注入测试。本次没有故意让现有库重复写入。

### F16 — 流水线等待超时被当作确定失败

**P1｜S/U｜影响：远端仍运行时允许失败续跑，结果核对链路也可能丢失。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/service/PipelineExecutionService.java:207–225,245–273,424–433,456–477`。

Spark/Flink `awaitTerminal()` 超时抛普通异常，统一 catch 调 `failRun()`，节点和流水线成为 failed。instance_id 在等待完成之后才写入 node_run；超时分支可能没保存关联实例。代码已有 unknown 禁止重跑的良好分支，却没有让等待窗口耗尽进入该分支。

**建议：**先持久化 instance_id/远端句柄，再等待；等待超时/中断进入 unknown 或等待核对，不确认取消不得标 cancelled，不确认失败不得标 failed。测试慢队列、网络断开、远端完成晚于等待窗口，断言不会重复提交。

### F17 — 接入与质量的 running 互斥会留下永久阻塞

**P1｜S/U｜影响：进程崩溃后同任务/规则可能无法再次运行。**

定位：`services/ingestion/src/main/java/com/aetheris/ingestion/service/IngestionExecutionService.java:35–65`；`services/ingestion/src/main/resources/db/migration/V2__execution.sql:25–26`；`services/quality/src/main/java/com/aetheris/quality/service/QualityExecutionService.java:41–80`；`services/quality/src/main/resources/db/migration/V2__execution.sql:23–24`。

running 记录先落库，再执行远端工作；异常 catch 只能处理当前进程还存活的普通异常。进程被杀/宿主故障后，部分唯一索引会一直认为该任务正在执行；未找到对应 lease/heartbeat/restart reconciler。

**建议：**引入租约、worker 身份、attempt 与恢复器。远端结果未知应先核对，不能简单把所有旧 running 改 failed 后立即重跑。补保存 running 后 kill、远端提交后 kill、恢复时重复触发的真实故障测试。

### F18 — Flink HTTP 没有明确的单请求超时

**P1｜S/U｜影响：执行、health、轮询和清理线程可长期阻塞。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/service/FlinkSqlExecutionClient.java:30–42,58–77,97–109,280–284,304–318`。

注入的 connectTimeout 没有用于请求工厂；`client()` 直接使用共享 builder，没有显式 connect/read 超时。poll deadline 仅在 HTTP 调用返回后能检查，不能限制一次阻塞请求；创建会话/提交阶段和 finally 的 close 也可能卡住。仓库 workflow 配置中未找到提供这些超时的自定义 RestClient 全局配置。

**建议：**单次 REST 请求设置 connect/read 超时，并确保创建、提交、状态、cancel、close 全覆盖；运行总期限与网络请求超时分开，长任务运行数小时不意味着一次状态 GET 应无期限。builder 使用 clone/mutate，避免共享可变配置。补目标接收连接却不返回数据的测试。

### F28 — Runner 重启后丢失存活进程超时监控

**P2｜S/U｜影响：子进程仍存活时，原任务时限可能不再执行。**

定位：`scripts/wsl-spark-job-runner.py:423,426–454,547–585,608–616`。

新提交会创建 `_monitor` 线程执行时限和终态落库。`_recover()` 仅处理不再存活的 PID；存活的、身份匹配的进程保持 active，却未重建监控。status 可以更新心跳，但不实施 `_monitor` 的运行时限。Linux/WSL 相关恢复测试在本次环境跳过，故此项不是当前平台实测。

**建议：**恢复时重建不依赖 Popen 子关系的 PID 监控，使用持久化 startedAt/deadline、进程 start time 与命令校验；确认超时终止进程组后再落终态。补“启动任务→重启 Runner→进程仍活着→超时必须终止”的 Linux 测试。

## 8. 备份、发布与供应链

### F19 — PowerShell 备份恢复不可作为可靠灾备链

**P1｜R/S/U｜影响：备份/恢复命令直接失败，或无法证明 dump 字节完整。**

定位：`scripts/backup-postgres.ps1:9–10`；`scripts/restore-postgres.ps1:10–14`。

PowerShell 7.6.6 实际拒绝 `Set-Content -Encoding Byte`；restore 的 `Get-Content -Encoding Byte` 同样不符合 PS7 API。另有二进制 pg_dump 与 PowerShell 管道的字节保真问题，不能凭“文件生成了”证明 dump 有效。backup 未检查 docker/pg_dump 的 LASTEXITCODE，却输出 Backup written。

**建议：**优先在容器内 pg_dump 到文件再 docker cp，或使用经过字节保真验证的进程流；备份和恢复都检查退出码、大小、SHA-256、`pg_restore --list`。在专用空库演练恢复并核对关键数据/权限/版本。AES 主密钥等非数据库资产必须独立灾备且演练，否则数据库恢复也不能解密凭据。本次未操作现有数据库的恢复。

### F20 — 发布验收用旧 passed 覆盖更新失败

**P1｜R/S｜影响：当前失败版本可被历史成功报告伪装成通过。**

定位：`scripts/platform-release-acceptance.ps1:57–74,98–109,121–140`；`scripts/platform-release-acceptance-strict.ps1:19–27`；`scripts/audit-r3-ha-evidence.py:45–54`。

按文件时间从新到旧扫描时只返回 `status=passed` 的文件，跳过失败/invalid；原始选择函数在隔离 fixture 中实际选中了 old-passed.json，虽然 new-failed.json 更新且 fallback 也是它。

```text
RELEASE_SELECTED_PATH=old-passed.json
RELEASE_SELECTED_STATUS=passed
```

聚合主要读取 JSON 状态，不绑定 commit/image digest 和最长年龄。strict 内容校验仍使用固定的 R1/R2 路径，不一定是聚合刚选中的那份；R3 新鲜度审计虽已存在，但未成为这里的强制门。探测 Docker version 只能证明守护进程可用，不能证明当前代码跑过。

**建议：**按当前 commit/digest/run ID 选唯一报告，最新 failed/invalid 不可被旧 passed 遮蔽；严格校验选中路径的内容、摘要、版本和年龄。不可变存档报告，明确“historical passed”和“current passed”；纳入 HA 新鲜度。添加两份冲突报告回归测试，而非只断言脚本含某个函数名。

### F21 — 发布镜像与实际部署镜像不一致

**P1｜S/U｜影响：CD 可能拉不到镜像或继续部署本地旧标签。**

定位：`.github/workflows/build.yml:13–15,75–83`；`.github/workflows/deploy.yml:32–37`；`docker-compose.yml:92,119,142,250,368,425`；`.env.example:24`。

构建发布 `ghcr.io/mazhuce/aetheris-<name>`；Compose 使用 `aetheris-local/<name>:<tag>`；部署直接 git pull/compose pull/up，没有可见的生产镜像覆盖配置。build 顶部“命名保持一致”的注释不符合实际文件。

**建议：**增加明确的生产 Compose/镜像前缀参数，所有服务使用与发布一致的 digest；部署按一个固定提交和镜像清单执行，禁止不加约束地 main＋latest 混用。验证 pull 的实际 digest、服务健康、数据库升级和回滚能力。本次未执行真实 SSH/CD。

### F22 — CI/扫描/签名没有成为部署的前置条件

**P1｜S/U｜影响：失败构建或未扫描/未签镜像可先进入可拉取标签。**

定位：`.github/workflows/build.yml:8–11,85–111`；`.github/workflows/deploy.yml:18–40`。

Build Images 自己由 push 触发，没有等待 CI 成功；先 push（含 main 的 latest），再扫描 published image，再签名。扫描失败并不能撤回已经更新的标签。Deploy 只 pull/up，没有看到 digest 绑定、cosign verify、门禁状态核对或失败回滚。

**建议：**用 CI 成功的 workflow_run/同工作流 needs 驱动候选构建；候选 digest 扫描、签名、验收成功后才提升发布标签。部署前验证签名身份、digest、测试与验收产物；加入健康门和明确回退策略。保留当前 SBOM/provenance/cosign 投入，但让它们真正约束交付。

### F33 — 生产包仍存在明显依赖安全债

**P1（整改优先级）｜V｜不是已验证的远程利用数量。**

定位：各服务 `pom.xml`，尤其 `services/metadata/pom.xml:20–30,102–147`；`apps/gateway/pom.xml`；`apps/web/package-lock.json`。完整坐标及所属模块见附带 `dependencies.csv`。

从实际 Surefire classpath 查询 **421 个 Maven 坐标/版本**：全部查询完成，**78 个坐标命中 245 个唯一公告 ID**。clean verify 后读取实际 Spring Boot `BOOT-INF/lib`，另查询打包注入的 `spring-boot-jarmode-tools:3.4.7`，核对全部嵌套 JAR：生产包中共有 **384 个去重坐标/版本，76 个命中 242 个唯一公告 ID**。这证明命中不全是测试依赖；也仍不证明对应功能可触达。

| 模块 | 实际嵌套 JAR 数 | 公告命中坐标数 |
| --- | ---: | ---: |
| gateway | 91 | 24 |
| auth | 70 | 13 |
| system | 79 | 14 |
| metadata | 328 | 62 |
| quality | 82 | 15 |
| ingestion | 82 | 15 |
| governance | 79 | 14 |
| workflow | 90 | 18 |
| AI | 96 | 17 |
| serving | 72 | 14 |

坐标数按 group/artifact/version 去重，多个平台 classifier 不重复计数。metadata 还把 `hive-llap-common-3.1.3-tests.jar`、`hbase-common-2.0.0-alpha4-tests.jar` 带进生产包，应清理不必要传递依赖。Hive JDBC 3.1.3 本身在本次查询未命中，不等于其 Hadoop/HBase/Commons Collections 传递链无风险。

优先核对的例子：

| 组件/实际版本 | 公告与前提 | 当前判断 |
| --- | --- | --- |
| Spring Cloud Gateway 4.2.1 | [CVE-2025-41235](https://spring.io/security/cve-2025-41235)，转发不可信代理头，4.2.3 修复 | 版本命中；需审入口代理头信任边界，不能只靠 JWT |
| Spring Cloud Gateway 4.2.1 | [CVE-2025-41253](https://spring.io/security/cve-2025-41253)，需路由 SpEL 或可被攻击者操作的不安全 gateway actuator；4.2.6 修复 | 本配置未证明满足利用前提，不列为已验证环境变量泄露 |
| Tomcat 10.1.42 | CVE-2025-53506 等，部分公告要求 HTTP/2 或特定资源配置 | 匹配版本；不能把公告中的 HTTP/2 条件省略成默认公网 DoS |
| PostgreSQL JDBC 42.7.7（metadata 以外多个服务） | [CVE-2026-42198](https://github.com/advisories/GHSA-98qh-xjc8-98pq)，恶意 SCRAM 服务造成 CPU 耗尽；42.7.11 修复 | 多数服务连受管平台 PG，攻击前提不同于自由数据源；metadata 已 42.7.13 |
| PostgreSQL JDBC 42.7.7 | [CVE-2026-54291](https://github.com/advisories/GHSA-j92g-9f8w-j867)，`channelBinding=require`＋特定证书/MITM；42.7.12 修复 | 未证明项目使用该配置，不能宣称默认已遭认证降级 |
| Commons Collections 3.2.1 / Hadoop 2.6.0 等 metadata 传递依赖 | 反序列化、服务端组件等不同类型公告 | 包存在；逐项分析反序列化入口/实际加载能力，不能因 JAR 存在就称 RCE 已复现 |

npm audit 返回 **5 个受影响依赖条目：2 critical、2 high、1 moderate**：

- `axios 1.19.0` 为生产直接依赖；部分命中仅适用于 Node/HTTP2/proxy/data URI，浏览器 bundle 不能直接套用所有 Node 场景。
- `vitest 3.2.7`、`@vitest/mocker 3.2.7`、`tinypool 1.1.1`、`source-map-js 1.2.1` 在 lockfile 标为 dev，影响测试/构建环境，不描述成静态前端线上全都可远程利用。
- audit 的自动修复建议可能涉及不合适的降级/主版本变更，不能直接盲目执行 `npm audit fix --force`。

**建议：**统一兼容的 Boot/Cloud BOM 与驱动基线；按“包存在→代码可触达→配置前提→输入是否可信”分级处置。隔离/精简 Hive 旧依赖链，生成实际镜像 SBOM；持续 OSV/Trivy/npm 扫描，豁免项必须有理由、负责人和到期日。这里的历史修复版本只是对应公告的最低修复点，不代表升到该版本便清除截至 2026-10-08 的所有公告。

## 9. 资源契约、前端与配置

### F23 — 业务日期没有真正校验却直接拼入 SQL

**P2｜S｜影响：错误业务日期、SQL 结构变化及执行/归档契约失效。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java:317–325`；`services/workflow/src/main/java/com/aetheris/workflow/dto/TaskRunRequest.java:10–12`；`services/workflow/src/main/java/com/aetheris/workflow/service/TaskExecutionService.java:629–631,668–675`；`services/workflow/src/main/resources/db/migration/V2__task_tables.sql:45`。

DTO 有 @Pattern，但 run 的 @RequestBody 缺 @Valid；execute/claim 路径没有统一 LocalDate 验证。随后直接字符串替换 `${bizDate}`，注释却称它已经服务端校验。`VARCHAR(10)` 只是长度限制，不能验证日期或阻止 10 字符内的 SQL 元字符；不是无限长度的无权限 SQL 注入。

**建议：**边界与服务层都用严格 LocalDate.parse，并将日期格式化后使用；启用 @Valid 仅能补格式，仍应拒绝 2026-02-31 等不存在日期。能够参数绑定的方言使用参数；管理权限场景也不应依赖注释承诺输入安全。

### F24 — domain code 授权在列表中被吞掉

**P2｜R｜影响：可读单条但列表消失，权限页面与实际行为不一致。**

定位：`services/auth/src/main/java/com/aetheris/auth/service/PermissionDecisionService.java:154–168,347–350,417–422`。

`addLong()` 忽略非数字范围值，注释声称会走 OPAQUE，但调用处没有切换分支。真实决策类＋mock 存储复现 `values=["ECOM"]`：visibility 为 `IDS []`，带 domainCode 的单条 check=true。

**建议：**非数字根资源 code 必须解析为 ID 或返回 OPAQUE，列表逐条批量判定；同时测试数字 ID、code、混合集合和非法 code，确保列表/详情允许集合一致。

### F25 — 多服务管理平面 GET 没有资源范围过滤

**P2｜S｜属于企业隔离能力缺口，不把默认全读设计本身伪装成缓存漏洞。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/controller/TaskController.java:343–368`；`services/workflow/src/main/java/com/aetheris/workflow/security/GatewayIdentityInterceptor.java:56–70`；`services/governance/src/main/java/com/aetheris/governance/controller/GovernancePolicyController.java:28–34`；ingestion/quality 同类列表控制器。

多数 GET 只验证网关签名，返回全部任务、实例、日志、策略/规则等，没有主体、workspace/domain 或对象授权过滤。auth 的 SERVICE_RESOURCES 对 quality/ingestion/governance 等也为空。结果是这些控制面资源尚未实现与 metadata/serving 类似的细粒度隔离。

**建议：**明确哪些内容允许全员可读，哪些可能含 SQL、业务表名和运行细节；建立资源归属和动作权限。若当前只支持单团队可信环境，在产品契约中准确声明，而不要宣称全平台多空间隔离已完成。

### F26 — 委托权限被服务端全局管理门否决

**P2｜S｜影响：网关允许的细粒度写授权到服务仍然 403。**

定位：`apps/gateway/src/main/java/com/aetheris/gateway/config/JwtAuthFilter.java:191–198`；`services/auth/src/main/java/com/aetheris/auth/service/PermissionDecisionService.java:171–208`；`services/workflow/src/main/java/com/aetheris/workflow/security/GatewayIdentityInterceptor.java:67–77`；quality/ingestion/governance 的同名拦截器；`services/serving/src/main/java/com/aetheris/serving/security/ServingSecurity.java:15`。

网关 may-mutate 允许某服务相关资源的委托写权限，部分服务却对所有写方法先要求全局 may-manage。前端和后端资源授权界面因此可以配置“看似可委托、实际不可用”的能力。

**建议：**网关只负责认证和粗入口，业务服务按对象/动作检查；全局 manage 只用于系统配置/审批特权等。需要管理员专用的功能明确标注，不以两套不一致门禁实现。用非 admin 但获单资源 update/execute 的用户做端到端测试。

### F27 — 本地上传 Spark 制品绕过双人审批

**P2｜S｜前提：调用者已具相应管理权限；不是非管理员任意代码上传。**

定位：`services/workflow/src/main/java/com/aetheris/workflow/service/ManagedSparkCatalogService.java:106–138,271–295`。

本地上传直接写 approved、approved_by=上传人、source_signature_status=unsigned。发布限制“创建人不能自己发布”仅对有 Git 来源的作业成立，无 Git 来源时 SQL WHERE 明确允许本人发布。Git 路径上的签名/双人审批不能约束本地上传路径。

**建议：**若双人审批是全局治理要求，所有来源均先 draft、由另一主体审批/发布；若本地上传仅为开发便利，用显式开发/紧急变更策略，默认生产关闭并审计。保留 digest 验证，但不要把完整性校验等同来源信任/审批。

### F29 — `.env.example` 的部分配置未传入 Compose 服务

**P2｜S｜影响：设置宿主 .env 后容器仍采用错误默认值/功能仍关闭。**

定位：`.env.example:35–42`；`docker-compose.yml:282–294`；`services/workflow/src/main/resources/application.yml:77–88`。

示例已列 maintenance enabled/artifact key/digest 和 Flink SQL Gateway base URL，但 workflow environment 未完整传这些值；`.env` 是 Compose 的插值输入，不会自动把所有变量注入容器。Flink gateway 未覆盖时在容器中回落到 127.0.0.1:18083，指向自身而非宿主。不是说 JobManager base URL 也完全漏了：它已经传入。

**建议：**同步配置声明、示例与 Compose environment，生成有效配置快照（屏蔽秘密）。新增契约测试，枚举每个支持的外部变量及容器最终值；快速开始必须列全必需密钥及合法生成方法。

### F30 — 多数据源支持没有延伸到全部查询 API

**P2｜S/U｜影响：连接测试通过不代表表/指标查询可用。**

定位：`services/metadata/src/main/java/com/aetheris/metadata/controller/InternalTableQueryController.java:47–50,66–70`；`services/metadata/src/main/java/com/aetheris/metadata/service/SemanticMetricQueryService.java:157–184,281`；对照 `JdbcService.java:372–382` 的 preview 方言分支。

内部 table query 普遍 LIMIT，除 PostgreSQL 系列外使用反引号；SQL Server/Oracle 需要不同 quoting/分页。语义指标编译也普遍 LIMIT。preview 已区分 TOP/FETCH FIRST，说明“支持数据源连接”与“所有消费入口支持其 SQL 方言”不是同一能力。

**建议：**建立统一 dialect 接口或明确能力矩阵；表/指标编译复用它，对不支持的能力返回明确错误。至少按每种声称支持的引擎覆盖连接、发现、preview、表查询、指标、写任务、日期粒度测试。本次未实际连接 SQL Server/Oracle 验证这些入口。

### F31 — useRequest 允许旧响应覆盖新状态

**P2｜S｜影响：筛选/刷新/连续操作中显示过期结果或错误 loading。**

定位：`apps/web/src/hooks/useRequest.ts:10–24`。

并发 run 没有请求序号/取消；较旧请求最后完成可覆盖最新 data/error，任意一请求的 finally 都设 loading=false。卸载后也没有中止请求。类型检查与现有测试通过不会证明竞态不存在。

**建议：**引入序号/AbortController，只有最新请求能更新状态；或统一使用项目已引入的 React Query，明确 query key、取消和 stale 策略。用可手控的 Promise 测“新请求先完成、旧请求后完成”。

### F32 — UI 只有二级角色门禁，不反映委托权限

**P2｜S｜影响：合法委托用户看不到入口、操作能力与后端授权脱节。**

定位：`apps/web/src/api/auth.ts:11`；`apps/web/src/utils/access.ts:5`；`apps/web/src/constants/navigation.tsx:193–224`；`apps/web/src/router/index.tsx:145,184–242`。

用户类型、导航、RequireRole 主要按 admin/viewer 判断。后端已经有组织/角色/资源授权模型，viewer 获合法的管理或资源授权仍可能被 UI 隐藏。这不表示改 localStorage 的 role 就能攻破后端，UI 门禁从来不应成为安全边界。

**建议：**从可信 capabilities API 加载动作能力和可见模块；仍由服务器做最终授权。处理权限版本变化、403 刷新和可解释的禁用原因；用真实委托角色而不是只有 admin/viewer 两类做交互测试。

### F34 — 前端构建包体积偏大

**P3｜R/S｜影响：低带宽/低性能设备加载及缓存更新成本。**

定位：`apps/web/vite.config.ts`；主要 UI/chart 引入；本次 `npm run build` 输出。

构建成功但有 chunk 警告：antd 约 1,344.49 KB、charts 约 555.87 KB（构建显示的未压缩大小）。现有 manual chunks/页面懒加载不等于总体性能已验证。

**建议：**分析实际首次进入路径的下载量和依赖图，按可见图表组件加载、移除多余 locale/icon；增加 gzip/brotli 体积预算和真实设备 Web Vitals。不要只为了消警告提高 chunkSizeWarningLimit。

### F35 — 报告生成脚本的 Python 基线不统一

**P3｜S｜影响仅限辅助报告生成，不是后端主服务失败。**

定位：`artifacts/report_build/build_report.py:289`；`.github/workflows/ci.yml:28`；`.github/workflows/lakehouse-r1.yml:22`。

f-string 外层和表达式字符串复用双引号，属于 Python 3.12 PEP 701 放开的语法，Python 3.11 不支持。CI 明确使用 3.11，但该 artifacts 脚本未进入现有测试覆盖。本机是 Python 3.12.7，语法解析成功；**没有把 3.12 的解析结果冒充成 3.11 实测**。依据：[Python 3.12 官方说明](https://docs.python.org/3.12/whatsnew/3.12.html#pep-701-syntactic-formalization-of-f-strings)。

**建议：**改成兼容写法或声明此工具要求 3.12+；为仓库内所有可执行 Python 入口增加明确最低版本及编译检查，不只测试 scripts/tests。

## 10. 全模块覆盖账本

下表的“人工核对主题”表示本次沿这些路径做了源码核对，**不是该行每个文件逐行签核**。完整文件列表及摘要见 `2026-10-08-evidence/files.csv`。

| 区域 | 基线文件数 | 人工核对主题 / 主要结论 |
| --- | ---: | --- |
| `apps/gateway` | 20 | JWT、撤销、签名身份、粗粒度权限、限流、审计、路由、actuator 配置；F09/F26/F33 |
| `apps/web` | 170 | API 层、登录/令牌、导航/路由、共享请求、数据源/语义层/任务/看板/权限/验收页面、构建/Nginx；F31/F32/F34 |
| `services/auth` | 95 | 登录、密码、用户管理、RBAC/组织/组/资源授权、数据范围、内部门、审计；F04/F05/F09/F24 |
| `services/system` | 59 | 系统配置、通知/审计/权限事件、身份门、内部通知、持久化与输入；F04/F25 |
| `services/metadata` | 200 | 凭据加密、JDBC 全连接/查询路径、字段/资源授权、同步、语义模型/发布/指标、血缘/湖仓/CDC/API资产/维护；F01/F02/F08/F12/F14/F30/F33 |
| `services/serving` | 69 | 数据集/看板/缓存、版本查询、API资产、API key/申请/轮换/授权/配额/SLA、用户内部调用、迁移；F06/F07/F08/F10 |
| `services/ai-agent` | 37 | 会话所有权、输入/限流、模型调用、工具循环、提示词、元数据与指标执行；F03/F04/F09 |
| `services/workflow` | 121 | SQL/Flink/Spark/质量执行、抢占/attempt/lease/fencing、取消/unknown/reconcile、流水线/重跑、Quartz/调度恢复、Git信任/制品发布、Runner容量；F13/F15/F16/F18/F23/F26/F27 |
| `services/ingestion` | 31 | 任务/同步配置、远端 transfer、写事务和运行互斥、通知；F17/F25 |
| `services/quality` | 35 | 规则表达式 AST、标识符/SQL路径、执行阈值/调度、运行互斥、通知；F17/F25 |
| `services/governance` | 25 | 策略/问题 CRUD、验证和服务端权限边界；F25/F26 |
| `scripts` | 78 | smoke/acceptance/证据选择/严格门、R1–R5/九源验证、备份恢复、WSL Runner、单测和平台差异；F19/F20/F28 |
| `infrastructure` | 13 | 湖仓 BOM/compose、Flink HA/connector/checkpoint、版本契约；静态和内容校验，未运行 HA |
| `configs` | 10 | 表/连接器契约、Prometheus/告警和客户端配置；未实测监控采集/告警投递 |
| `docs` | 29 | 架构、权限、数据源能力、R1–R5/发布验收、运维、路线图与代码一致性；F02/F20/F30 |
| `artifacts` | 194 | 历史 JSON/日志与验证器关系、摘要校验、失败/历史通过区分、报告辅助脚本；F20/F35；PDF 未逐页 |
| `.github` | 6 | CI、镜像构建/扫描/签名、SSH部署、R1/HA门及工作流触发；F11/F21/F22 |
| `spark` | 5 | Runner 使用的业务/maintenance 脚本、输入/写入/退出和运行证据边界；未启动 Spark 引擎 |
| `tests` | 3 | Python Runner/平台行为测试，实际执行并记录 4 项跳过 |
| 根文件及其他目录 | 27 | 根 POM、Compose/dev、README/CHANGELOG、env、git/docker ignore、Makefile、database/fixtures/packages/examples/tools 等全量盘点与静态扫描 |

合计 1,227 个文件。package-lock、文档、历史报告也属于统计范围，不能把约 11 万行全描述为业务源码。根 POM 是聚合工程，服务版本/父依赖分散；依赖更新应统一策略，避免只修 metadata 而其他服务保留旧驱动。

## 11. 暂不判为缺陷的项与加固建议

### 11.1 防止误报

- **没有确认 Spark 排队期间定义可被编辑。** `ManagedSparkCatalogService` 没有定义编辑接口，已发布定义基本不可变；不把此猜测写成问题。
- **没有确认 Prometheus 被 gateway JWT 拦住。** JWT 是路由 GlobalFilter，gateway 自己的 actuator 未必经过它；需真实采集验证。
- **没有确认密钥已进入最终镜像。** `.dockerignore` 未充分排除 `.env`/backups/artifacts，会扩大 build context 风险，但 Dockerfile 的 COPY 限定源码，不能直接断言最终镜像包含这些秘密。
- **没有把源码里的测试密钥和 smoke 演示值当作真实泄露。** 有限密钥扫描不代替历史和部署审计。
- **没有把每个 catch 或每个字符串 SQL 都当作注入。** 使用标识符白名单、参数化 JDBC、质量 AST、事务写入的路径有真实防御；F01/F12/F23 分别指出具体旁路。
- **没有把历史 R1–R5 passed 当成本次运行成功。** 正确 R3 HA 内容校验通过与新鲜度需要重跑可以同时成立。

### 11.2 纵深加固（未发现利用证据）

1. JWT 在 localStorage（`apps/web/src/api/http.ts:13`）增加 XSS 后令牌窃取影响；评估 HttpOnly Secure cookie/BFF、短 access token＋可撤销 session，明确 CSRF 策略。当前 Nginx CSP 是有价值的防护，未确认 XSS。
2. 共享 internal token 同时承担多服务能力，某服务被攻破会有横向影响；考虑独立服务凭据、mTLS、最小内部 API 能力与签名执行上下文。
3. 所有数据库连接/Runner/LLM 出站目的地应有独立策略；管理员可配置不等于任意网络目标都安全。
4. 请求体/返回总字节限制、日志/执行记录保留、数据库连接预算、长任务异步化、缓存与配额在多实例下的一致性需压测和容量验证。100 行限制不等于大字段响应有字节上限。
5. CI Actions 尽量锁定可信 commit SHA；构建上下文排除本地秘密与备份；实际镜像与 SBOM 扫描进入发布门。
6. 建立可观测的 auth拒绝/unknown/lease过期/恢复阻塞指标，验证 Prometheus 和告警真实投递，不能以存在 YAML 证明监控闭环。
7. LLM 工具返回的数据可能进入外部供应商；明确敏感数据过滤、供应商保留政策和审计，不以提示词保证合规。

## 12. 修复顺序与退出标准

### 阶段 A：立即止血与恢复可启动交付

- **F01/F02/F03/F08：**暂停不可信用户的自由 SQL/AI 数据执行；平台库剥离为不可普通查询资产；只读账号与统一授权执行器。
- **F10/F11：**解决迁移版本/CI密钥，然后在专用空白 PostgreSQL 中启动全栈，不能仅再跑单测。
- **F04/F13：**统一服务身份验签；health 只接受可信集群；检查业务服务端口和出站策略。
- **F19/F20/F21/F22：**修复真正可恢复的备份、版本绑定验收与固定 digest 交付，阻止历史证据替代发布门。

退出标准：空白库启动、从实际旧版本升级均成功；viewer 的每个查询入口不能改变数据，不能读平台控制 schema；篡改身份头全部拒绝；当前失败报告必须阻断发布；专用恢复演练有完整数据/权限/凭据解密核对。

### 阶段 B：授权语义和执行结果一致性

- **F05/F06/F07/F09/F24/F25/F26/F32：**建立统一权限契约，消除撤销扩大权限、列表/详情和前后端能力偏差。
- **F15/F16/F17/F18/F28：**推广 unknown/幂等执行键/lease/reconcile；单请求超时与整体任务时限分离；Linux Runner 故障注入。
- **F12/F14/F23/F29/F30：**统一连接验证、扫描范围、日期、配置传递与方言能力。
- **F33：**按实际可触达性修复高风险组件，精简旧 Hive 依赖，持续扫描。

退出标准：权限矩阵和撤销单调性测试通过；丢响应/超时/kill/restart 不重复提交且可核对；超过 50 个 Schema 不误标；设置示例配置时容器收到正确值；各声称支持引擎的对应能力有实际结果。

### 阶段 C：工程与体验收尾

- **F27：**统一 Git/本地上传的审批策略。
- **F31/F34/F35：**修复前端竞态、性能预算及工具语言基线。
- 做当前 commit/digest 的 HTTP/浏览器全链路、真实九源矩阵、湖仓重建/CDC/Flink HA/维护回归、监控报警、容量与恢复演练。

**不建议边扩新功能边把上述 P0/P1 当作仅文档待办。** 优先把已有边界补闭合，而不是再复制一套各服务不同的安全实现。

### 必须新增的回归测试清单

| 类别 | 最少覆盖 |
| --- | --- |
| SQL | LF/CRLF、多行注释/字面量、EXPLAIN ANALYZE写入、写CTE、SELECT INTO/OUTFILE、危险函数；真实库只读账号断言无副作用 |
| 身份 | 所有服务无签名/伪造username或role/过期签名/错误签名；撤销jti、禁用、改密、降权后的旧JWT |
| 权限 | admin、普通viewer、受限用户、组/角色委托、撤销最后授权、domain code、workspace继承；列表/详情/AI/SQL/preview/指标/字段投影等价 |
| 数据连接 | 显式URL、自动URL、databaseName参数注入、H2 INIT、驱动工厂类、目标网段/DNS/重定向；有界连接和读取 |
| 元数据同步 | 单Schema、多Schema、部分失败、截断/分页、数据源改名、手工表保护 |
| 执行恢复 | 远端提交后丢响应、任务排队超过等待窗口、cancel未确认、服务kill、lease过期、多worker抢占、Runner重启后超时 |
| 迁移 | 全模块重复版本检查、空白PG启动、旧版本真实升级、升级失败的恢复策略 |
| 发布与灾备 | 最新failed压过旧passed、报告commit/digest/年龄一致、镜像签名/扫描强制、健康失败回退、dump字节保真、完整恢复演练 |
| 前端 | 慢旧请求后到、卸载取消、委托用户导航/动作、权限变更、各API错误和超时表现 |

## 13. 附件及复核说明

附件目录：[`2026-10-08-evidence`](2026-10-08-evidence/README.md)。

- `files.csv`：1,227 个基线文件、大小、SHA-256、文本扫描标记。标记不是逐行人工验收。
- `dependencies.csv`：421 个 classpath 坐标＋1 个打包辅助坐标、版本、测试模块、实际打包模块、公告 ID。
- `metrics.json`：测试套件/模块统计、包内 JAR 映射、静态契约统计和依赖范围。
- `maven-osv.json`、`boot-jarmode-osv.json`、`contracts.json`：原始依赖查询及接口/风险位置扫描结果。
- `probes.txt`、`final-probes.txt`：SQL/权限/迁移/同步/URL、Flink回环请求与旧报告选择的实际输出。
- `readonly-checks.txt`：历史证据内容/新鲜度、PS7编码和CI密钥校验。
- `clean-verify.txt`：隔离源码全模块 clean verify 原始日志。
- `npm-audit.json`：npm 原始公告、范围和修复建议。
- `AetherisReviewProbe.java`、`AetherisWorkflowReviewProbe.java`：隔离复现源代码；仅使用内存库/mock/自建HTTP目标。

原始盘点/扫描/OSV结果和审查脚本保留在本机 `C:\Users\yafei\AppData\Local\Temp\opencode\aetheris-review-*`，未把来源不明的其他 `aetheris-audit-*` 文件当作本次证据。业务源码没有修改，未提交或推送；用户原有 `.playwright-mcp/` 保持不动。

**最终判断：工程骨架和测试基础可保留；本次已复现的边界问题不能被“测试全绿”抵消。先修安全/迁移/交付与不确定执行结果，再完成本报告明确列出的真实运行验证，才适合给出生产就绪结论。**

# Changelog

本文件记录项目的重要变更，建议遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 与语义化版本。

## [Unreleased]

### Eleventh iteration

- 完成受管计算 M5b：固定 Git commit/path 后由服务端自动读取常规文件 blob，计算 Git 对象 ID 与 SHA-256，并限制浅克隆深度、文件类型和大小；人工摘要仅作为预期值参与失败关闭校验。
- 增加 OpenPGP 提交签名验证、完整指纹信任名单和 `required` / `optional` 策略；信任轮换先验证后落库，公钥环以只读方式注入，私钥与 token 不进入数据库。
- 制品登记、复核、手工复验和发布均重新核对 Git blob、签名及 Runner 部署文件，验证证据写入只追加历史；Spark attempt 固化对应源码证据快照。
- Spark 管理页补齐签名策略、指纹轮换、自动摘要提示、证据历史和人工复验，任务详情展示执行时的签名与 blob 摘要。
- 新增 V15 迁移、Git 负向安全测试、管理权限测试和前端 API 回归，并将整套 Compose 镜像纳入 M5b 重建与健康验收。

### Tenth iteration

- 完成阶段零可信基线：工作台改用全服务健康探测与真实审计活动流，修复并行探测共享集合的并发风险，导航只开放形成闭环的能力。
- 建立正式业务域，数据表、逻辑模型、语义维度和指标统一使用外键归属；编码成为创建后不可修改的稳定身份。
- 逻辑模型升级为结构化实体/关系图，发布时校验真实表绑定、实体角色、关系基数和等值关联表达式。
- 新增可复用语义维度，绑定受管表真实字段，支持校验、发布、版本历史、废弃和草稿回退。
- 指标补齐来源表、模型、聚合、度量字段、结构化过滤、时间字段、默认粒度和维度绑定，并由受控编译器生成只读 SQL。
- 发布生命周期支持不可变快照与线上/草稿并行；创建下一版不再中断 v1 消费，支持放弃草稿、替换发布和下线。
- 分析数据集支持直接消费已发布语义指标；看板无需改变消费协议即可复用统一口径。
- DataAgent 新增 `searchMetrics` / `queryMetric`，标准经营问题优先使用发布指标，无匹配时再回退到元数据与 SQL。
- 新增语义 SQL 安全、版本隔离、活动角色边界、AI 工具及跨服务指标数据集测试；同步数据库迁移与 Docker 构建链路。

### Ninth iteration

- 数据接入任务从配置骨架升级为真实批量执行器：来源查询走元数据服务只读守卫，目标写入支持 append / replace、批处理、事务回滚、并发运行互斥及运行记录。
- 数据质量规则支持非空、范围、枚举、重复与自定义只读 SQL 检查，持久化违规数、阈值、耗时、触发人和错误信息。
- 接入任务与质量规则接入 Quartz JDBC JobStore，支持持久化定时调度；执行失败写入用户待办，工作台可查看和标记已读。
- 工作流写操作补齐前后端双层 RBAC，viewer 无法创建、修改、删除、运行任务或维护依赖。
- 新增安全即席查询与任务监控页：人工查询复用元数据只读守卫，监控页汇总最近实例、成功率、耗时及错误。
- CI 新增跨服务冒烟链路，真实验证登录、批量接入、工作流写入、质量检查、分析查询和 viewer 越权拦截。
- 所有 Java 镜像加入无系统工具依赖的健康探针，Compose 按 `service_healthy` 启动依赖与网关。
- 产品导航、子导航与全局搜索仅展示已交付功能；前端页面按路由懒加载，并移除约 9 MB 的中文 WebFont 资源。

### Eighth iteration

- DataAgent 打通：AI 助手新增「数据模式」，可自主检索数仓元数据、确认表结构、执行只读查询并给出带真实数字的回答。
- 新增三个 agent 工具（`searchTables` / `describeTable` / `query`），工具描述里写明数仓分层语义与分区要求，引导模型优先用 ADS/DWS 层而非含脏数据的 ODS 层。
- 工具调用循环由 Spring AI 的 `ChatClient` 内建驱动，不自行实现多轮循环。
- 工具端点下沉到 metadata 的 `/internal/agent/**`：凭据、JDBC 驱动与 `validateReadOnlySql` 守卫都在那边，与 workflow 的取舍一致。网关只路由 `/api/**`，实测该路径返回 404。
- agent 查询与人工查询受同一套只读守卫约束，未放宽任何限制；agent 使用只读账号，即便守卫被绕过也无写权限。
- 工具失败时返回带 error 字段的结构化结果而非抛异常，让模型看到原因后自行改写重试；检索无结果时附带改写建议，避免模型凭空编造表名。
- 系统提示固化四条纪律：必须先检索再看结构再查询、统计优先用 ADS/DWS、查询必须带 dt 分区条件、以字段注释为口径依据。
- 修复 ai-agent 缺少 `METADATA_SERVICE_HOST` 环境变量：base-url 回落到 localhost 指向自身，表现为工具调用全部返回空、模型误判「仓库里没有任何表」。workflow 侧本已正确配置，故阶段 2 未暴露此问题。
- 任务保存时即校验 SQL 白名单（新增 metadata 的 `/internal/sql/validate-write`），修复「保存成功、点运行才失败」的问题；校验与执行共用同一份 `SqlWriteGuard`，不在 workflow 复制规则以免漂移。
- 前端 AI 对话页新增数据模式开关，两种模式的超时与提示文案分别设置。
- 显式声明 `dayjs` 依赖：此前它只是 antd 的传递依赖，直接引用会在 antd 变更时断裂。

### 关于 pgvector

原计划引入 pgvector 做元数据向量检索，核实后**暂缓**：当前 `metadata_table` 仅 20 张表，
全部表名与注释可直接放入上下文，关键词检索已足够；而切换镜像需从 Alpine(musl) 跨到
Debian(glibc)，`en_US.utf8` collation 行为不同会影响文本索引。表数量增长到数百张后再引入，
届时用 dump/restore 迁移成本相同。

### Seventh iteration

- 新增 `workflow` 服务（原为空目录），实现任务编排与调度：任务定义、Quartz 定时、运行实例、日志、重跑与依赖拓扑。
- Quartz 用 JDBC JobStore 且 `isClustered=true`：调度落库，服务重启不丢；多实例由 `QRTZ_LOCKS` 行锁保证同一次触发只被一个实例执行。
- Quartz 表结构从 2.5.0 发行包提取后脚本转换生成迁移（移除 DROP、加 IF NOT EXISTS），而非手工抄写；已比对 2.3.2（运行时版本）与 2.5.0 的列定义，确认零差异。
- 执行幂等：`(task_id, biz_date)` 唯一，重跑复用同一实例行并累加 attempt，实例列表不因反复重跑膨胀，也排除了同一业务日期被并发写入。
- SQL 执行下沉到 metadata 的 `/internal/datasources/{id}/execute`：凭据、JDBC 驱动、URL 协议校验与危险参数黑名单都已在 metadata，复制到 workflow 等于复制一份安全边界。网关只路由 `/api/**`，该端点外部不可达（实测返回 404）。
- 新增 `SqlWriteGuard`：写入路径用白名单只放行 `INSERT INTO/OVERWRITE` 与 `CREATE ... AS SELECT`，仍拒绝 `DROP`/`TRUNCATE`/`ALTER`/`GRANT` 与可读服务器文件的函数；只读查询路径的 `validateReadOnlySql` 不变。
- 修复守卫的检查顺序缺陷：白名单与分号检查原先用原始文本，导致以注释开头的真实加工脚本被误拒、注释里的分号被误判为多语句；改为先剥离注释与字符串字面量再检查（由测试发现）。
- 动态分区参数（`hive.exec.dynamic.partition`）由服务端注入而非让用户写在 SQL 里——守卫只放行单条语句，用户无法自带 `SET`。
- 依赖未就绪标记 `skipped` 而非 `failed`，避免告警噪声掩盖真正的故障；登记依赖时检测环形与自依赖并拒绝。
- 任务 SQL 支持 `${bizDate}` 占位符，同一份定义可跑任意业务日期，补数据无需改任务。
- 定时触发的业务日期取「昨天」，misfire 策略为 `DoNothing`：停机期间错过的触发不补跑，避免用错误的业务日期执行；补数据走手动触发显式指定日期。
- 端到端验收通过：任务写入 Hive 的 `order_cnt=5067 / gmv=6102169.27 / user_cnt=5067` 与独立计算完全一致，且与阶段 0 的 `ads_order_stats` 交叉印证。
- 新增写入专用数据源，任务用 HDFS 仓库属主身份连接；只读查询与 AI 助手仍走 `aetheris` 账号，读写分离保留（这是阶段 3 agent 只读保证的前提）。

### Sixth iteration

- 全部 8 个后端模块升级到 Spring Boot 3.4.7、Spring Cloud 2024.0.1、springdoc 2.8.6，零代码改动通过全量测试。
- `ai-agent` 引入 Spring AI 1.0.0，DeepSeek 调用改走 `spring-ai-starter-model-deepseek`，替代手写 RestClient；重试与超时由自动配置管理，429 不在服务端重试以免放大配额消耗。
- 新增 `LlmClient` 端口与 `SpringAiLlmClient` 适配器，业务逻辑不再绑定模型厂商；随依赖引入的 `spring-ai-autoconfigure-model-tool` 为后续工具调用打好基础。
- 模型失败按 NOT_CONFIGURED / RATE_LIMITED / UNAVAILABLE / UNKNOWN 分类，分别映射 503 / 429 / 502；未配置密钥时提前失败而不是打到 provider 拿 401。
- provider 原始报文不再外泄给调用方（可能含请求内容或密钥片段），只记日志。
- 回复携带 provider 实际使用的模型标识，实测请求 `deepseek-chat` 会被路由到 `deepseek-v4-flash`。
- 修复限流器内存泄漏：原实现用 `ConcurrentHashMap` 累积每个用户的窗口且从不清理；现在按需淘汰过期条目并设容量上限。
- 修复限流器并发缺陷：原用 CAS 重置窗口，并发下多线程可能各自新建窗口而放大配额；改用 `compute` 保证同一 key 原子创建，并加并发测试锁定。
- `ChatController` 只保留协议转换，会话编排下沉到新的 `ChatService`，模型调用走端口，三者均可单测。
- 用户提问先落库再调模型，模型失败时提问仍保留，刷新后不会凭空消失。
- `ai-agent` 测试从 2 个增加到 20 个（controller 9 + LLM 适配器 7 + 限流器 4）。

### Fifth iteration

- 元数据支持数仓分层：`metadata_table` 新增 `layer` / `domain` / `layer_source`，采集时按库名（推断不出时退回表名前缀）自动判定 ods/dwd/dws/ads/dim。
- 层级推断要求层级名后跟分隔符，避免 `adsense_log`、`odyssey_events` 之类的名字被前缀误判；匹配不上归入 `other` 由人工修正。
- 人工指定过的层级（`layer_source=manual`）不会被重复采集覆盖，与 `source` 区分手工/自动的取舍逻辑一致。
- 新增「数仓地图」页与 `/api/metadata/tables/warehouse-map` 接口，按数据流向展示分层结构；空层级也返回，避免前端分层图错位。
- 元数据目录页新增分层、业务域筛选与分层标签列。
- 修复 Hive/Spark 数据源采集不到表注释：`SHOW TABLES` 与 JDBC `getTables()` 都不返回 REMARKS，改为逐表 `DESCRIBE FORMATTED` 读取 Comment 行。表注释是 NL2SQL 命中率的关键信息。
- 新增 SQL 血缘解析：`/api/metadata/lineages/parse` 从加工 SQL 提取表级血缘。实测 JSqlParser 4.9 无法解析 Hive 的 `INSERT OVERWRITE TABLE ... PARTITION`，故改用面向 Hive 方言的提取器，覆盖 `INSERT OVERWRITE/INTO`、`LATERAL VIEW`、`DISTRIBUTE BY`、`CREATE TABLE AS SELECT`。
- 血缘解析排除 CTE 名称、`range()` 等表值函数与自引用，避免产生不存在的表节点；同一目标表重复解析时替换其既有解析边，人工登记的关系不受影响。
- 删除骨架期的示例血缘种子数据（`ods_orders → dwd_orders` 等，表名为复数，真实数仓中不存在）。
- `lineage_relation` 新增 `source` 字段区分人工登记与 SQL 解析，并对 (上游, 下游) 建唯一索引。

### Fourth iteration

- 新增数仓分层建库与造数脚本，建立 `ods` / `dwd` / `dws` / `ads` / `dim` 五层库与 16 张表（电商域）。
- ODS 层刻意保留 11 类脏数据（空值、重复行、非法枚举、超范围值、格式不一致、负价格等），DWD 逐条清洗，为质量规则与 DataAgent 提供真实作用对象。
- 事实表按 `dt` 分区，订单区间从当天倒推，使「上个月」「近 7 天」这类问题始终有对应分区。
- 全部字段带中文注释，并固化 GMV / 客单价 / 有效订单口径，作为后续语义层指标定义的唯一来源。
- 造数支持 `tiny` / `wsl` / `cluster` 三档规模与按步骤重跑；`wsl` 档约 130 万订单、320 万明细、180 个分区。
- 修复 Spark Thrift Server driver 堆内存不足：`local[N]` 下 driver 兼作 executor，原 1g 在写百万级订单时抛 `OutOfMemoryError` 并导致进程退出，现默认 3g 且可配置。
- 写动态分区的语句补充 `DISTRIBUTE BY dt`，避免单个 task 同时持有全部分区的 Parquet writer 而耗尽堆内存。
- 修复 `wsl-hive-test-server.sh restart` 的竞态：`stop` 异步发送 SIGTERM，紧随的 `start` 会因端口仍在监听而误判服务健在并跳过启动，导致 HDFS 未拉起；现在 `stop` 等待端口释放后才返回。
- `.gitignore` 补充 Hive/Derby 在仓库根产生的 `metastore_db/`、`derby.log` 与未展开变量的字面量目录。

### Third iteration

- 元数据支持从数据源自动采集库表信息，手工登记的记录不会被采集覆盖。
- 采集失败时不再把既有表误标为已删除；目标库中消失的表标记为 stale 而非物理删除。
- 网关不再公开 `/actuator` 全部端点，`gateway` 路由端点不再暴露内部服务地址。
- 审计日志改为早于鉴权执行，覆盖失败登录与越权尝试；身份只取自已验签的令牌。
- 只读查询补充危险函数黑名单，JDBC 连接参数与自定义 URL 拒绝可读取本机文件的驱动配置。
- 数据源错误信息不再回显驱动原文。
- 登录失败超过 5 次锁定 15 分钟。
- AI 限流改为按用户计量，消息角色与末条消息来源纳入校验，删除会话补充事务。
- 修复用户昵称、邮箱、手机号无法清空的问题。
- 前端超时与后端对齐，AI 对话不再出现「前端报错但回复已入库」。

### First iteration

- 新增根 Maven 聚合工程，统一构建全部八个后端模块。
- CI 改为构建并测试全部后端模块。
- 新增网关、认证与 AI 会话隔离单元测试。
- AI 会话增加所属用户字段及 Flyway 迁移，只允许所属用户访问。
- 网关覆盖客户端伪造的 `X-User-Name` 请求头。
- 初始管理员改为环境变量配置，首次启动要求至少 12 位密码。
- JWT 密钥不足 32 字节时拒绝启动并给出明确错误。
- 修复登录用户昵称为空时的服务端异常。

### Second iteration

- `auth_user` 增加角色、邮箱和手机号，现有账号兼容迁移为管理员。
- 新增登录账号管理接口，密码仅保存 BCrypt 哈希。
- JWT 增加角色声明，网关实现 `admin` / `viewer` 最小 RBAC。
- 覆盖客户端伪造的用户角色请求头，普通用户的管理写请求返回 403。
- 管理员不能删除、禁用或降级当前账号，系统始终保留至少一个启用管理员。
- 前端用户权限页切换到 Auth 服务，并根据角色保护路由和菜单。

### Added

- 初始化 monorepo 目录结构与基础文件。
- 添加 `.gitignore`、`.editorconfig`、`Makefile`、`docker-compose.yml` 与 CI 占位工作流。
- 端到端最小闭环：`system` 服务 + API 网关 + 前端控制台 + PostgreSQL。
- 前端重构为 React 18 + Ant Design 5 企业级控制台。
- 元数据服务（数据源管理）与前端数据源管理页面。
- Docker 构建切换国内镜像源，打通一键构建。
- 每服务独立 schema 隔离、网关 CORS、前端按路由分包。

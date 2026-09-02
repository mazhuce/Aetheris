# 架构设计

## 目标

从个人数据中台起步，逐步补齐数据平台核心能力，最终演进为企业级数据平台。

## 总体架构

```text
[Web 控制台]
      |
[API 网关]
      |
      +--> auth / system      认证与系统管理
      +--> metadata           元数据
      +--> ingestion          数据接入
      +--> quality            数据质量
      +--> governance         数据治理
      +--> serving            数据服务/开放
      +--> workflow           任务编排
      +--> ai-agent           AI / Agent
      |
[数据库 / 缓存 / 消息队列 / 对象存储]
```

## 关键决策

1. **Monorepo**：前期个人开发统一管理；后期可按领域拆分为多仓。
2. **Java 微服务**：Spring Boot + Spring Cloud（建议 Gateway、Nacos、OpenFeign）。
3. **前端**：React 18 + TypeScript + Vite + Ant Design 5（企业级控制台风格）。
4. **多语言兼容**：以 OpenAPI（REST/HTTP）与 Protobuf（gRPC）作为跨语言契约，统一放在 `packages/contracts/`。
5. **数据库迁移**：Flyway 或 Liquibase 版本化迁移，脚本放 `database/migrations/`。
6. **部署演进**：`docker-compose`（本地）→ Kubernetes（生产）。
7. **数据库隔离**：每个微服务使用独立 schema（`system` / `metadata`），Flyway 按各自 schema 管理迁移，互不干扰。

## 单个 Java 微服务目录建议

```text
services/<name>/
├── pom.xml                       # 或 build.gradle
├── Dockerfile
└── src/
    ├── main/java/...
    ├── main/resources/application.yml
    └── test/java/...
```

## 技术栈与版本矩阵

本阶段锁定稳定版本，并保证彼此兼容（当前本机环境：Java 21.0.7、Maven 3.9.10、Node 23、Docker 28）。

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 21 LTS | 已安装，长期支持 |
| Spring Boot | 3.4.7 | 与 Spring AI 1.0.0 对齐（后者构建在 Boot 3.4.5 上） |
| Spring Cloud | 2024.0.1 | 对应 Boot 3.4.x，仅引入 Gateway |
| Spring AI | 1.0.0 | `spring-ai-starter-model-deepseek`，仅 ai-agent 使用 |
| MyBatis-Plus | 3.5.7 | `mybatis-plus-spring-boot3-starter`，与 Boot 3.4 兼容 |
| Flyway | 10.x | 版本由 Spring Boot BOM 管理 |
| springdoc-openapi | 2.8.6 | 兼容 Boot 3.4 |
| PostgreSQL | 16-alpine | 数据持久层 |
| React / Vite / TypeScript | 18 / 6 / 5.7 | 前端 |
| Ant Design / Axios | 5 / 1.7 | UI 与 HTTP 客户端 |

> Nacos / 注册中心暂不引入，避免在执行引擎尚未稳定时扩大运维复杂度；计划在 Phase 3 单独锁定版本后接入。

### 版本选择依据

升级前查询了 Maven Central 实际发布的构件（而非依赖二手资讯），确认：

- Spring AI 最新为 **1.0.0**，其 starter 自身依赖 `spring-boot-starter 3.4.5`，因此 Boot 选 3.4.7 对齐；
- Spring Boot 最新为 3.5.x，**不存在 4.x 构件**；Spring Framework 7.x 只有里程碑版（M1–M6），无 GA；
- MyBatis-Plus 无 `boot4-starter`，`boot3-starter:3.5.7` 是当前最新且兼容 Boot 3.4。

网络上有关 "Spring AI 2.0 GA / Spring Boot 4.x 已发布" 的说法与 Maven Central 的事实不符，
升级时应以仓库中能实际拉到的构件为准。

## 任务执行架构

任务执行刻意拆成两个服务，而不是让 workflow 自己连数据库：

```text
workflow / ingestion / quality       metadata-service
  Quartz 调度 / 依赖拓扑                数据源凭据 / JDBC 驱动
  批量接入 / 事务写入           ──▶     URL 协议校验 / 参数黑名单
  质量检查 / 运行留痕                   只读与写入 SQL 守卫
                                       ↓
                                Hive / Spark / PostgreSQL / MySQL
```

**为什么数据库执行能力放在 metadata**：它已持有凭据、驱动（含 hive-jdbc 的 12 处 exclusion）、
`buildUrl` 的协议校验和危险连接参数黑名单。把这些复制到 workflow 不只是重复代码，
而是复制一份安全边界——两边任一处遗漏就是缺口。走 `/internal/**` 路径，
网关只路由 `/api/**`，因此该端点仅在 Compose 内部网络可达。

### 两条 SQL 路径的守卫

| 路径 | 守卫 | 放行 | 用途 |
| --- | --- | --- | --- |
| 只读查询 | `validateReadOnlySql` | `SELECT`/`SHOW`/`DESCRIBE`/`EXPLAIN` | 数据源浏览、AI 助手 |
| 写入执行 | `SqlWriteGuard` | `INSERT INTO/OVERWRITE`、`CREATE ... AS SELECT` | 数仓加工任务 |

两条路径都拒绝多语句、`DROP`/`TRUNCATE`/`ALTER`/`GRANT` 与可读服务器文件的函数。
写入白名单刻意不含 `DROP`：`INSERT OVERWRITE` 覆盖分区已能满足重跑，不需要先删表。

### 幂等设计

- **实例幂等**：`(task_id, biz_date)` 唯一，重跑复用同一行并累加 attempt，
  实例列表不因反复重跑膨胀，也排除了同一业务日期被两个实例并发写入。
- **数据幂等**：依赖加工语句用 `INSERT OVERWRITE ... PARTITION` 覆盖目标分区，
  因此重跑不产生重复数据，不依赖事务回滚（Hive/Spark 本就不支持事务性 DML）。

### 读写权限分离

数仓加工任务用 HDFS 仓库属主身份连接（写权限），只读查询与 AI 助手用 `aetheris` 账号
（仓库目录对其他用户为 `r-x`）。这不是权宜之计：阶段 3 的 DataAgent 需要「即使 SQL 守卫
被绕过也无法写入」的兜底保证，因此两个账号不合并。

## DataAgent 架构

数据问答的完整链路：

```text
用户提问（数据模式）
      ↓
ChatController        协议转换、限流
      ↓
ChatService           会话编排、持久化
      ↓
SpringAiLlmClient     ChatClient 驱动工具调用循环
      ↓  ↑
DataTools             @Tool 注解暴露给模型的三个工具
      ↓  ↑
AgentToolClient  ──▶  metadata /internal/agent/**
                        searchTables / describeTable / query
                        ↓
                      Hive / Spark（只读账号）
```

工具调用循环由 Spring AI 的 `ChatClient` 内建驱动（模型请求调用 → 框架执行 → 回填结果
→ 再次询问模型），不需要自己实现多轮循环。

### 三个工具的职责

| 工具 | 作用 | 约束 |
| --- | --- | --- |
| `searchTables` | 按关键词与层级检索表 | 排除 stale 表，按层级排序让 ADS/DWS 优先 |
| `describeTable` | 读取字段名、类型、**注释** | 必须先检索拿到 id |
| `query` | 执行只读 SQL | 走 `validateReadOnlySql`，最多 100 行 |

工具失败时返回带 `error` 字段的结构化结果而非抛异常——模型看到原因后能自行改写重试，
抛异常会中断整轮对话反而拿不到结果。

### 为什么工具端点在 metadata

与 workflow 的取舍一致：凭据、JDBC 驱动、只读守卫都已在 metadata，复制到 ai-agent
等于复制一份安全边界。走 `/internal/**` 路径，网关只路由 `/api/**`，外部不可达。

### 三层防护

1. **语句守卫**：`validateReadOnlySql` 只放行 SELECT/SHOW/DESCRIBE/EXPLAIN，
   agent 生成的 SQL 与人工输入受同一套约束，未放宽任何限制；
2. **账号隔离**：agent 使用只读账号，即便守卫被绕过也无写权限；
3. **网络隔离**：工具端点不经网关暴露。

### 系统提示的作用

提示里固化了四条纪律，都对应本项目数仓的真实特征：

- **必须先检索再看结构再查询** —— 避免凭字段名猜测拼 SQL；
- **统计优先用 ADS/DWS** —— ODS 层保留脏数据（空值、重复行、非法枚举），直接统计得错误结论；
- **查询必须带 dt 分区条件** —— 事实表百万级且按 dt 分区，不带条件会全表扫描超时；
- **以字段注释为口径依据** —— 「有效订单」「GMV」在本项目有固定定义，不让模型自行发明。

实测效果：问「2026 年 8 月销售额最高的品类」，agent 自主选择了 ADS 层、带上了
`month_key` 过滤，并在回答里说明了来源表与口径。

### 元数据检索：暂用关键词而非向量

当前 `metadata_table` 仅 20 张表，全部表名与注释可直接进入上下文，关键词检索
（匹配表名、表注释、业务域）已足够。pgvector 的价值在表数达数百张时才显现，
且切换镜像需从 Alpine(musl) 跨到 Debian(glibc)，collation 行为差异会影响文本索引。
待表数量增长后再引入。

## AI 能力分层

`ai-agent` 通过端口隔离模型厂商：

```text
ChatController      仅协议转换（限流、状态码映射）
      ↓
ChatService         会话编排、校验、持久化
      ↓
LlmClient           端口（接口）
      ↓
SpringAiLlmClient   Spring AI + DeepSeek 适配器
```

这样做的目的是让后续 DataAgent 的核心工作（工具调用循环、语义层、SQL 守卫）不与具体
provider 耦合；换模型只替换适配器。随 Spring AI 引入的
`spring-ai-autoconfigure-model-tool` 提供工具调用能力，
`spring-ai-starter-vector-store-pgvector` 可在现有 PostgreSQL 上做元数据检索，
两者都不需要引入新中间件。

## 演进路线

- **Phase 1（已完成）**：单机 Docker Compose、前端、网关、领域服务、PostgreSQL 与 JWT 登录闭环。
- **Phase 2（进行中）**：数据接入与质量执行引擎、持久化调度、失败待办、任务监控、RBAC、审计、跨服务测试和容器健康检查已完成；高级治理与指标可观测性继续建设。
- **Phase 3（已完成基础设施）**：升级到 Boot 3.4.7 + Spring AI 1.0.0，引入 DeepSeek starter、工具调用能力、pgvector 向量库支持；为 DataAgent（元数据检索、NL2SQL、查询执行）打好基础。后续接入注册/配置中心与 Python 数据服务。
- **Phase 4**：Kubernetes + 完整数据治理与服务开放能力。

## 当前进度

- Monorepo 目录结构、Git 规范、版本矩阵、企业级前端设计规范已建立。
- 已实现：前端、网关、`auth`、`system`、`metadata`、`quality`、`ingestion`、`governance`、`workflow`、`serving`、`ai-agent` 与 PostgreSQL 编排。
- 已形成真实执行闭环：登录鉴权、数据源连接测试/即席只读查询、跨关系型数据源批量接入、质量检查、持久化 SQL 调度、失败待办、DeepSeek 对话与按用户隔离的会话持久化。
- 已建立最小 RBAC：认证账号由 `auth` 服务统一管理，JWT 携带 `admin` / `viewer` 角色，网关负责写操作授权。
- `ai-agent` 分层清晰（controller 协议转换、service 编排、LlmClient 端口、SpringAiLlmClient 适配器），测试覆盖从 2 个增至 20 个，为后续工具调用循环与 DataAgent 扩展准备好基础。
- 数仓底座已建立：WSL Spark 五层数仓（ODS/DWD/DWS/ADS/DIM）130 万订单 + 320 万明细、11 类脏数据、180 个分区；元数据采集支持层级推断、表注释抓取、17 条真实血缘（从 SQL 解析生成）。
- `system.sys_user` 作为旧版资料目录暂时保留，后续在确认无外部调用后再通过独立迁移退役。
- 高级治理策略与数据服务开放仍处于管理能力阶段，未交付入口不会显示在产品导航中。
- Docker 一键构建与启动已打通，并使用国内镜像源。

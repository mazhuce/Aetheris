# Aetheris 

自用数据中台，通过持续迭代与功能拓展，逐步演进为企业级数据平台。

当前阶段：已具备统一登录、真实数仓分层、任务调度执行与数据智能体闭环。用户可以用自然语言提问，
AI 自主检索元数据、编写并执行只读 SQL，返回带真实数字的回答；也可以定义 SQL 加工任务并按 cron 调度。
数据接入、质量与治理的执行引擎仍在迭代中。

## 已实现的核心闭环

| 能力 | 说明 |
| --- | --- |
| 数仓分层 | ODS / DWD / DWS / ADS / DIM 五层，采集时按库名自动推断层级 |
| 元数据采集 | 反向采集库表与字段，含表注释（Hive 需 `DESCRIBE FORMATTED` 才能取到） |
| 数据血缘 | 从加工 SQL 解析表级血缘，非人工维护 |
| 任务调度 | Quartz JDBC JobStore，重启不丢调度；按业务日期幂等，重跑覆盖分区 |
| 数据智能体 | 自然语言问答，AI 自主调用工具检索元数据并执行只读查询 |
| 安全边界 | 读写账号分离、SQL 白名单守卫、内部端点不经网关暴露 |


## 目录结构

```text
aetheris/
├── apps/                 # 可运行应用（前端、API 网关）
├── services/             # 后端微服务（Java，按业务领域划分）
├── packages/             # 跨模块/跨语言复用代码
├── infrastructure/       # 部署与基础设施（Docker/K8s/Terraform）
├── database/             # 数据库迁移与脚本
├── docs/                 # 架构与开发文档
├── scripts/              # 工具脚本
├── tests/                # 跨模块/端到端测试
├── configs/              # 全局配置模板
├── examples/             # 示例
├── tools/                # 开发工具
├── .github/workflows/    # CI/CD
├── docker-compose.yml    # 本地开发编排
├── Makefile              # 常用命令入口
└── README.md
```

## 设计原则

1. 顶层只保留「物理分层」（应用 / 服务 / 包 / 基础设施），业务域下沉到 `services/` 内部，避免分类维度混乱。
2. `packages/contracts/` 统一维护 OpenAPI / Protobuf 契约，作为多语言之间的唯一接口来源，解决多语言兼容与连接问题。
3. 每个微服务独立构建、独立部署，通过 API 网关统一对外。
4. 数据库变更一律走各服务 `src/main/resources/db/migration/` 下的 Flyway 版本化迁移，禁止手工改库。
5. 本地开发用 `docker-compose.yml` + `Makefile` 统一入口。

## 快速开始

```bash
git clone <your-repo-url>
cd aetheris

# 配置本地 Git 身份（仅首次）
git config user.name  "你的名字"
git config user.email "你的邮箱"

# 首次启动必须配置 JWT 密钥和初始管理员密码
cp .env.example .env
# 编辑 .env，替换 JWT_SECRET 和 INITIAL_ADMIN_PASSWORD

# 查看可用的 make 命令
make help
```

### 一键启动（Docker）

```bash
docker compose up --build -d
```

启动后访问：

- 前端：http://localhost:5173
- 网关：http://localhost:8080
- PostgreSQL（宿主机映射端口）：5433

> 业务服务仅在 Compose 内部网络开放，由网关统一鉴权和转发；不要在生产环境使用 `.env.example` 中的示例密钥或口令。

## 当前服务

| 服务 | 内部端口 | 能力 |
| --- | --- | --- |
| gateway | 8080 | API 路由与 JWT 鉴权 |
| system | 8081 | 系统用户、系统配置 |
| metadata | 8082 | 数据源、元数据目录、数仓地图、血缘解析、只读查询、SQL 执行（内部） |
| quality | 8083 | 质量规则管理 |
| ingestion | 8084 | 接入任务管理，执行器待实现 |
| governance | 8085 | 治理策略管理 |
| ai-agent | 8086 | 数据智能体：工具调用、NL2SQL、会话持久化 |
| auth | 8087 | 登录与 JWT 签发 |
| workflow | 8088 | SQL 加工任务定义、Quartz 调度、运行实例与依赖 |

> `metadata` 与 `ai-agent`、`workflow` 之间通过 `/internal/**` 路径通信。网关只路由
> `/api/**`，因此这些内部端点不对外暴露 —— 数据源凭据与 JDBC 驱动只存在于 `metadata`，
> 其他服务不复制这份安全边界。

### 账号与权限

- `admin`：可以查看并修改平台资源，同时管理登录账号。
- `viewer`：可以查看平台资源并使用 AI 助手，不能新增、修改或删除管理数据。
- 登录账号由 `auth.auth_user` 统一管理；旧的 `system.sys_user` 暂时作为兼容数据保留，不再由前端账号页使用。
- 权限升级后旧 JWT 不包含角色，请退出并重新登录获取新令牌。

### 本地开发

```bash
# 后端：先启动数据库，再单独运行所需服务
docker compose up -d postgres
cd services/system && mvn spring-boot:run
cd apps/gateway && mvn spring-boot:run

# 前端：Vite 开发服务器（已配置 /api 代理到网关 8080）
cd apps/web && npm install && npm run dev

# 全部后端模块测试
mvn -f pom.xml -s scripts/maven-mirror-settings.xml test
```

### 数仓测试环境

数据智能体与任务调度需要真实的数仓数据。本项目在 WSL 上跑 Spark Thrift Server + Hive
Metastore + HDFS 作为测试环境，并提供造数脚本：

```bash
# 启动 Hive 栈
bash scripts/wsl-hive-test-server.sh start

# 建五层库并生成电商域测试数据（默认 wsl 档：约 130 万订单、320 万明细、180 个分区）
bash scripts/gen-warehouse-data.sh

# Docker 内的服务访问 WSL 需要端口转发
pwsh ./scripts/windows-wsl-hive-relay.ps1 start
```

数据刻意保留 11 类脏数据（空值、重复行、非法枚举、格式不一致等）并带完整中文注释——
前者让清洗与质量规则有真实作用对象，后者是 NL2SQL 准确率最重要的信息来源。
详见 [docs/warehouse-layers.md](docs/warehouse-layers.md)。

更多内容见：

- [docs/architecture.md](docs/architecture.md)：架构设计、版本矩阵与演进路线
- [docs/warehouse-layers.md](docs/warehouse-layers.md)：数仓分层约定、脏数据对照表、口径定义
- [docs/datasource-connectivity.md](docs/datasource-connectivity.md)：WSL / VMware 数据源连通性
- [docs/git-workflow.md](docs/git-workflow.md)：Git 开发工作流
- [CHANGELOG.md](CHANGELOG.md)：逐次迭代的变更与决策理由

<img width="450" height="450" alt="AETHRIS" src="https://github.com/user-attachments/assets/87b4d847-9627-4c0e-a965-d23fbe9ee8c9" />

# Aetheris 

自用数据中台，通过持续迭代与功能拓展，逐步演进为企业级数据平台。

当前阶段：数据中台管理面 MVP；已具备统一登录、领域管理、数据源查询和 AI 对话闭环，数据接入、质量与治理执行引擎仍在迭代中。

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
| metadata | 8082 | 数据源、元数据目录、血缘、只读查询 |
| quality | 8083 | 质量规则管理 |
| ingestion | 8084 | 接入任务管理，执行器待实现 |
| governance | 8085 | 治理策略管理 |
| ai-agent | 8086 | DeepSeek 对话与用户级会话持久化 |
| auth | 8087 | 登录与 JWT 签发 |

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

更多内容见：

- [docs/architecture.md](docs/architecture.md)：架构设计与演进路线
- [docs/git-workflow.md](docs/git-workflow.md)：Git 开发工作流

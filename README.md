# Aetheris
<img width="1254" height="1254" alt="AETHRIS" src="https://github.com/user-attachments/assets/87b4d847-9627-4c0e-a965-d23fbe9ee8c9" />

个人数据中台，目标是通过持续迭代与功能拓展，逐步演进为企业级数据平台。

当前阶段：基础架构设计与目录规划；后端采用 Java 微服务架构，前端先做数据中台 Web 控制台。

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
4. 数据库变更一律走 `database/migrations/` 的版本化迁移，禁止手工改库。
5. 本地开发用 `docker-compose.yml` + `Makefile` 统一入口。

## 快速开始

```bash
git clone <your-repo-url>
cd aetheris

# 配置本地 Git 身份（仅首次）
git config user.name  "你的名字"
git config user.email "你的邮箱"

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
- 系统服务健康检查：http://localhost:8081/actuator/health
- 系统服务接口文档：http://localhost:8081/swagger-ui.html
- 元数据服务健康检查：http://localhost:8082/actuator/health
- 元数据服务接口文档：http://localhost:8082/swagger-ui.html
- PostgreSQL（宿主机映射端口）：5433

> 数据库口令等可通过复制 `.env.example` 为 `.env` 后自定义；默认值均为 `aetheris`，仅用于本地开发。

### 本地开发

```bash
# 后端：先启动数据库，再单独运行服务
docker compose up -d postgres
cd services/system && mvn spring-boot:run
cd apps/gateway && mvn spring-boot:run

# 前端：Vite 开发服务器（已配置 /api 代理到网关 8080）
cd apps/web && npm install && npm run dev
```

更多内容见：

- [docs/architecture.md](docs/architecture.md)：架构设计与演进路线
- [docs/git-workflow.md](docs/git-workflow.md)：Git 开发工作流

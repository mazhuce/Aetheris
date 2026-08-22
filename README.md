# Aetheris

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

更多内容见：

- [docs/architecture.md](docs/architecture.md)：架构设计与演进路线
- [docs/git-workflow.md](docs/git-workflow.md)：Git 开发工作流

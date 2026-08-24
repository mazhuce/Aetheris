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
| Spring Boot | 3.3.5 | 稳定分支 |
| Spring Cloud | 2023.0.3 | 与 Boot 3.3 兼容，仅引入 Gateway |
| MyBatis-Plus | 3.5.7 | `mybatis-plus-spring-boot3-starter` |
| Flyway | 10.x | 版本由 Spring Boot BOM 管理 |
| springdoc-openapi | 2.6.0 | 兼容 Boot 3.3 |
| PostgreSQL | 16-alpine | 数据持久层 |
| React / Vite / TypeScript | 18 / 6 / 5.7 | 前端 |
| Ant Design / Axios | 5 / 1.7 | UI 与 HTTP 客户端 |

> Nacos / 注册中心暂不引入，避免在执行引擎尚未稳定时扩大运维复杂度；计划在 Phase 3 单独锁定版本后接入。

## 演进路线

- **Phase 1（已完成）**：单机 Docker Compose、前端、网关、领域服务、PostgreSQL 与 JWT 登录闭环。
- **Phase 2（进行中）**：实现数据接入、质量和治理执行引擎，补齐 RBAC、审计、测试与可观测性。
- **Phase 3**：引入注册/配置中心与 Python 数据服务，通过契约互联。
- **Phase 4**：Kubernetes + 完整数据治理与服务开放能力。

## 当前进度

- Monorepo 目录结构、Git 规范、版本矩阵、企业级前端设计规范已建立。
- 已实现：前端、网关、`auth`、`system`、`metadata`、`quality`、`ingestion`、`governance`、`ai-agent` 与 PostgreSQL 编排。
- 已形成真实执行闭环：登录鉴权、数据源连接测试/只读查询、DeepSeek 对话与按用户隔离的会话持久化。
- 已建立最小 RBAC：认证账号由 `auth` 服务统一管理，JWT 携带 `admin` / `viewer` 角色，网关负责写操作授权。
- `system.sys_user` 作为旧版资料目录暂时保留，后续在确认无外部调用后再通过独立迁移退役。
- 仍属于管理能力：接入任务、质量规则、治理策略；对应执行引擎尚未完成。
- Docker 一键构建与启动已打通，并使用国内镜像源。

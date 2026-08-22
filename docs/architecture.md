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
3. **前端**：Vue 3 或 React + TypeScript（待定，建议先定 Vue 3 + Vite）。
4. **多语言兼容**：以 OpenAPI（REST/HTTP）与 Protobuf（gRPC）作为跨语言契约，统一放在 `packages/contracts/`。
5. **数据库迁移**：Flyway 或 Liquibase 版本化迁移，脚本放 `database/migrations/`。
6. **部署演进**：`docker-compose`（本地）→ Kubernetes（生产）。

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

## 演进路线

- **Phase 1**：单机 docker-compose，1 个前端 + 1~2 个核心服务。
- **Phase 2**：按领域拆分服务，接入网关与注册中心。
- **Phase 3**：引入 Python 数据/AI 等多语言服务，通过契约互联。
- **Phase 4**：Kubernetes + 可观测性 + 完整数据治理能力。

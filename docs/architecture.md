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
| Vue / Vite / TypeScript | 3.5 / 6 / 5.7 | 前端 |
| Element Plus / Axios | 2.9 / 1.7 | UI 与 HTTP 客户端 |

> Nacos / 注册中心暂不引入，避免 Spring Cloud Alibaba 与 Boot 的版本矩阵在骨架阶段引入风险；Phase 2 再加入并单独锁定版本。

## 演进路线

- **Phase 1**：单机 docker-compose，1 个前端 + 1~2 个核心服务。
- **Phase 2**：按领域拆分服务，接入网关与注册中心。
- **Phase 3**：引入 Python 数据/AI 等多语言服务，通过契约互联。
- **Phase 4**：Kubernetes + 可观测性 + 完整数据治理能力。

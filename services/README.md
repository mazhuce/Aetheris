# services

后端微服务，按业务领域划分，每个目录是一个可独立构建、独立部署的 Java 服务。

| 服务 | 职责 |
| --- | --- |
| `auth` | 认证与权限（登录账号、JWT、admin/viewer RBAC） |
| `system` | 系统管理（用户、租户、字典、配置） |
| `metadata` | 元数据（数据源、库表、字段采集与管理） |
| `ingestion` | 数据接入（离线/实时同步与采集） |
| `quality` | 数据质量（规则、校验、评分、告警） |
| `governance` | 数据治理（血缘、目录、脱敏、安全） |
| `serving` | 数据服务（API/SQL 方式开放数据） |
| `workflow` | 任务编排（工作流调度与依赖） |
| `ai-agent` | AI / Agent（智能问答、指标解读、自动化） |

## 单个服务目录建议

```text
services/<name>/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/...
    ├── main/resources/application.yml
    └── test/java/...
```

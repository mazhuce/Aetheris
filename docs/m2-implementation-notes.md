# M2 实施说明：实训 MVP + MCP 基础能力

版本：2026-11-12 v1.0
对应排期：阶段 0（可靠基线）+ 阶段 1（里程碑 M1/M2）
关联设计：[Aetheris 数据中台实训平台总体设计与开发排期](aetheris-integrated-design-and-development-plan.md)

> 后续进展：M3/M4 垂直切片已在 M2 基础上增加内容审核、教师后台、AI 导师、过程证据、MCP 草稿/审批/长任务、L3/L4 六个任务与两个项目，见 [M3/M4 实施说明](m3-m4-implementation-notes.md)。本文件描述的是 M2 时点的范围与验证结果。

> 范围声明：本次交付的是 **可运行的 M2 垂直切片**，不是完整阶段 1。课程种子为 7 个 L1-L2 任务（设计目标是 40-60 个），MCP 只开放 L0 只读工具，教师后台、内容审核、AI 导师大模型接入、过程/性能判分仍属于后续工作。文中"已实现"均指代码与本地端到端验证通过，"未实现"单独列出，不做模糊表述。

---

## 1. 本次交付清单

### 1.1 新增服务

| 服务 | 端口 | schema | 职责 |
| --- | --- | --- | --- |
| `learning-service` | 8090 | `learning`、只读教学数据 `teach` | 课程目录、实验任务、Light 沙箱、判分、进度、凭证、分级提示 |
| `mcp-server` | 8091 | `mcp` | MCP JSON-RPC 协议、工具目录、Resources、Prompts、审计、限流 |

同时完成：

- 根 `pom.xml` 增加两个模块；
- 网关新增 `/api/learning/**` 与 `/api/mcp` 路由，`audienceOf` 支持 `learning`、`mcp`；
- `docker-compose.yml` 增加两个服务与网关依赖；
- `configs/database/services.json` 与两份迁移示例配置；
- `scripts/security/GenerateKeys.java` 的密钥清单加入 `learning`、`mcp`；
- metadata-service 的 agent 只读路由允许 `mcp` 作为调用方（仍要求 USER 证明）。

### 1.2 数据库迁移

`learning` schema（V1）：

- 课程：`learning_path`、`skill_node`、`course`、`course_module`、`lab`、`lab_skill`
- 学习过程：`sandbox_instance`、`submission`、`grading_result`、`grading_item`、`execution_evidence`
- 成长：`learner_skill_progress`、`credential`、`tutor_hint_log`

`teach` schema（V2）：

- `orders_raw`：26 行合成电商订单，刻意保留 6 类脏数据（重复订单、空用户、负数量、非法状态、逻辑删除、零价/未来时间）；
- `order_status_dict`：状态字典。

`learning` 课程种子（V3）：

- 1 条 L1-L2 路径、6 个技能节点、1 门课程、2 个模块、7 个实验任务、7 条任务-技能映射。

`mcp` schema（V1）：

- `mcp_call_log`：调用审计，只存参数 SHA-256 摘要，不落原始参数。

### 1.3 学习 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/learning/paths` | 路径列表 + 当前学员完成度 |
| GET | `/api/learning/paths/{code}` | 路径详情：模块、任务、技能雷达 |
| GET | `/api/learning/labs/{code}` | 任务卡；**不返回标准答案与隐藏测试** |
| POST | `/api/learning/labs/{code}/start` | 创建/复用 Light 沙箱 |
| POST | `/api/learning/labs/{code}/reset` | 重置沙箱（只影响本人 schema） |
| POST | `/api/learning/labs/{code}/submit` | 提交 SQL 并同步判分 |
| GET | `/api/learning/submissions/{id}` | 提交详情与评分项 |
| GET | `/api/learning/labs/{code}/submissions` | 最近提交列表（最多 50 条） |
| POST | `/api/learning/labs/{code}/hint` | 分级提示（规则引擎，最多 6 级） |
| GET | `/api/learning/progress` | 完成度、平均分、技能雷达、凭证 |
| GET | `/api/learning/credentials` | 凭证列表 |
| GET | `/api/learning/admin/overview` | 内容运营概览（用户名白名单） |
| GET | `/api/learning/admin/labs` | 任务清单（不含标准答案） |
| POST | `/api/learning/admin/labs/{code}/publish` | 发布任务 |

内部接口（仅 mcp-server，WORKLOAD + USER 双证明）：

- `POST /internal/v2/mcp/learning-path`
- `POST /internal/v2/mcp/lab`
- `POST /internal/v2/mcp/submissions`

### 1.4 判分引擎（M2 三层）

1. **静态安全**：单条语句、仅 SELECT/WITH、禁用写关键字；字符串与注释先剥离，避免 `'delete from x'` 误判或注释绕过。额外强制两项与课程无关的底线：禁止显式引用 `learning/mcp/metadata/...` 等平台 schema，禁止 `pg_read_file`、`dblink`、`COPY` 等文件或外部访问函数。
2. **结构规范**：`SELECT *` 禁用、必需列/必需正则（如 `ROW_NUMBER` + `PARTITION BY`）。
3. **动态结果**：在学员沙箱中分别执行标准答案与学员 SQL，比较列数、行数与多重集 SHA-256；行序不影响结果。

评分按任务的 rubric 权重计算（种子任务为 20/30/50），达到 `pass_score` 即通过。每条评分项、执行行数、校验和、耗时都写入 `grading_result` / `grading_item` / `execution_evidence`，可追溯。

### 1.5 Light 沙箱

- 每个学员一个 `sb_u<uid>` schema，表从 `teach` 复制；
- 启动实验只补齐缺失表，不覆盖学员改动；
- 重置只允许删除本人 schema；
- 24 小时 TTL，定时任务回收过期 schema；
- 查询连接强制 `READ ONLY` 事务、`statement_timeout`、固定 `search_path`，静态守卫之外再有一道数据库侧防线。

### 1.6 MCP 能力

协议基线：MCP `2026-07-28`，无状态 JSON-RPC over HTTP。

已实现方法：

- `initialize`、`notifications/*`、`ping`
- `tools/list`、`tools/call`
- `resources/list`、`resources/templates/list`、`resources/read`
- `prompts/list`、`prompts/get`

工具（全部 L0 只读）：

| 工具 | 数据来源 |
| --- | --- |
| `search_tables` | metadata-service |
| `describe_table` | metadata-service |
| `query_readonly_sql` | metadata-service（服务端再次执行 SQL 守卫） |
| `search_metrics` | metadata-service |
| `query_metric` | metadata-service |
| `get_learning_path` | learning-service |
| `get_current_lab` | learning-service |
| `get_grading_feedback` | learning-service |

Resources：

- `aetheris://learning/path`
- `aetheris://learning/lab/{labCode}`
- `aetheris://catalog/tables/{tableId}`

Prompts：`sql_review`、`metric_definition`、`data_quality_triage`、`learning_path_recommendation`、`lab_feedback`。

---

## 2. 安全边界

### 2.1 生产路径

- 网关签发按服务 audience 的 USER 证明；`learning-service` 与 `mcp-server` 都用 `DirectUserProofFilter` 验签并实时检查会话。
- `mcp-server` 向下游转发同一份 USER 证明，并用自身 WORKLOAD 私钥签署请求；metadata 的路由明确允许 `mcp` 调用方，且仍要求 USER。
- `learning-service` 的内部接口只允许 `mcp` 调用方，uid 从证明读取，不接受请求体自报。
- 所有 MCP `tools/call` 与 `resources/read` 写审计；参数只存摘要。
- 工具限流按用户固定窗口（默认 60 次/分钟），超限返回 `isError` 与 `RATE_LIMITED`。

### 2.2 本地演示模式（不可用于生产）

两个服务都提供显式开关：

```bash
AETHERIS_LEARNING_DEV_MODE=true
AETHERIS_MCP_DEV_MODE=true
```

- 未开启时，缺少有效证明一律 401，不存在静默回退；
- 开启后只接受 `X-Aetheris-Dev-User` 或配置的默认演示用户，启动日志会打印 WARN；
- 该模式只用于单机验收，生产部署必须配置 `aetheris.security.*` 密钥安装。

### 2.3 v2 密钥安装步骤

1. 用 `scripts/security/GenerateKeys.java` 生成密钥目录（已包含 `learning`、`mcp`）；
2. 为两个服务配置：
   - `AETHERIS_SECURITY_REGISTRY_FILE`
   - `AETHERIS_SECURITY_WORKLOAD_KID`
   - `AETHERIS_SECURITY_WORKLOAD_PRIVATE_FILE`
   - `AUTH_SERVICE_HOST/PORT`、`METADATA_SERVICE_HOST/PORT`、`LEARNING_SERVICE_HOST/PORT`
3. 确认网关能签发 audience 为 `learning` / `mcp` 的用户证明；
4. 确认 metadata 的路由允许 `mcp`，learning 的路由允许 `mcp`（代码已内置）。

> 注意：现有 `docker-compose.yml` 并未给任何服务挂载 v2 密钥，这是仓库既有的部署前置条件，本次未擅自改变部署安全模型。上线前需要按实际密钥目录补充 volume 与 env。

### 2.4 数据库权限提示

Light 沙箱需要为每个学员创建 `sb_u<uid>` schema，因此 `learning-service` 的数据库角色需要 `CREATE` 权限；生产环境建议使用独立数据库或仅授予该角色在专用 schema 前缀下的建权，避免与业务库共享过高权限。`teach` schema 由 V2 迁移创建，部署前需确认迁移角色具备建 schema 权限。

---

## 3. 已执行的验证

### 3.1 单元测试

| 模块 | 用例数 | 结果 |
| --- | --- | --- |
| `learning-service` | 17 | 全部通过 |
| `mcp-server` | 8 | 全部通过 |

覆盖点：只读守卫（写语句、多语句、字符串内关键字、SELECT *、必需结构、平台 schema 越界、文件函数）、结果校验和（行序无关、NULL 与空串区分）、判分权重（100/50/0、执行异常反馈）、MCP initialize/tools/resources/prompts/限流/通知。

### 3.2 端到端验证（本地 PostgreSQL 16 + 两个服务）

- Flyway：`learning` V1-V3、`mcp` V1 全部成功；
- 七个任务用标准答案提交，全部通过（修正两处 `SELECT *` 与静态策略冲突后，均为 100 分）；
- 错误投影（少列）→ 50 分，明确提示"列数不一致"；
- `DELETE FROM orders_raw` → 0 分，提示只允许 SELECT/WITH；
- 进度：7/7、100%、平均 100 分，6 个技能全部点亮，自动发放结业凭证；
- MCP：`initialize` 返回 `2026-07-28` 与三类能力；`tools/list` 返回 8 个工具；`get_learning_path`、`get_grading_feedback`、`resources/read` 正常；审计表落库 3 条（2 次工具 + 1 次资源读取）。

### 3.3 构建

- `mvn -f services/learning/pom.xml test` → BUILD SUCCESS（17 项）
- `mvn -f services/mcp/pom.xml test` → BUILD SUCCESS（8 项）
- `mvn -f apps/gateway/pom.xml test` → BUILD SUCCESS（62 项，覆盖新增 `learning` / `mcp` audience）
- `npm run type-check`（apps/web）→ 通过
- `npm run lint`（apps/web）→ 通过
- `npm run build`（apps/web）→ 通过（仅既有 chunk 体积告警）
- 全仓 `mvn -DskipTests package` → BUILD SUCCESS（15 个模块，含 metadata 编译）

---

## 4. 本地运行

```bash
# 1. 启动 PostgreSQL（示例）
docker run -d --name aetheris-learning-pg \
  -e POSTGRES_PASSWORD=aetheris -e POSTGRES_USER=aetheris -e POSTGRES_DB=aetheris \
  -p 55432:5432 postgres:16-alpine

# 2. 启动学习服务（演示模式）
DB_HOST=127.0.0.1 DB_PORT=55432 DB_NAME=aetheris DB_USER=aetheris DB_PASSWORD=aetheris \
AETHERIS_LEARNING_DEV_MODE=true \
mvn -f services/learning/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=8090

# 3. 启动 MCP 服务（演示模式）
DB_HOST=127.0.0.1 DB_PORT=55432 DB_NAME=aetheris DB_USER=aetheris DB_PASSWORD=aetheris \
AETHERIS_MCP_DEV_MODE=true \
mvn -f services/mcp/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=8091

# 4. 验证
curl -H 'X-Aetheris-Dev-User: dev-learner' http://127.0.0.1:8090/api/learning/paths
curl -X POST -H 'X-Aetheris-Dev-User: dev-learner' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' http://127.0.0.1:8091/api/mcp
```

Web 控制台：

- 学习中心：`/learning`
- 任务详情：`/learning/labs/{code}`
- MCP 调试台：`/learning/mcp`

---

## 5. 未实现与 M3 衔接

| 项 | 状态 | 建议 |
| --- | --- | --- |
| 40-60 个 L1/L2 任务 | 仅 7 个种子任务 | 内容作者按同一 schema 扩充，管理接口可发布 |
| 课程编辑/审核流 | 未实现 | M3 增加 authoring API 与审核状态机 |
| AI 导师大模型接入 | 规则提示已实现，LLM 未接 | 复用 ai-agent 的 LlmClient 与预算，增加教学工具 |
| 过程判分 / 性能判分 | 未实现 | 读取 workflow 执行审计与引擎指标 |
| Medium/Heavy 沙箱 | 未实现 | 阶段 2 引入 Spark 队列与资源配额 |
| MCP Tasks / MCP Apps / Skills | 未实现 | 阶段 2-3 |
| MCP 独立 OAuth / 个人令牌 | 未实现，目前仅网关会话 | 阶段 3 增加客户端注册与令牌管理 |
| 分布式限流 | 单实例内存实现 | 多副本前替换为 Redis 计数器 |
| 管理员授权 | 用户名白名单 | 接入 auth-service 的权限判定 |
| 生产密钥挂载 | 代码已支持，compose 未挂载 | 部署前补齐 volume 与 env |

---

## 6. 验收结论

M2 的核心链路已经可以真实跑通：

```text
学员进入学习中心 → 打开任务 → 创建沙箱 → 写 SQL → 提交
→ 静态安全 + 结构规范 + 动态结果三层判分 → 评分证据落库
→ 技能进度与凭证更新 → MCP 客户端读取同一份学习数据
```

它满足 M2 的三个最低验收条件：

1. 学员可以在无人工协助下完成一个 L1/L2 任务的完整闭环；
2. 每次评分都能追溯到真实执行的行数、校验和与耗时；
3. 外部 MCP 客户端可以通过标准协议读取受控的只读能力，并进入审计。

下一阶段（M3）应在不破坏这三条的前提下扩展课程容量、过程判分和 AI 导师。

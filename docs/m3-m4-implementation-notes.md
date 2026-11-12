# M3/M4 实施说明：内容运营、AI 导师、MCP 审批与 L3/L4 项目

版本：2026-11-13 v1.0
关联：[M2 实施说明](m2-implementation-notes.md) · [总体设计](aetheris-integrated-design-and-development-plan.md)

> **范围声明**：本文档覆盖两轮迭代。第一轮交付 M3 垂直切片（内容审核、教师后台、AI 导师、过程证据、MCP 草稿/审批/长任务、L3-L4 六个任务与两个项目）；第二轮补齐 **L5/L6 课程（L1-L6 全覆盖）+ Medium 沙箱 v0（并发槽位，超卖=0）**。**仍未实现**：基于 Spark 的 Medium/Heavy 批处理执行、500-1000 并发容量证明、多租户/SSO/K8s、MCP Apps/Skills、独立 OAuth、主观题 AI 初评与申诉流。

---

## 1. 交付内容

### 1.1 内容运营与审核（M3 WS-A / WS-E）

数据库新增：

- `learning.project`：端到端项目；
- `learning.lab.project_code / author / review_status / review_note / published_at`：内容版本与审核状态；
- `learning.approval_request`：MCP 高风险动作审批单。

流程：

```text
新建/编辑草稿 → 审核通过/驳回 → 发布 →（可选）下架
```

约束：

- 新建任务默认 `status=draft`、`review_status=draft`；
- 编辑已发布任务被拒绝，必须先下架；
- 编辑会递增版本并把审核状态重置为 `draft`；
- 发布必须同时满足 `review_status=approved` 且有标准答案；
- 审核通过但缺少标准答案会被拒绝。

### 1.2 教师后台

新增 Web 页面 `/learning/admin`：

- **内容库**：13 个任务的发布/审核状态、版本、更新时间；新建/编辑（标准答案仅管理员可见）、通过/驳回、发布/下架。
- **审批**：MCP 发起的发布审批，支持批准/驳回与审批意见；批准后自动发布已审核任务。
- **运营分析**：任务数、提交数、学员数、通过率、待审批、活跃沙箱；按任务统计提交次数、学员数、通过人数、平均分、主要失败类型。

管理接口（均要求用户名在 `aetheris.learning.admin-users`，默认 `admin`）：

| 方法 | 路径 |
| --- | --- |
| GET | `/api/learning/admin/labs` |
| POST | `/api/learning/admin/labs` |
| PUT | `/api/learning/admin/labs/{code}` |
| POST | `/api/learning/admin/labs/{code}/review` |
| POST | `/api/learning/admin/labs/{code}/publish` |
| POST | `/api/learning/admin/labs/{code}/unpublish` |
| GET | `/api/learning/admin/approvals?status=` |
| POST | `/api/learning/admin/approvals/{id}/decision` |
| GET | `/api/learning/admin/analytics` |

### 1.3 AI 导师（M3 WS-F 基础）

- 新增 `AiTutorClient`：OpenAI 兼容协议，默认 DeepSeek `chat/completions`。
- 配置项：`AETHERIS_AI_BASE_URL`、`AETHERIS_AI_API_KEY`、`AETHERIS_AI_MODEL`、`AETHERIS_AI_TIMEOUT_MS`、`AETHERIS_AI_MAX_TOKENS`。
- 未配置 Key 或调用失败时，**自动回退规则导师**，不会阻断学习流程；占位 Key（`sk-local-placeholder*`）视为未配置。
- 系统提示明确禁止输出可直接提交的完整 SQL，只给问题分类与 1-3 条提示。
- 对话写入 `learning.tutor_message`，教师可按学员/任务复盘。
- 任务页新增 AI 导师抽屉与提交时间线。

接口：

- `POST /api/learning/labs/{code}/tutor` → `{reply, model, fallback, history}`
- `GET /api/learning/labs/{code}/tutor/messages`

### 1.4 过程证据（M3 WS-C 的部分）

- 每次提交写入 `learning.attempt_event`：`SAFETY_BLOCKED` / `SQL_ERROR` / `RESULT_MISMATCH` / `PASSED`。
- 运营分析按任务汇总主要失败类型，用于定位课程难点与内容缺陷。
- 尚未实现独立“过程分”权重与主观题复核，当前过程证据用于分析与复盘，不改变 M2 的评分公式。

### 1.5 MCP 草稿、审批与长任务（M3/M4 WS-D）

工具从 8 个扩展到 11 个：

| 工具 | 风险 | 行为 |
| --- | --- | --- |
| `draft_lab_publish` | L1 | 只创建发布审批单和长任务，**不执行发布** |
| `get_approval_status` | L0 | 查询审批状态，并把长任务同步为 completed/rejected |
| `draft_quality_rule` | L0 | 生成质量规则 SQL 草稿并记录任务，不在任何数据源执行 |

协议扩展：

- `initialize` 的 capabilities 增加 `tasks`；
- 新增 `tasks/get`、`tasks/list`；
- 长任务落 `mcp.mcp_task`，状态：`pending_approval` → `completed` / `rejected`；
- 任务读取时以 learning 审批单为准做一次状态同步，避免双写漂移。

安全边界：

- MCP 没有任何直接写内容的路径；高风险工具只产生审批单；
- 审批单只能由内容管理员在 Web 后台处理；
- 审批前会再次校验内容审核状态，未审核内容不能借审批绕过；
- 审批单与长任务只允许申请人本人读取。

### 1.6 L3/L4 内容与项目（M3/M4 WS-A 部分）

新增模块与技能：

- `m-l3-analytics`：用户 RFM 分层、次日留存、支付-发货-完成漏斗；
- `m-l4-production`：按日增量快照、质量基线四项指标、GMV 三日移动平均；
- 技能：`analytics-modeling`、`retention-analysis`、`production-pipeline`、`quality-governance`。

两个端到端项目：

| 项目 | 层级 | 任务 | 交付物 |
| --- | --- | --- | --- |
| `proj-order-analytics` 订单分析端到端项目 | L3 | RFM、留存、漏斗 | 分层表、留存日报、漏斗、口径说明 |
| `proj-quality-production` 质量与生产化项目 | L4 | 增量快照、质量基线、移动平均 | 日快照、质量四项指标、趋势指标、重跑说明 |

Web 新增项目详情页 `/learning/projects/{code}`，学习中心展示项目进度。

### 1.7 L5/L6 课程与 Medium 沙箱 v0（第二轮）

课程扩展到 **L1-L6 全覆盖**：新增 `m-l5-assets`、`m-l6-ops` 两个模块、五个任务、两个技能和第三个项目 `proj-asset-operations`（数据资产与生产运维，L5-L6）：

| 任务 | 层级 | 主题 | 沙箱 |
| --- | --- | --- | --- |
| `l5-asset-inventory` | L5 | 按负责人的资产健康度盘点（先聚合再关联） | Light |
| `l5-metric-review` | L5 | 活跃指标 90 天未复核清单 | Light |
| `l6-sla-monitor` | L6 | 分区到达完整率与 SLA 状态 | Medium |
| `l6-backfill-plan` | L6 | 需重跑分区、原因与缺失行数 | Medium |
| `l6-alert-threshold` | L6 | 历史延迟 P95 告警阈值 | Medium |

新教学数据（`teach` schema）：`metric_dict`（指标字典）、`table_asset`（表资产）、`daily_arrival`（分区到达记录）。

**Medium 沙箱 v0**（不含 Spark，属槽位与配额层）：

- `learning.lab.sandbox_tier` / `learning.sandbox_instance.tier` 区分 Light/Medium；
- 全平台 Medium 并发上限 `AETHERIS_LEARNING_MEDIUM_CONCURRENCY`（默认 2），槽位申请在进程内互斥，**单实例下超卖 = 0**；满载返回 409 与明确提示；
- 本人复用自己的沙箱不占新槽；切换到 Light 任务自动降级释放槽位；重置保持档位；
- Medium 任务判分使用独立语句超时 `AETHERIS_LEARNING_MEDIUM_TIMEOUT_MS`（默认 60s），仍受只读事务与行数上限约束；
- 多实例部署需要把槽位判定升级为数据库级（advisory lock / 槽位表），列为 M5 事项。

---

## 2. 验证结果

### 2.1 单元测试

| 模块 | 用例数 | 结果 |
| --- | --- | --- |
| `learning-service` | 27 | 全部通过 |
| `mcp-server` | 10 | 全部通过 |
| `gateway` | 62 | 全部通过（未受本次改动影响） |

新增覆盖：AI 导师模型/规则双路径、超长提问、审批前内容未审核拒绝、重复审批拒绝、非法任务编码、MCP 草稿任务创建、长任务状态随审批同步、Medium 槽位满载拒绝/本人复用/Light 降级。

### 2.2 端到端（运行中的 16 容器全栈）

- V5-V8 迁移成功，课程达到 **6 个模块 / 18 个任务 / 3 个项目 / 12 个技能**，覆盖 L1-L6；
- L3/L4/L5/L6 共 11 个新任务用标准答案提交，全部 **100 分通过**（`l6-alert-threshold` 曾因 `round(double precision)` 报错，V8 修正后通过）；
- 项目进度正确显示（订单分析 3/3、质量生产 3/3、资产运维 5/5）；
- AI 导师在无 Key 时返回 `model=rule-based`、`fallback=true`，通过后提示“已经通过”，并落库两条对话；
- 管理端新建 `m3-demo-approval` 草稿 → 审核通过 → MCP `draft_lab_publish` 创建审批单 → 后台批准 → 任务自动发布 → `tasks/get` 返回 `completed / progress=100`；
- `draft_quality_rule` 生成 `not_null` 校验 SQL 草稿与任务记录，未执行任何数据源操作；
- **Medium 槽位实测**：两个学员（admin + slot-test-01）占满 2/2 后，第三个学员 start 返回 **409**；slot-test-01 转做 Light 任务后自动降级，第三个学员重试 **成功获得 Medium 沙箱**——单实例超卖为 0。

### 2.3 构建

- `mvn -f services/learning/pom.xml test` → 通过
- `mvn -f services/mcp/pom.xml test` → 通过
- `npm run type-check` / `npm run lint` / `npm run build` → 通过
- learning/mcp/web 镜像已重建并重启，容器健康。
- 运维注意：重建 learning/mcp 容器后需重启 gateway，否则网关连接池可能持有旧容器 IP，表现为 `/api/learning/**` 持续 503。

---

## 3. 配置与运行

AI 导师（可选）：

```bash
# .env（不要提交）
AETHERIS_AI_API_KEY=sk-...
AETHERIS_AI_BASE_URL=https://api.deepseek.com
AETHERIS_AI_MODEL=deepseek-chat
```

重建 learning-service 后生效。未配置时页面显示“规则导师（未配置大模型）”，功能仍可用。

教师后台：`http://127.0.0.1:5173/learning/admin`，使用管理员账号登录。

MCP 审批演示：

```bash
# 1. MCP 发起审批
curl -b cookies.txt -X POST http://127.0.0.1:5173/api/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"draft_lab_publish","arguments":{"labCode":"l3-rfm-segments","reason":"申请发布"}}}'

# 2. 教师后台批准后查询长任务
curl -b cookies.txt -X POST http://127.0.0.1:5173/api/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":1}}'
```

---

## 4. 未完成项与 M4 差距

| M4 要求 | 当前状态 |
| --- | --- |
| 覆盖 L1-L6 | **已满足**：6 模块 18 任务，L1-L6 全覆盖 |
| 至少 2 个端到端企业项目 | **已满足**：3 个项目（L3 / L4 / L5-L6） |
| 高风险工具直接执行数 = 0 | **已满足**：MCP 无写执行路径，只创建审批单 |
| 长任务状态可追溯率 100% | **已满足**：`mcp_task` + `tasks/get` 同步审批状态 |
| Medium 沙箱资源超卖 = 0 | **槽位层已满足**（单实例实测 2/2 满载 409、释放后恢复）；但 Medium 仍跑在共享 Postgres，Spark 批处理执行未实现 |
| 500-1000 学员并发资源模型 | 未验证；仍是单实例 Docker 部署，需要多副本 + 压测环境 |
| MCP Apps / Skills / 独立 OAuth | 未实现 |
| 主观判分、申诉复核 | 未实现，当前 AI 仅做提示 |
| 多租户 / SSO / K8s / 成本核算 | 未实现，属阶段 3 |

下一步优先级建议：

1. 容量与多副本：把 Medium 槽位升级为数据库级，learning/mcp 多副本部署，50-500 学员梯度压测并出容量报告（M4 最后一个硬指标）；
2. Medium 批处理执行：接入真实 Spark/Flink 或受限批处理队列，让 L6 任务跑在真正的批处理沙箱上；
3. 主观题 AI 初评 + 人工复核 + 申诉流；
4. MCP Apps（任务卡片/审批界面嵌入）与独立 OAuth；
5. AI 复盘报告（基于过程事件与评分证据自动生成周报）。

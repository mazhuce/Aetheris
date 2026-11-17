# Aetheris 学习目标化转型：2–3 天执行目标（2026-11-17）

版本：2026-11-17 规划版。适用：用户明确启动后，在 `C:\Users\yafei\RA1NL0VE\Aetheris-sim`（sim 分支）执行。
依据：现有代码盘点（见各工作包"现状"）、[全项目复核 N01–N23](reviews/2026-10-26-project-gap-review.md)、[上一版执行目标](next-goals-2026-10-26.md)、[前端设计规范](frontend-style.md)。

**战略定位（用户指令，属战略下注，证据级别 D）**：把平台从"企业数据中台操作台"逐步转向"面向求职者的数据开发实训与证据平台"。企业能力不删除，收进管理员/高级视图；学习者看到的默认界面以"学会 → 练会 → 证明会"为主线。

本稿不自动授权提交、推送、部署、现有数据库破坏性变更或真实密钥轮换；沿用上一版目标的证据与资源约束。

---

## 1. 问题与证据

| 论点 | 证据 | 来源 | 级别 | 支持程度 |
| --- | --- | --- | --- | --- |
| 现有 IA 以企业操作为中心，学习者入口被淹没 | 11 个一级菜单中 9 个是企业职能；学习中心子项混入管理员工具 | `apps/web/src/constants/navigation.tsx:52-195` | C（代码事实） | 支持"结构不合理"，不支持"用户流失规模" |
| 学习中心把运营工具暴露给学习者 | `learning-admin`（内容运营）、`learning-mcp`（MCP 调试台）与学习路径并列，无角色门禁 | `navigation.tsx:57-58` | C | 支持越权入口存在 |
| 左上角图标只回首页，没有全局 AI 入口 | `<Link to={homePath} aria-label="返回默认首页">` | `layouts/components/TopNav.tsx:92` | C | 支持"入口缺失" |
| 已有可复用的 AI 与学习底座 | 数据助手（dataMode、会话持久化）、智能问数；learning 后端含 paths/labs/projects/start/reset/submit/hint/tutor/progress/credentials；GradingEngine/SqlGuard/沙箱；V9 课程种子 | `pages/ai/chat`、`pages/ai/chatbi`、`LearningController.java:40-123` | C | 支持"转型可复用，不需重造" |
| 角色只有 admin/viewer 二元，无 learner 语义 | `getCurrentRole()` 返回 admin/viewer | `utils/access.ts:6` | C | 支持"需角色化默认 IA，而非新角色体系" |
| 后端仍有不完善项 | N01–N23 台账，W0–W6 未关闭 | `reviews/2026-10-26-project-gap-review.md` | C | 支持"需取舍，不能全量补齐" |
| 求职者愿意用真实环境练 SQL 并要证据 | 用户战略指令；无外部访谈/埋点 | 用户 2026-11-17 | D（战略下注） | 仅支持方向，规模待验证 |

**不做的后果**：平台继续是"功能齐全但 nobody 练得起来"的中台演示；学习模块孤立，无法形成求职证据闭环。

**待验证假设（不进问题证据）**：目标求职者（应届/转行数据岗）愿意在真实平台完成任务并以导出证据用于求职。验证方式：上线后 7 天埋点 + 3 名目标用户走查（负责人：用户；期限：交付后 2 周）。

## 2. 目标与非目标

**主指标**：新学习者在不求助管理员的情况下，完成"登录 → 选路径 → 完成首个真实判分任务 → 看到进度/凭证"闭环的比例。
基线：未知（无埋点，假设）→ 目标：走查 3/3 完成且埋点闭环率可计算；窗口：交付后 2 周。

**护栏**：
- 企业管理流程零回归（admin 路由、权限、既有验收不破坏）。
- 前端规范守卫（`npm run lint` 含 `check-web-conventions.mjs`）保持绿。
- 不出现假指标/假凭证/假模型回答；AI 预算不超配。
- 主机内存约束沿用：重构建串行、停栈构建。

**非目标（本轮明确不做）**：
- 移动端、企业 GA 全量验收（W6）、引擎物理 HA、计费/付费、外部 LMS 集成。
- 不删除企业菜单与后端能力（只重排/角色化/延期）。
- 不新增后端角色体系（learner = viewer 的默认 IA 语义）。
- 不把 N05/N07–N13 等企业缺口全量塞进本窗口。

**反指标**：学习页点击量上升但判分通过率/闭环率下降（说明内容或判分有问题，不是成功）。

## 3. 用户与场景

| 角色 | 权限 | 核心任务 | 触发 |
| --- | --- | --- | --- |
| 学习者（viewer 默认 IA） | 读可见资产、练沙箱、提交判分、导出自己的证据 | 学会并证明会 | 求职准备、技能练习 |
| 内容管理员（admin） | 任务草稿/审核/发布、审批、运营分析 | 供给高质量任务 | 课程运营 |
| 平台管理员（admin） | 企业全部菜单 | 运维与治理 | 日常管理 |

**学习者主流程**：登录 → 默认首页=学习中心 → 选路径/继续学习 → 进任务（读需求→看元数据→写 SQL→真实执行→判分反馈）→ 失败看原因/提示/问专家 → 通过看进度/凭证 → 导出求职证据包。
**摩擦点（现状）**：首页是企业概览；专家入口不存在；失败反馈依赖模型（无 key 即断）；运营工具混在学习菜单。

## 4. 方案与取舍

| 方案 | 做法 | 成本 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| 维持现状 | 不改 IA/入口 | 0 | 转型不成立 | 不选 |
| 最小可行 | 角色化默认菜单 + logo 抽屉复用 chat 后端 + 规则兜底提示 + 证据导出 | 低 | 专家深度有限 | **选** |
| 完整产品化 | 独立 learner 角色/知识库服务/LMS/独立专家模型 | 高 | 2–3 天不可交付 | 延期 |

取舍写成"用 X 换 Y"：用"复用 chat/ai-agent + 提示层 persona"换"独立专家服务"，换取 2–3 天可交付；当需要独立课程知识库或跨平台专家时改选完整方案。用"viewer 默认 IA"换"新角色体系"，换取零后端角色迁移。

## 5. 工作包与行为规格

### L01 学习者默认信息架构（Must，Day1）

现状：`navigation.tsx` 11 个一级；学习中心混入运营工具；菜单配置仅浏览器本地。
行为：
- viewer 默认顶栏一级 ≤6：`学习中心 / 实训台 / 数据资产 / 智能助手 / 我的求职 /（高级）平台管理`。
- 学习中心子项只留 learner 内容（学习路径、我的任务、项目、凭证）；`内容运营`、`MCP 调试台` 加 `requiredRole: 'admin'` 并移入管理组。
- 企业 9 个一级菜单对 viewer 收进"平台管理"折叠组（admin 展开可见全部）；隐藏≠禁用，直接 URL 仍可达（沿用现有语义）。
- 默认树按角色选择（服务端常量），浏览器本地覆盖优先；菜单配置器可恢复默认。

目标 IA 映射（节选）：

| 现一级 | 学习者视图 | 管理员视图 |
| --- | --- | --- |
| 学习中心 | 保留，子项去运营化 | 保留+运营 |
| 数据开发 | 实训台（SQL/Spark/Flink 作为练习入口） | 保留 |
| 数据资产 | 只读参考（目录/表/指标） | 保留 |
| 智能中心 | 智能助手（专家+问数） | 保留 |
| 工作台/接入/建模/治理/分析/运维/系统 | 收进"平台管理" | 保留 |

验收：
- Given viewer 登录 When 打开首页 Then 首项为学习中心且一级 ≤6。
- Given admin When 打开 Then 企业菜单全部可见。
- Given viewer When 直接访问 `/development/sql` Then 页面可达（隐藏不越权禁用）。
- Given 菜单配置恢复默认 When 刷新 Then 回到角色默认树。
- 键盘 Tab 顺序与焦点可用；小屏一级收"更多"。
回滚：角色默认 IA 开关 `AETHERIS_LEARNER_IA_ENABLED`（.env），关闭即回旧树。

### L02 左上角图标 → 数据开发专家（Must，Day1）

现状：logo 仅回首页；chat 后端有会话/dataMode；ai-sql 等 comingSoon。
行为：
- 点击左上角图标在任意页打开全局"数据开发专家"抽屉；persona=数据开发专家（SQL/数仓/建模/质量/生产化），可问概念、解释报错、给学习建议。
- 自动携带上下文：当前路径/任务 code、可见元数据摘要；dataMode 默认开但只读。
- 无 DeepSeek key：降级为"课程知识库检索 + 规则提示"，明确标注"离线提示，非模型回答"，不伪造。
- 复用 chat 会话持久化与 ai-agent 预算/限流；审计留痕；Esc/焦点陷阱/小屏不超视口。
边界：超时/限流→可重试不重复扣预算；越权→不返回不可见元数据；重复提交→同一请求。
验收：
- Given 任意页 When 点 logo Then 抽屉打开且可输入。
- Given 无 key When 提问 Then 显示离线提示及来源，不出现模型口吻假回答。
- Given 在任务页问"这条 SQL 为什么错" Then 回答引用当前任务验收标准/失败类型。
- Given 连点发送 Then 只消费一次预算。
回滚：`AETHERIS_EXPERT_DRAWER_ENABLED` 开关；关闭后 logo 回首页行为。

### L03 学习闭环可靠性（Must，Day2）

现状：start/reset/submit/hint/tutor、沙箱 TTL job、GradingEngine/SqlGuard 已存在。
行为：
- 判分反馈=失败原因+对应知识点+下一步（规则兜底，不依赖模型）。
- hint 无 key 返回课程内置提示并标注来源。
- reset 幂等（连点只有一个沙箱）；沙箱过期后提交给明确恢复路径（一键重建），不产生脏判分。
- 进度/凭证刷新不丢、不重复发放。
验收：
- Given 沙箱过期 When 提交 Then 明确错误+一键重建，无脏判分记录。
- Given 连点 reset Then 沙箱数=1。
- Given 无 key When hint Then 内置提示+来源标注。
- Given 判分失败 Then 反馈含失败类型与知识点链接。

### L04 课程扩展与岗位映射（Should，Day2）

行为：路径对齐岗位（数据开发/数据分析/数据治理工程师）；路径→模块→任务→对应平台功能；V10+ 迁移种子（只增不改）；难度梯度；每任务可真实判分。写任务沙箱（隔离 writer）若现有只读则列 Could。
验收：每条新路径 ≥6 个可真实判分任务；迁移幂等；学习页显示岗位映射；种子不覆盖用户进度。

### L05 求职证据包（Should，Day3）

行为："我的求职"页汇总进度/凭证/项目交付物；导出 Markdown/JSON 证据包（含真实判分时间/记录标识，不含密码/token）；只读分享页（slug）列 Could；沿用"登记≠部署"语义，不夸大。
验收：导出与页面一致；无敏感凭据；空状态明确；分享页只读且不可越权。

### L06 后端学习适配缺口（Must 子集，Day2–3）

从 N 台账挑 learner-blocking：沙箱并发上限与清理验收、判分 receipt 持久化与防重放（复用固定执行 receipt 语义）、AI tutor 预算与降级、MCP 学习工具只读边界。
验收：组件+真实 PG 正负例；unknown 不推进；撤权后零新增执行。
**延期（记录不实施）**：N05 Flink 生命周期、N07/N08 湖仓维护、N09–N12 血缘/Gold 门禁、N13 语义下线约束——除非被证明阻塞学习闭环。

### L07 企业不完善功能取舍记录（Must 文档，Day1）

把"后端不完善"三分：学习阻塞→L06；企业 GA→延期保留台账；可学习化→作为实训台入口（如 SQL 开发页）。输出决策表（项/分类/理由/owner/期限），写入本稿附录。

### L08 验收、证据与规范（Must，贯穿）

每切片：`tsc --noEmit`、`npm run lint`（含规范守卫）、`vite build`、聚焦测试；有登录凭据时真实浏览器走查，否则明确标注"未做浏览器验收"。检查点追加本稿；不写假证据；重构建串行+内存观测；sim 分支推送 gitee+GitHub。

## 6. 范围与切片（交付顺序）

| 顺序 | 切片 | 用户可见结果 | 依赖 | 验证 |
| --- | --- | --- | --- | --- |
| 1 | L01 | 学习者默认 IA | 无 | 角色走查+键盘 |
| 2 | L02 | logo 专家抽屉 | 无 | 有/无 key 两路径 |
| 3 | L07 | 取舍决策表 | 无 | 评审 |
| 4 | L03 | 可靠学习闭环 | L01 | 正负例+幂等 |
| 5 | L04 | 岗位路径 | L03 | 判分真实执行 |
| 6 | L06 | 后端阻塞修复 | L03 | PG 正负例 |
| 7 | L05 | 证据包导出 | L03/L04 | 导出一致性 |
| 8 | L08 | 证据汇总 | 全部 | 检查点 |

Day1=L01+L02+L07；Day2=L03+L04+L06a；Day3=L05+L06b+L08。每片独立可回滚（开关/迁移只增）。

## 7. 风险与未决

| 假设 | 证据 | 不成立后果 | 验证 | 负责人 | 期限 |
| --- | --- | --- | --- | --- | --- |
| viewer 默认 IA 足够，无需新角色 | 代码二元角色 | 多角色时需后端扩展 | 走查 | 用户 | 本窗口后 |
| 无 key 离线兜底可接受 | 设计 | 专家体验差 | 有/无 key 走查 | 执行者 | Day1 |
| 有测试账号做浏览器验收 | .env 无初始密码 | 只能组件级验收 | 用户提供/重置 | 用户 | Day1 |
| 内存≥构建需求 | 历史 0.9GB | 构建失败 | 停栈+观测 | 执行者 | 每次构建 |
| 求职者要证据包 | 战略下注 | L05 价值低 | 埋点+走查 | 用户 | 交付后 2 周 |

未决：专家是否需要独立课程知识库（owner 用户，Day3 前定）；写任务沙箱是否本轮做（owner 用户，Day2 前定）。

## 8. 上线与度量

**埋点事件**：客户端事件 `expert_question_asked`、`expert_fallback_used`、`portfolio_exported` 经上报接口落库（白名单 + 2KB 上限 + JSON 校验）；服务端派生 `learner_first_lab_passed`（首个通过且去重）、`lab_reset`、`sandbox_expired_recovered` 直接写入 `learning.learning_event`；运营分析页展示近 7 天计数。属性：pathCode/labCode/payload。
**灰度/回滚**：sim 部署；`AETHERIS_LEARNER_IA_ENABLED`、`AETHERIS_EXPERT_DRAWER_ENABLED` 两开关独立回滚；迁移只增不改。
**文档**：本稿检查点 + frontend-style 规范 + 菜单配置说明同步更新。

## 9. 执行规约（沿用）

- 分支 sim；推送 gitee + GitHub；不擅自扩 scope。
- 证据分级：组件/服务聚焦 ≠ 整体验收；旧失败保留。
- 规范守卫保持绿；新页面套 `PageHeader → PageToolbar → DataSurface`，字号走令牌。
- 重构建串行、停栈构建、内存观测；不并行两个以上独立子任务。
- 每完成一片追加检查点（日期/提交/验证/未完成），不提前宣称整体完成。

## 10. 执行检查点

### 2026-11-17 检查点 1：L01/L02 实现完成（组件级）

- L01（提交 `0b1bbe4`）：viewer 默认一级收敛为 学习中心 / 实训台 / 数据资产 / 智能助手 + 平台管理；内容运营、MCP 调试台、菜单配置改为 admin 门禁；`RequireRole` 拒绝时回落到用户 homePath。验证：导航单测 18/18，`tsc --noEmit`、`npm run lint`（含规范守卫）、`vite build` 通过。
- L02（提交 `d0e3f00`）：左上角图标改为按钮打开「数据开发专家」抽屉，字标保留返回首页；ai-agent 新增专家系统提示与 `mode/context` 透传，上下文最长 600 字符；模型未配置/不可用时显示「离线提示（非模型回答）」，不伪造回答。验证：ai-agent 后端 13 项测试（含专家提示词与学习上下文断言）、抽屉组件 2 项测试、tsc/lint/build 通过；镜像已重建部署（`sim-20261116`），web 200、gateway 200、ai-agent healthy，新前端资产 `index-C5Vjg15a.js`。
- 未完成：真实浏览器登录走查（`.env` 无管理员密码）；L03–L08 未开始；写任务沙箱与专家知识库待用户决策。

### 2026-11-17 检查点 2：L03 可靠性修复、L05 求职证据页完成

- L03（提交 `7dee2ef`）：沙箱 reset 的删除与重建改为同一把锁内完成，避免并发重置互删刚建好的 schema；新增「重置替换」与「过期沙箱下次使用时自动重建」两项测试。过期恢复采用自动重建而非报错，属对原验收口径的有意调整，不产生脏判分。验证：`SandboxServiceTest` 5/5；learning-service 已重建部署且 healthy。
- L05（提交 `cc94784`）：新增「我的求职」页与 `/learning/portfolio` 路由，聚合真实进度、能力、项目交付物与凭证，并导出含免责声明的 Markdown 证据包（不含密码/token）。验证：页面 2/2、导出函数 1/1、tsc/lint/build 通过；web 已重建部署，新资产 `index-CtYcur2c.js`。
- 课程现状核对：真实库已有 4 条路径（de-basic 14 个任务、dg-basic 8、ops-basic 7、de-foundation 已归档），岗位映射已存在；L04 的剩余价值集中在补充「数据分析岗」路径与真实样例集，尚未开始。
- 未完成：真实浏览器走查仍缺测试账号；L04/L06 未完成。

### 2026-11-17 检查点 3：沙箱模板表覆盖修复（学习阻断）

- 现状：沙箱只复制 `orders_raw` 与 `order_status_dict`，而 V9 中 7 个任务引用 `users / payments / refunds / shop_dim`，提交时会因缺表导致判分失败。
- 修复：`TEMPLATE_TABLES` 扩到 teach 全部 9 张教学表，并新增覆盖测试；用真实库建立等价沙箱 schema，执行全部 47 个已发布任务的 `expected_sql`，结果 `ok=47 failed=0`。
- 部署：learning-service 已重建部署；既有活跃沙箱在下一次 `ensure` 时自动补齐缺表。
- 未完成：真实浏览器提交判定仍待测试账号；L04 数据分析岗路径与真实样例集尚未开始。

### 2026-11-17 检查点 4：新增数据分析师路径（L04）

- 新增路径 `da-basic`「数据分析师入门」：2 个模块、6 个任务、5 个技能节点、1 个端到端项目「电商经营分析周报」；任务覆盖指标口径（FILTER 成功率）、分组对比（品类/城市）、趋势环比（LAG）、注册转化（LEFT JOIN 漏斗）与退款率（去重计数）。
- 迁移 V11 已验证：flyway `11 da basic curriculum` 成功；6 个任务、5 个技能、6 条 lab_skill 关联、项目行均存在；6 条 `expected_sql` 在等价沙箱 schema 中执行 `ok=6 failed=0`。
- 已知约束：新任务只使用 `teach` 教学表；后续新增任务必须同时写 `lab_skill`，并在等价 schema 中跑通 `expected_sql`。
- 未完成：真实浏览器走查（缺测试账号）；真实样例集（L09）仅预留未实施。

### 2026-11-17 检查点 5：L06 学习适配审计（MCP 边界、导师预算）

- MCP 学习工具审计：当前只读工具（路径/任务/提交）与 `draft_lab_publish`、`get_approval_status` 等审批入口，未发现直写课程内容的通道；发布仍需管理员在内容运营审批。
- AI 导师预算：新增单任务提问上限（默认 20，环境变量 `AETHERIS_LEARNING_MAX_CHATS`）；超限后不再调用模型，降级规则导师并在回复中明示。`TutorServiceTest` 4/4。
- 判分凭证：提交、评分与执行证据按 submission 绑定，重复提交计为新 attempt，未发现跨提交复用通道，本轮不改。
- 部署：learning-service 已重建；未完成真实浏览器走查。

### 2026-11-17 检查点 6：L09 真实样例扩展点第一步

- 新增 `learning.dataset_manifest`：记录 code/version/table/row_count/checksum/license/source/PII 复核状态，已登记 6 个合成教学集并计算内容校验和。
- `learning.lab` 新增 `dataset_ref`；**仅单表任务**自动绑定 `<table>@1`（27/53），多表任务保持 NULL，等待后续多引用设计。
- 任务详情新增「样例数据」展示；真实脱敏样例后续按同一清单增加版本与绑定，不改任务编号。
- 验证：V12 迁移成功；6 条清单校验和已生成；learning/web 重建，healthy，web 200。
- 未完成：真实样例数据本体、多表任务的 datasetRef、真实浏览器走查（缺测试账号）。

### 2026-11-17 检查点 7：多表任务的样例数据引用

- V13 补齐 `metric_dict / daily_arrival / table_asset` 三个治理类清单，确保已发布任务的表全部在册。
- `dataset_ref` 迁移为 `dataset_refs`（JSONB 数组）：只有任务引用的全部表都在清单内才绑定；**53/53 已发布任务全部完成绑定**，多表任务如 `de-l1-04` 绑定 `orders_raw@1 + order_status_dict@1`。
- 任务详情展示多个「样例数据」引用；后端编译、前端 tsc/lint 通过；learning/web 重建 healthy，web 200。
- 未完成：真实脱敏样例数据本体与真实浏览器走查。

### 2026-11-17 检查点 8：样例数据来源在任务详情可见

- 任务详情新增 `datasets` 清单：展示每个引用的 code@version、许可、来源行数与 PII 复核状态；`datasetRefs` 保留为兜底。
- 后端编译、前端 tsc/lint 通过；learning/web 重建 healthy，web 200。
- 未完成：真实脱敏样例数据本体与真实浏览器走查。

### 2026-11-17 检查点 9：专家上下文引用任务验收标准

- 专家抽屉在任务页会加载任务详情，把「验收标准 / 最近提交状态 / 最佳得分」并入上下文；后端系统提示已声明优先围绕上下文回答。
- 该改动闭合 L02 验收中的「在任务页提问时引用当前任务验收标准/失败类型」；组件测试 2/2（含上下文断言），tsc/lint/build 通过；web 已重建，200。
- 未完成：真实浏览器走查（缺测试账号）。

### 2026-11-17 检查点 10：学习页加载失败态与重试

- 学习中心：加载失败不再误显示「还没有已发布的学习路径」，改为可重试错误态；刷新失败保留既有内容。
- 任务页：加载失败不再误显示「任务不存在或尚未发布」，改为可重试错误态。
- 验证：两个页面测试 2/2，tsc/lint/build 通过；web 已重建，200。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 11：学习页成功路径回归

- 学习中心：新增成功路径测试（路径标题、完成进度渲染）。
- 任务页：新增提交成功路径测试（提交启动 SQL → 展示「得分 86 · 已通过」与判分摘要）。
- 验证：两页测试合计 4/4；tsc/lint/build 通过；web 已重建，200。
- 专家抽屉补充「离线失败 → 重试成功」恢复路径测试（组件测试 2/2）。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 12：学习 UI 关键交互回归

- 学习中心：新增「切换路径 → 重新加载详情」测试。
- 任务页：新增「规则提示展示」与「无模型时导师标记为规则导师」测试。
- 求职证据页：新增导出按钮测试，校验 Markdown 含免责声明且不含 password/token/cookie。
- 验证：learning 相关三页测试 10/10；lint 守卫通过。仅测试改动，无部署变更。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 13：验收关键路径补充

- 任务页：新增判分失败反馈测试（得分 40 · 未通过 + 规则提示展示）。
- 专家抽屉：新增重复提交锁测试（请求在途时连点只发送一次）。
- 求职证据页：新增首次使用空状态测试（能力/项目/凭证三处空态）。
- 验证：三文件测试 12/12；tsc/lint 通过。仅测试改动。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 14：管理员数据样例审计

- 新增 `GET /api/learning/admin/datasets`（内容管理员限定）与内容运营「数据样例」页签：展示数据集、版本、表名、行数、许可、来源、PII 复核、状态与备注。
- 真实脱敏样例后续只需按新版本登记清单，即可在学习任务与运营页同时看到来源与许可。
- 验证：后端编译、前端 tsc/lint/build 通过；learning/web 重建 healthy，web 200。
- 未完成：真实样例数据本体、真实浏览器走查。

### 2026-11-17 检查点 15：求职证据细化到任务级

- 求职证据页新增「已完成任务」区块：路径、任务、等级、最佳得分；数据来自各路径详情的真实判分结果。
- Markdown 证据包同步新增任务级表格；空状态明确。
- 验证：页面与导出测试 5/5；tsc/lint/build 通过；web 已重建，200。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 16：数据样例漂移校验

- 新增 `GET /api/learning/admin/datasets/verify`（内容管理员限定）：逐项重算行数与内容校验和，与清单比对。
- 内容运营「数据样例」页提供「校验一致性」按钮，行级展示一致/行数漂移/校验和漂移；表名仅允许 `teach.*` 安全模式。
- 验证：等价 SQL 校验 9/9 数据集行数与校验和全部匹配；后端编译、前端 tsc/lint/build 通过；learning/web 重建 healthy，web 200。
- 未完成：真实样例数据本体、真实浏览器走查。

### 2026-11-17 检查点 17：样例清单支持多版本

- 修正清单表主键：由 `code` 升级为 `(code, version)`，同一数据集可登记后续真实脱敏版本而不覆盖旧版本。
- 实测：同一 `teach.orders_raw` 可新增 v2；重复 `(code, version)` 被唯一约束拒绝；探针数据已清理。
- 运营页表格行键改为 `code@version`；tsc/lint 通过；learning/web 重建 healthy，web 200。
- 未完成：真实样例数据本体、真实浏览器走查。

### 2026-11-17 检查点 18：数据样例审计页回归

- 内容运营「数据样例」页新增组件测试：校验通过显示「一致」、校验和不匹配显示「校验和漂移」。
- 验证：测试 2/2；tsc/lint（235 文件）通过。仅测试改动。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 19：我的求职升为一级入口

- 按 L01 目标 IA，将「我的求职」从学习中心二级提升为一级模块（子项「求职证据」指向 `/learning/portfolio`）；viewer 一级顺序为 学习中心 / 实训台 / 数据资产 / 智能助手 / 我的求职 / 平台管理。
- 验证：导航与求职页测试 19/19；tsc/lint/build 通过；web 已重建，200。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 20：回滚开关与运行手册

- 补齐目标文档承诺的两个构建期回滚开关：`VITE_LEARNER_IA=false` 退回企业全量菜单；`VITE_EXPERT_DRAWER=false` 图标恢复返回首页。开关默认开启，关闭后需重建 web。
- 新增「运行与验证手册」：启动/重建、前后端检查、样例清单、漂移校验、服务端参数与证据位置。
- 更新交付状态总表至检查点 20。
- 验证：tsc/lint/build 通过；web 重建，200。
- 未完成：真实浏览器走查。

### 2026-11-17 检查点 21：真实浏览器验收（学员核心路径）

- 测试账号：本地新建 `learner-e2e`（viewer，随机密码存于 `configs/security/private/e2e-learner.password`，gitignored；删除命令：`DELETE FROM auth.auth_user WHERE username='learner-e2e'`）。已通过网关真实登录（200 / viewer）。
- 浏览器实测（Chromium + CDP，1440×900）：
  - 学员一级导航实测为 学习中心 / 实训台 / 数据资产 / 智能助手 / 我的求职 / 平台管理，未出现运营工具；
  - 学习中心渲染 4 条路径与真实进度（1/35、平均 100 分、能力雷达更新）；
  - 任务页创建沙箱 `sb_u30`，提交标准 SQL 得到**真实判分「得分 100 · 已通过」**，显示校验和；
  - 求职证据页展示真实进度/能力/项目/凭证，导出按钮可用；
  - 左上角图标打开「数据开发专家」，未配置模型时显示「离线提示（非模型回答）」；
  - 首轮采集控制台错误 0。
- 证据：`artifacts/learning-pivot-20261117/`（learning-center、career-portfolio、lab-graded、expert-drawer 截图与 acceptance.json）。
- 未完成：管理端导航的浏览器验收（需 admin 账号，未获授权创建）；证据包文件下载未实际落盘验证；模型在线回答未验证（未配置密钥）。
### 2026-11-17 检查点 22：提示、导师与导出下载浏览器验收

- 真实浏览器继续验证学员闭环：
  - 任务页「获取提示」返回「第 1 级：先对齐任务目标」；
  - 「AI 导师」问答返回规则导师回复，界面标注「规则导师（未配置大模型）」；
  - 求职证据页触发导出，实际下载 `aetheris-portfolio-2026-11-17.md`（1742 字节），包含标题与免责声明，不含 password/token/cookie；
  - 本轮控制台错误 0。
- 证据：`artifacts/learning-pivot-20261117/` 新增 `lab-hint.png`、`lab-tutor.png`、`acceptance2.json`、`portfolio-export-sample.md`。
- 未完成：管理端浏览器验收（需 admin 账号）、模型在线回答（未配置密钥）、真实样例数据本体。
### 2026-11-17 检查点 23：真实样例版本可由管理员登记

- 新增 `POST /api/learning/admin/datasets`（内容管理员限定）：登记教学表的新版本，服务端自动计算行数与内容校验和；校验编码必须等于表名、表必须存在于 `teach` schema、版本为正整数、许可与来源必填。
- 内容运营「数据样例」页新增「登记新版本」表单（表名/版本/许可/来源/PII/备注），提交后自动刷新列表。
- 验证：组件测试 3/3；后端编译、tsc/lint/build 通过；learning/web 重建 healthy，web 200。
- 未完成：真实样例数据本体、管理端浏览器验收、模型在线回答。
### 2026-11-17 检查点 25：管理端浏览器验收与配置修复

- 修复 Compose 未传递 `AETHERIS_LEARNING_ADMIN_USERS` 导致管理端接口全部 403 的接线缺陷；默认仍为 `admin`，可用环境变量扩展。
- 使用临时 `admin-e2e`（验收后已删除，白名单已恢复为 `admin`，临时凭据文件已清理）完成浏览器验收：
  - 管理端一级菜单 11 项（学习中心/数据接入/数据开发/数据建模/数据治理/数据资产/数据分析/智能中心/我的求职/运维中心/系统管理），新建入口可见；
  - 内容运营加载 53 个已发布任务、1 次真实提交、100% 通过率；
  - 「数据样例」页签展示 9 个数据集；点击"校验一致性"后 9 行全部显示「一致」，提示"全部数据集与清单一致"；
  - 本轮控制台错误 0。
- 证据：`artifacts/learning-pivot-20261117/` 新增 `admin-learning-home.png`、`admin-content-ops.png`、`admin-dataset-audit.png`、`acceptance-admin.json`。
- 未完成：模型在线回答、真实样例数据本体、写任务沙箱决策。
### 2026-11-17 检查点 26：学习事件埋点上线

- 新增 `learning.learning_event` 与 `POST /api/learning/events`：仅允许低敏事件（专家提问、离线兜底、证据导出等），2KB 上限与 JSON 校验。
- 专家抽屉在提问与离线兜底时上报；求职证据导出时上报；运营分析新增「学习事件（近 7 天）」表。
- 浏览器实测落库：`expert_question_asked` 1 条、`expert_fallback_used` 1 条、`portfolio_exported` 2 条；控制台错误 0。
- 测试：前端 10/10、后端事件测试 2/2；learning/web 重建 healthy。
- 未完成：`learner_first_lab_passed` 等其余事件暂由既有数据推导；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 27：服务端派生事件与沙箱重建缺陷修复

- 新增服务端派生事件：首个任务通过（按学员去重）、沙箱重置、过期恢复，均写入 `learning.learning_event`。
- 真实浏览器触发"重置沙箱"时发现并修复严重缺陷：`sandbox_instance.schema_name` 全表唯一导致重置/过期恢复后无法重建沙箱；V16 改为仅 active 行唯一（部分唯一索引）。修复后重置成功创建新 active 沙箱并记录 `lab_reset`。
- 浏览器实测事件：`expert_question_asked` 1、`expert_fallback_used` 1、`portfolio_exported` 2、`lab_reset` 1；控制台错误 0。
- 测试：`SandboxServiceTest` 6/6、`LearningEventRepositoryTest` 3/3；learning 已重建 healthy。
- 未完成：首个通过事件的真实浏览器复验（需新学员首个通过）；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 28：首个通过事件分支回归

- 新增 `SubmissionFirstPassEventTest` 2 项：首次通过写入 `learner_first_lab_passed`；后续通过不重复写入。
- learning 全量单测 **41/41** 通过。仅测试改动。
- 未完成：首个通过事件的真实浏览器复验（需新学员）；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 30：事件接口完整性与校验修复

- 客户端事件白名单收紧为 `expert_question_asked / expert_fallback_used / portfolio_exported`；`lab_reset`、`sandbox_expired_recovered`、`learner_first_lab_passed` 只能由服务端派生写入，禁止 HTTP 伪造。
- 修复非法 JSON 被静默吞掉的问题：payload 使用严格解析，非法 JSON 返回 400（原先会在写库时变成 500）。
- 测试：`LearningEventControllerTest` 3/3；learning 全量 **44/44**；learning-service 重建 healthy。
- 未完成：模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 29：过期沙箱恢复真实库验证

- 将测试学员的 active 沙箱 expires_at 置为过去，随后通过浏览器点击"开始实验"触发恢复：
  - 旧沙箱行标记 `dropped`，新建 active 沙箱（id 4）；
  - 写入 `sandbox_expired_recovered` 事件；
  - 控制台无错误。
- 事件余额：`expert_question_asked` 1、`expert_fallback_used` 1、`portfolio_exported` 2、`lab_reset` 1、`sandbox_expired_recovered` 1。
- 未完成：首个通过事件的真实浏览器复验（需新学员）；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 30：事件接口完整性与校验修复

- 客户端事件白名单收紧为 `expert_question_asked / expert_fallback_used / portfolio_exported`；`lab_reset`、`sandbox_expired_recovered`、`learner_first_lab_passed` 只能由服务端派生写入，禁止 HTTP 伪造。
- 修复非法 JSON 被静默吞掉的问题：payload 使用严格解析，非法 JSON 返回 400（原先会在写库时变成 500）。
- 测试：`LearningEventControllerTest` 3/3；learning 全量 **44/44**；learning-service 重建 healthy。
- 未完成：模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 24：数据集登记后端校验回归

- 新增 `DatasetManifestRepositoryTest` 5 项：编码与表名一致性、teach schema 限制、缺失表/许可拒绝、行数与校验和计算后写入、漂移标记。
- learning 全量单测 **36/36** 通过。仅测试改动。
- 未完成：真实样例数据本体、管理端浏览器验收、模型在线回答。
### 2026-11-17 检查点 25：管理端浏览器验收与配置修复

- 修复 Compose 未传递 `AETHERIS_LEARNING_ADMIN_USERS` 导致管理端接口全部 403 的接线缺陷；默认仍为 `admin`，可用环境变量扩展。
- 使用临时 `admin-e2e`（验收后已删除，白名单已恢复为 `admin`，临时凭据文件已清理）完成浏览器验收：
  - 管理端一级菜单 11 项（学习中心/数据接入/数据开发/数据建模/数据治理/数据资产/数据分析/智能中心/我的求职/运维中心/系统管理），新建入口可见；
  - 内容运营加载 53 个已发布任务、1 次真实提交、100% 通过率；
  - 「数据样例」页签展示 9 个数据集；点击"校验一致性"后 9 行全部显示「一致」，提示"全部数据集与清单一致"；
  - 本轮控制台错误 0。
- 证据：`artifacts/learning-pivot-20261117/` 新增 `admin-learning-home.png`、`admin-content-ops.png`、`admin-dataset-audit.png`、`acceptance-admin.json`。
- 未完成：模型在线回答、真实样例数据本体、写任务沙箱决策。
### 2026-11-17 检查点 26：学习事件埋点上线

- 新增 `learning.learning_event` 与 `POST /api/learning/events`：仅允许低敏事件（专家提问、离线兜底、证据导出等），2KB 上限与 JSON 校验。
- 专家抽屉在提问与离线兜底时上报；求职证据导出时上报；运营分析新增「学习事件（近 7 天）」表。
- 浏览器实测落库：`expert_question_asked` 1 条、`expert_fallback_used` 1 条、`portfolio_exported` 2 条；控制台错误 0。
- 测试：前端 10/10、后端事件测试 2/2；learning/web 重建 healthy。
- 未完成：`learner_first_lab_passed` 等其余事件暂由既有数据推导；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 27：服务端派生事件与沙箱重建缺陷修复

- 新增服务端派生事件：首个任务通过（按学员去重）、沙箱重置、过期恢复，均写入 `learning.learning_event`。
- 真实浏览器触发"重置沙箱"时发现并修复严重缺陷：`sandbox_instance.schema_name` 全表唯一导致重置/过期恢复后无法重建沙箱；V16 改为仅 active 行唯一（部分唯一索引）。修复后重置成功创建新 active 沙箱并记录 `lab_reset`。
- 浏览器实测事件：`expert_question_asked` 1、`expert_fallback_used` 1、`portfolio_exported` 2、`lab_reset` 1；控制台错误 0。
- 测试：`SandboxServiceTest` 6/6、`LearningEventRepositoryTest` 3/3；learning 已重建 healthy。
- 未完成：首个通过事件的真实浏览器复验（需新学员首个通过）；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 28：首个通过事件分支回归

- 新增 `SubmissionFirstPassEventTest` 2 项：首次通过写入 `learner_first_lab_passed`；后续通过不重复写入。
- learning 全量单测 **41/41** 通过。仅测试改动。
- 未完成：首个通过事件的真实浏览器复验（需新学员）；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 30：事件接口完整性与校验修复

- 客户端事件白名单收紧为 `expert_question_asked / expert_fallback_used / portfolio_exported`；`lab_reset`、`sandbox_expired_recovered`、`learner_first_lab_passed` 只能由服务端派生写入，禁止 HTTP 伪造。
- 修复非法 JSON 被静默吞掉的问题：payload 使用严格解析，非法 JSON 返回 400（原先会在写库时变成 500）。
- 测试：`LearningEventControllerTest` 3/3；learning 全量 **44/44**；learning-service 重建 healthy。
- 未完成：模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 29：过期沙箱恢复真实库验证

- 将测试学员的 active 沙箱 expires_at 置为过去，随后通过浏览器点击"开始实验"触发恢复：
  - 旧沙箱行标记 `dropped`，新建 active 沙箱（id 4）；
  - 写入 `sandbox_expired_recovered` 事件；
  - 控制台无错误。
- 事件余额：`expert_question_asked` 1、`expert_fallback_used` 1、`portfolio_exported` 2、`lab_reset` 1、`sandbox_expired_recovered` 1。
- 未完成：首个通过事件的真实浏览器复验（需新学员）；模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 30：事件接口完整性与校验修复

- 客户端事件白名单收紧为 `expert_question_asked / expert_fallback_used / portfolio_exported`；`lab_reset`、`sandbox_expired_recovered`、`learner_first_lab_passed` 只能由服务端派生写入，禁止 HTTP 伪造。
- 修复非法 JSON 被静默吞掉的问题：payload 使用严格解析，非法 JSON 返回 400（原先会在写库时变成 500）。
- 测试：`LearningEventControllerTest` 3/3；learning 全量 **44/44**；learning-service 重建 healthy。
- 未完成：模型在线回答、真实样例、写沙箱待外部输入。
### 2026-11-17 检查点 31：学习事件统计回归与口径核对

- 内容运营「运营分析」新增学习事件表组件测试（展示近 7 天事件与计数）。
- 真实库口径核对（7 天）：`expert_fallback_used` 1、`expert_question_asked` 1、`lab_reset` 1、`portfolio_exported` 2、`sandbox_expired_recovered` 1。
- 验证：admin 测试 4/4；lint 通过。仅测试/文档改动。
- 未完成：模型在线回答、真实样例、写沙箱待外部输入。

## 11. 预留：学习者真实数据范例（L09，按需启动）

现状锚点：`teach.orders_raw` 为合成教学数据（`V2__teaching_dataset.sql`，刻意保留 6 类脏数据），沙箱通过 CTAS 复制，不直接写源表。

预留扩展点（尚未实现，启动时按此接线）：

1. **数据集清单**：为 `teach` 增加版本化清单（code/version/rows/sha256/license/source/PII 复核状态），迁移只增不改。
2. **任务绑定**：lab 定义新增 `datasetRef`（如 `teach.orders_raw@v1`），沙箱与判分按引用解析，不在代码里写死表名。
3. **隔离与许可**：真实样例必须脱敏后落入 `teach`（或独立 `teach_sample`），禁止直连生产库导出；附来源与许可说明。
4. **体积上限**：单集 ≤ 5 万行且 ≤ 20MB，按 dt 分区；沙箱复制后记录执行时长。
5. **验收**：同一任务对合成集与真实样例集都可判分且口径一致；切换样例不改变任务编号与学员进度；无 PII 泄漏。

第一批建议：电商订单脱敏样例（orders/users/products/refunds），先做 1 个集 + 1 个任务验证闭环，再扩量。

## 12. L07 后端缺口取舍表（本窗口）

| 缺口 | 分类 | 处理 | 理由 | owner/期限 |
| --- | --- | --- | --- | --- |
| N03 / N14 正式安装与制品链 | 企业 GA | 延期，保留台账 | 不阻塞学习闭环 | 用户 / 本窗口后 |
| N05 Flink 生命周期与物理 HA | 企业 GA | 延期 | 学习任务基于沙箱 PG，不依赖 Flink HA | 用户 / W6 |
| N07/N08 湖仓维护与 Schema 提交 | 企业 GA | 延期 | 与学习任务无直接依赖 | 用户 / 本窗口后 |
| N09/N10/N12 血缘、Gold 门禁 | 企业治理 | 延期 | 企业治理范围 | 用户 / 本窗口后 |
| N11 分页真实 PG 证据 | 企业 GA | 延期（实现已完成） | 大规模/浏览器证据属 W3/W6 | 用户 / W6 |
| N13 语义资产下线约束 | 企业治理 | 延期 | 企业治理范围 | 用户 / 本窗口后 |
| N16–N18 / N21–N22 浏览器验收 | 学习相关 | 随 L08 补走查 | 学习页复用同一对象/错误态边界 | 执行者 / 本窗口 |
| N19/N20 请求所有权 | — | 已完成 | 见 2026-11-16 检查点 | — |
| 沙箱过期恢复、reset 幂等、hint 兜底、AI 预算降级 | 学习阻塞 | 进入 L06 | 直接决定学习闭环可用性 | 执行者 / Day2–3 |
| 专家课程知识库 | 产品决策 | 本轮不做 | 先用平台元数据+课程上下文 | 用户 / Day3 前定 |

## 13. 交付状态总表（截至 2026-11-17）

| 工作包 | 状态 | 证据 | 剩余 |
| --- | --- | --- | --- |
| L01 学习者默认 IA | 已完成（学员+管理端浏览器验收） | `0b1bbe4`、`576ac84`；检查点 21/25 浏览器实测 | — |
| L02 专家抽屉 | 已完成（离线路径浏览器验收） | `d0e3f00`、`a83ba26`；后端 13 项、组件 2 项测试；检查点 21 抽屉实测 | 模型在线回答（未配置密钥） |
| L03 闭环可靠性 | 已完成（真实判分+沙箱恢复浏览器） | `7dee2ef`、`06bc197`、`b805c37`；检查点 21/22/27/29 真实提交、重置与过期恢复 | — |
| L04 岗位路径 | 已完成 | `24f92df`；V11；6 条标准 SQL 通过 | 真实浏览器走查 |
| L05 求职证据包 | 已完成（页面+下载浏览器验收） | `cc94784`、`4990d89`；页面/导出 5/5；检查点 21/22 渲染与下载 | — |
| L06 学习适配 | 已完成（审计级） | `1d65336`；MCP 只读审计；导师预算 4/4；learning 单测 31/31 | 真实 PG 撤权/大规模场景 |
| L07 取舍表 | 已完成 | `4ddfd77` 第 12 节 | — |
| L08 验收与证据 | 进行中 | 本稿检查点 1–31；`artifacts/learning-pivot-20261117/` | 在线模型、真实样例 |
| L09 真实样例扩展点 | 已完成（除真实数据本体） | `2d6e689`、`7115c54`、`ef69395`、`3ffe693`、`5fa17db`、`f884a7c`、`0a8b9fd`、`ed51ac3`；清单 9 条、53/53 绑定、漂移校验、图形化登记 | 真实脱敏样例数据本体 |

**未关闭的关键依赖**：DeepSeek 密钥（在线回答）、真实样例数据的来源与许可（L09 后续）、写任务沙箱（产品决策）。

## 14. 运行与验证手册（sim 本机）

```bash
cd C:/Users/yafei/RA1NL0VE/Aetheris-sim

# 启动 / 重建（.env 中的 COMPOSE_FILE 会自动带安全安装覆盖）
docker compose up -d
docker compose build learning-service web && docker compose up -d learning-service web

# 前端检查
cd apps/web && npm run type-check && npm run lint && npm run build

# 后端学习服务单测
mvn -s scripts/maven-mirror-settings.xml -f services/learning/pom.xml test

# 数据样例清单
docker exec aetheris-postgres-1 psql -U aetheris -d aetheris \
  -c "SELECT code, version, row_count FROM learning.dataset_manifest ORDER BY code"
```

数据集漂移校验：登录内容运营 → 学习中心 → 内容运营 → 数据样例 → 「校验一致性」；或执行第 16 检查点中的等价 SQL。

回滚开关（构建期，修改后需重建 web）：

- `VITE_LEARNER_IA=false`：退回企业全量一级菜单（默认开启学习者 IA）。
- `VITE_EXPERT_DRAWER=false`：左上角图标恢复为返回首页（默认为专家抽屉）。

服务端可调参数：`AETHERIS_LEARNING_MAX_HINTS`（每任务提示额度，默认 6）、`AETHERIS_LEARNING_MAX_CHATS`（每任务 AI 导师提问上限，默认 20）、`AETHERIS_LEARNING_ADMIN_USERS`（内容管理员用户名列表，默认 admin）。

证据位置：本稿检查点 1–31；前端测试位于 `apps/web/src/**/*.test.ts(x)`；后端测试位于 `services/learning/src/test/java/**`。
# Aetheris 审查整改与可靠交付验证

目标来源：用户提供的 `Aetheris_mimo.md`、`Aetheris_deepseek.md`，以及本轮目标附件中确认的六阶段计划。报告内容是待核实证据，不是独立授权。起始 HEAD：`f22a32c836fdc7eab275d15a89919525ed3e3f19`。

开始前工作区仅有 `.playwright-mcp/`、`docs/reviews/` 未跟踪内容；保留这些内容。实施不自动提交、推送或部署。真实密钥轮换、已有数据库的高影响修改，在方案、备份和回滚准备完成后确认。隔离测试不连接现有业务数据。

## 完成条件与证据

2026-10-19 新目标接续：用户已确认继续阶段0—5完整目标。本轮新增S11业务数据源状态、S12公开连接信息保护及S13稳定UID归属；不能沿用下述305/176、102项等历史分包结果宣称新源码整体通过。完整脚本在独立Python3.12环境332项通过，Spark脚本21项执行通过、4项跳过；旧完整脚本失败与Python3.11/PS7崩溃仍保留，根因未明。两项MinIO引用仍保持strict release非零阻断。现有业务数据库、真实密钥、提交和部署未改变。

阶段0证据校正：114源数据库部署gate run `ea13d31a596b400689c7525a319756ed` 已实际完成1正23负，report SHA256 `8403e6887619ef620a15b9e94b34581a2ee0807a10f80832b96a7f206cb024fc`，源摘要、数据库未变与独立资源清理均通过；不是九应用实际部署。R1新包 `output/audit-r1-pin-integration/run-20261019-r1-cross-os-ee92034a7ecd/delivery.json` SHA256 `38736563c62ddfd5043f4dd74e394c2c48a6de97879fa096510f802bbe5206dd`，8份真实历史附件原字节与两种Git换行设置已验证，历史审计范围明确不等于当前验收。未运行原生Linux CI或完整镜像构建。

2026-10-19 接续校正：完整目标已恢复 active，以下均为分包结果。114源部署gate的1正23负已通过，后续基础镜像/Compose变更后的新指纹回归正在执行；九schema角色336项、transfer/quality六应用31项仍属109源历史。当前纯key五应用在metadata SQL/P04变更后已通过102/102项，表三行中文、指标SUM60及权限/生命周期负例、普通与全局审计均覆盖；此前92项报告保留。workflow SQL五应用114源37/37覆盖普通用户授权、明确批准、业务writer实际INSERT、严格日期、撤权和真实Quartz一次触发，但早于后续P04改动，最终须复验。各次源码末指纹匹配、独立自有资源清理全0。cron ABA两代generation已先红后绿，不能回填历史manual报告。最终平台总验尚未完成。

- 真实三应用身份链：[identity-chain-acceptance.md](identity-chain-acceptance.md)，run `07eeb0e3ecb94d32ae7c4626d126f302`，34/34；实际JAR、PG105迁移、Redis、撤权/禁用/AV及128字符requestId落库，容器/自有JVM/临时key已独立核对清理。它是本机JAR分段证据，未声称容器化全平台或正式发布producer。
- metadata：最近305项/60suite全绿，SQL包2项PG和P04另2项PG均有独立证据；USER、transfer/quality/SQL及published-key分包已接。当前纯key五应用102项覆盖最新P04源码；workflow SQL37项早于P04，保留并待最终冻结回归。静态复核见 [identity-v2-review.md](identity-v2-review.md)。列表故障仍fail-closed空集，不一概返回503；P03已发现大列表批量上限缺口，待修。
- auth：最近176项/26suite全绿；新增metadata-only的query_audit.view能力，未扩大may-manage。published-key窄断言及workflow/ingestion批准者query/write、quality批准者query已接，SQL必须query+write。API资产data_service窄生命周期授权已先红后绿；Gateway粗门46项/5suite通过，backend audience为metadata。当前源码已参与published-key102项，workflow SQL37项为P04前结果。
- AI：62项全绿，实际Tomcat DeferredResult请求200/完成前禁用或过期403/非法dispatch拒绝；原USER经不可变快照传入worker，每次工具请求重验。metadata/auth目前为签名HTTP替身、ChatService为本机替身；没有付费模型调用，也未把P05历史真PG当作本次重跑。
- 脚本最近完整263项为112源历史（`output/audit-scripts-112-green.log`）；新增工具与当前全量正在接续，不能用旧263项代表当前。protocol160已在隔离Temurin21通过，今日主机160项亦已重新执行；Servlet60单测与两项真实Tomcat/PG IT通过，收尾竞态失败及诊断保留。
- ingestion/quality：最近来源包分别66项/16suite、76项/20suite通过。新增V10封存schedule与binding两代generation，真实PG先红后绿，旧NULL封存不回填、不恢复执行；SQL变更后114源整链尚未总验。109源manual来源→metadata→auth→业务JDBC六应用31项是历史分段证据，不能外推到新的cron边界。
- serving：当前115项/23suite及真实PG1项通过，稳定UID/AV、并发轮换、配额/单次准入及领取、旧NULL拒绝、24h后每replica有界清理已验证。归档见 `output/audit-serving-published-key-package/20261018T140340Z-eb0b2a6c/acceptance.json`，已参与五应用纯key92项；后续metadata改动仍需链路回归。
- workflow：SQL source期限/CAS包223项/43suite及真实PG1项通过，归档 `output/audit-workflow-fixed-package/sql-deadline-20261018T143852Z-aef5ead9`，acceptance SHA256 `a307edb4ec3f4da5aa1de037021fcbb2814cdc55019eece611902e48a7814282`。DB固定T+60租约、生产client按source期限减5秒等待、迟到success/failed不能覆盖unknown；24h为受控时钟证据，未运行24h真实任务。实际SQL五应用37项另见下文；pipeline/quality child/Spark/Flink固定主体接线仍未完成。

完整目标覆盖阶段 0–5，不能用某一模块通过测试代替完成。证据必须说明源码/工作树、run、制品、脱敏配置、时间、逐项结果及附件摘要。历史报告仅能证明历史执行。状态为：待复现、已确认、已修复待验证、验证通过、不成立、延期处理。下表中“待处理”代表已确认或尚需核实的工作仍未完成，不代表接受风险。

1. 安全：权限、身份、平台数据库隔离、SQL/JDBC 和出站边界。
2. 正常功能：登录、元数据、质量、接入、加工、发布查询。
3. 状态：日期、会话并发、异步响应、调度恢复、不确定执行结果。
4. 数据与交付：全新安装、版本升级、备份恢复、数仓对账。
5. 发布：失败、缺失、过期、错版本或被替换的证据必须阻断。
6. 清理：隔离测试的账号、表、制品、容器与目录核对。
7. 文档：每条原报告发现都有结论、证据和处置；未验证能力不标为完成。

## 工作包与原报告映射

M = MiMo；D = DeepSeek。原报告无单独 ID 的 Low 项在“卫生”行列出。阶段 4 的增强项若需要延期，必须记录理由及残余影响；阶段 1–3 的阻断项不能靠延期完成目标。

| 包 | 原发现 | 内容 | 状态 / 待完成验收 |
| --- | --- | --- | --- |
| S01 | M C-1/C-2a/C-2c/C-2d；D P0-2 | SQL 结构、词法、函数、只读/写入统一契约 | 分项验证通过；先红基线 26 例中 21 失败，修复后单元及真实隔离PG通过；Hive真实兼容与最终冻结版本复测待完成 |
| S02 | M C-2b、M-4；D P2-6/P1-8 | JDBC 各入口参数、URL、TLS、嵌入式和出站目标 | 分项验证通过；最终URL/参数允许清单、云元数据等危险目标拒绝、DNS全部地址检查、HTTP拒重定向、Runner精确origin已实现。网络层DNS rebinding隔离、TLS与真实引擎仍待验收 |
| S03 | D P0-1/P1-3 | 平台连接隔离、查询与加工账号、按服务数据库权限 | 分项验证；legacy managed隔离，最新109源/九schema角色真实PG336项通过，部署入口强制roles/live receipt与Compose DB identity。业务READ/WRITE分离、加密/禁用与revision+CAS已真实PG通过；固定transfer也已用普通metadata runtime和真实业务reader/writer验收。109源gate 1正23负通过并独立清理；九完整应用启动、九类业务引擎及存量实施未完成 |
| S04 | M C-4；D P1-2 | auth 可信身份 | 单元/MVC验证通过；uid/主体/活动状态检查与伪造拒绝；真实跨服务部署待总验 |
| S05 | M H-1；D 5.10 | system 用户/配置鉴权和 DTO，settings 门禁 | 单元/MVC验证通过；角色、直连与DTO扩展字段负例已覆盖，真实部署待总验 |
| S06 | D P1-1 | 纯 API Key 经网关执行已发布 API | 当前v2五真实应用114迁移链102/102通过，保留92项并新增普通查询审计10项；表3行中文、指标SUM60、无JWT/Cookie、权限/域/field/禁用/AV/配额/轮换等均按约。P04之后源码末指纹匹配、独立资源清理全0。历史旧协议25项与前轮92项保留；浏览器及未来最终冻结版本仍待总验 |
| S07 | M M-1/M-2/M-5；D P1-7/P2-3/P3-3 | 路径规范化、资源读写契约、批量授权、列表过滤 | 分项实现/验证中；viewer显式授权、末grant撤销、domainCode、AND范围与列表过滤。本人资源权限提示及采集/质量/加工/治理/数据集/看板入口已接入。治理MVC部分更新必填冲突先红后修复；完整定义委托仍须补S08与datasource.write，暂限管理员 |
| S08 | D P1-3；M M-8 | 内部服务身份、audience、操作/资源范围、质量任务主体 | 实施中；protocol160已真实Temurin通过，Servlet60+2真实Tomcat/PG通过。最近分包：Gateway46、auth176、system46、governance42、metadata305、AI62、ingestion66、quality76、serving115、workflow223，各包独立指纹不等于同一冻结版总验。metadata transfer/quality/SQL共用receipt及物理worker池；当前纯key五应用102项、P04前SQL五应用37项通过，transfer/quality六应用31项为109源历史。pipeline/quality child/Spark/Flink固定主体仍未接，整体不可部署 |
| S09 | M M-3；D P1-5/P1-6 | refresh 并发、登录容量/过期/IP维度 | 后端CAS/重放与前端独立标签页协调单测通过；真实浏览器/服务验收待完成 |
| S10 | D 模块审查/P3-4 | 禁用/降权/改密及时失效，passwordHash 防御 | 独立access_version与SQL生成/并发单测通过；真实PG生命周期、版本迁移待总验 |
| S11 | 本轮复核新增；关联M M-4/D P1-8 | 数据源严格active准入、独立保存前测试、执行前重读 | 分项验证通过；有效RED26项20失败/0error后，状态/固定执行/health53项通过，实际PG另1项通过：active实际连接、8种非active零新物理连接、重新启用恢复成功。transfer执行前重新读取两端状态/定义。保留首次17个夹具错误及后续整合失败，不代表最终平台通过 |
| S12 | M H-1/M-4；D 5.10/P1-8 | 数据源公开DTO、历史URL/参数/错误秘密保护、保存边界 | 分项验证通过；DTO21项、保存35项及既有Controller/Credentials11项通过。历史URL/params/host/db秘密隐藏、固定错误、嵌入式路径参数拒绝；无关编辑保留已隐藏且运行期拒绝的历史脏配置，明确替换/空串清除。前端14项与完整tsc通过，实际浏览器及最终整合待验 |
| S13 | 本轮复核新增；关联P04/P05/P09 | 同名账号复用下的历史、会话、审计与通知稳定UID归属 | 已确认实施中；auth允许删除后重建同名用户，不能以用户名恢复旧数据归属。AI实际PG6项中4项有效RED、2项正例通过；metadata历史8项回归待执行。新写绑定可信UID；旧NULL不猜绑admin或当前同名用户；本人SQL复制/会话复用保留。通知/告警与实际auth整链仍待接续 |
| R01 | D P0-3/P2-9；M Low 时区 | 显式业务时区、Clock、严格日期、SQL占位符 | 实现与模块单测通过；注入Clock、scheduledFireTime、闰日和占位符二次校验；手动执行默认日期改读服务端日历，Flink快捷执行使用服务端默认；最终整合复测中 |
| R02 | D P2-7/P1-9 | ingestion/quality 持久化调度补偿 | 单元和12项真实PG/Quartz JobStoreTX验收通过；同事务generation、回滚、并发、重启与pending恢复；全平台部署待完成 |
| R03 | 两报告可靠性建议与旧审查线索 | 未知 SQL 执行结果、自动重试、running 崩溃恢复 | 分项验证通过；workflow unknown停止盲重试；ingestion/quality租约、过期unknown保留并发槽、worker fencing、管理员对账和quality隔离同事务已通过真实PG。刚补最近结果摘要与前端对账流程，最终复测中 |
| R04 | M H-9；D P2-10 | 聊天会话乱序、Spark重叠轮询、卸载更新 | hook和API回归通过；会话epoch、串行/合并轮询及AbortSignal接入，真实浏览器验证待完成 |
| E01 | M C-3/H-2；D 测试与CI建议 | 当前运行证据绑定、strict一致性、非通过退出码 | 行为测试通过；v2显式context/manifest绑定当前源码、运行、制品、配置和时间；需冻结源码后重跑八类真实producer形成正式当前证据 |
| E02 | M C-3 | 九类数据源结果、业务错误与清理聚合 | 行为测试通过；完整性、重复/缺项、200+error、实际行数、清理失败反例已覆盖；真实九类重跑待完成 |
| E03 | M H-3/H-2；D 测试建议 | PostgreSQL/Flyway/Mapper/事务与跨服务测试 | 分项真实验证；SQL、调度、unknown恢复、备份、权限迁移、角色部署gate、AI围栏与四服务API Key链已运行真实PG。S06发现并修复真实JSONB Mapper actions/scope解码缺陷。全业务与最终冻结版本跨服务验收仍待完成 |
| E04 | M H-4；D 运维建议 | 备份与恢复二进制链路 | 模拟和真实一次性PG验收通过；中文/二进制/NULL、约束/索引/序列、clean恢复、摘要、坏归档拒绝与临时文件清理；不代表已有备份已核验 |
| E05 | M H-8/M-15；D P3-5 | 干净构建与历史迁移升级 | 当前109源/九schema新装、重启0pending及1正23负部署gate通过；历史metadata V27→V28、存量writer为NULL和serving V3兼容/歧义拒绝证据保留。存量实库仍需inventory与明确路径，不盲目repair；当前六应用clean构建、独立角色启动与固定执行业务整链31项通过 |
| E06 | M H-7/M-14 | 数仓语法、跨层对账和维度/脱敏断言 | 真实隔离Spark分项通过；原语法/金额两缺陷实证先红，当前21断言+11损坏数据拒绝+原表复验通过。模拟按分计算，运费/订单优惠明确0、实付缺失单列，邮箱/电话掩码。现有Thrift/JDBC/平台SQL守卫链仍待整合验收 |
| E07 | D P1-4/P2-13 | 恢复三份NUL文档、成熟度和契约文档校正 | 原三文档已恢复；刚补先红后绿反例，检查器现包含未提交且未忽略的Markdown，76份当前文档完整性/核心链接通过。旧48份结果仅检查tracked清单，不能覆盖新增契约；持续内容仍须最终复查 |
| E08 | M H-10/M-6/M-7；D P2-11 | CI成功与制品绑定、shell参数、签名和供应链 | 部分验证；同commit最新CI attempt、11制品digest与签名清单、全量迁移source、部署/回滚同DB gate及13种Action官方SHA已实现。18种基础引用中16种锁定实际官方index摘要，2种MinIO原tag在Quay及同发布者DockerHub均401，严格检查保持非零阻断。5种1panel默认值经root决策切至官方同tag，字节等价未证实、完整构建待验；12工具测试与4组Compose非镜像配置等价通过。云端CI未运行 |
| E09 | M H-5/H-6/M-9/M-10 | 私密state/备份隔离、Git/Docker规则、凭据轮换 | 分项准备/验证通过；S08新ignored目录生成10个workload+Gateway USER key拒绝覆盖。新增AES离线重加密/回滚SQL准备工具、16工具测试及17项真实PG/Java兼容/备份恢复/CAS验证通过，见datasource-credentials-contract。已有数据库与真实密钥未修改，实际轮换仍按具体环境/回滚方案另行确认 |
| P01 | M M-17；D P2-5 | 共享身份协议/方言、核心DTO/契约 | 分项实现；security-protocol统一密码学与传输契约，当前160例通过；新增固定auth客户端和有限USER audience中继，Servlet60例通过。历史10应用编译不代替后续整合复测。共享库不代替应用授权；核心DTO与其他契约继续逐项补齐 |
| P02 | M M-18/M-19；D P2-10 | 渐进lint、错误/状态兜底、关键页面拆分 | 首包分段验证通过：4文件typed ESLint、6项实际注入门禁、type-check/build/lint通过，自动polling拒绝显式处理。最新全量133通过/1工具30s超时，全部128业务断言通过；仅该工具时限调90s后6项通过，未声称134项整套重跑通过。CI已接npm run lint；页面后续治理及浏览器未验，无全站格式化 |
| P03 | M M-16；D P2-1/P2-2/P2-3/P2-4 | 分页、批量授权、连接测量/预算 | 已确认待实施：table OPAQUE单次超过1000批量上限会被客户端转为空集；metric先截500再授权导致后续资源不可见；published候选草稿域与已发布快照域可能漂移。分页须稳定游标/授权后结果/无假总数与明确503；池化和缓存依测量决策，限定容量与失效边界 |
| P04 | M M-12/M-13；D P2-8/P2-12 | requestId、审计身份/来源、告警、保留策略 | 首包验证通过：管理员全局/普通用户本人汇总经独立auth能力判定；普通查询记录可信requestId、安全固定错误和无高基数标签的落库失败计数。metadata305/auth176、PG两IT、五应用新增10项实际验证通过。审计/执行历史保留、告警投递与其余链路关联仍待实施；详见permission-contract |
| P05 | D ai-agent模块审查 | 服务端对话历史、工具权限、循环/时限/token预算、固定安全评估集 | 分项验证通过；只接收末问题、本人持久化历史、DB运行围栏、手动有界工具循环/token/总时限、有界worker和可观察取消。49单测+7真实PG通过；真实跨服务工具授权仍归S08，前端显式取消及供应商费用硬上限未声称完成 |
| P06 | M M-11 | Windows造数脚本编码、硬编码测试账号/清理逻辑 | v2模拟协议分包12方法/100原生PS5.1/7子进程通过；Cookie会话、显式业务源/独立批准与cron绑定、Ordinal比较和未知状态拒绝已实现，原SQL/定义语义保留且Docker SQL仅供审阅。`output/audit-seed-script-package/v2-final-definition-ordinal-20261019T102348Z-3c12136f/acceptance.json` SHA256 `713bb7a62e880d7ab0287b2d1fcc3d176180f4a4aca6a069b3b6e3f518a3abac`。未接触真实API/DB；完整320脚本后有一次PS7 CLR异常，精确单例复验通过但根因未知，原失败未抹去，待整合复验 |
| P07 | M M-12/M-15；D P3-5 | 删除/回填/迁移的审计与可恢复性 | 待核实；不把全库软删除、迁移缺号或普通Flyway非幂等直接判为缺陷 |
| P08 | M M-20；D P3-1/P3-2 | 包体、死资源、死方法/头参数、临时文件 | 待核实；确认引用与用途再清理，增加合理体积预算 |
| P09 | M Low；D P3-1/P3-5 | 版本/Wrapper、默认配置、query_history隐私、API-key前缀、logout计时器、401回跳、aria、prune/启动器/固定sleep、崩溃日志 | 待核实；逐项记录证据与处置，不批量移除未知日志或用户内容 |

## 实施约束

- 共享文件唯一写入者；最多两名独立执行代理。主代理维护此台账、SQL/数据边界和总体集成。
- 先确认边界再变更；测试发现新的兼容要求则补入契约。无法验证的项目继续保持未完成。
- 外部部署、实际密钥与现有数据库变更前准备可审阅方案。不得将临时隔离测试的通过外推为现有生产环境通过。
- 最终独立复核逐行核对此台账、目标附件和实际证据，所有阶段必须有状态及验收记录。

## 当前验证记录

2026-10-19 20:40 接续（均为局部分包，不作为最终冻结发布证据）：

- S11/S12：`output/audit-datasource-admission/run-20261019-v1/archive-state-dto/delivery.json` SHA256 `696e07bcf9f82a1c7d38d76abf4e7ddc82a3701f48bab4e7dcf711bab4855af0`，109项相关回归通过，287个main class与实际PG测试摘要一致；DTO独立归档manifest SHA256 `52cb455d5440d464236270b84ba448dffc396c3b0d14c28ab4c7a22de5655185`。完整metadata410项当时5失败：P03四个真实列表缺陷，以及旧quality wire夹具未标active；后者已修夹具待重跑，未虚报整模块全绿。
- 实际PG：run `fd7c9c0ab9dd48998195f6198cf9d3ab`，report SHA256 `7a5079dbeadb03dc58f021b44b6f2614d2f4808d86790f8c5180e19a1faf9713`，1项SQL/角色/状态IT通过，31份metadata Flyway与真实Driver计数断言；源末摘要一致，四项清理及独立资源核对通过。未触及现有业务数据库。
- datasource前端：`output/audit-datasource-admission/run-20261019-v1/frontend-focused.log` 14项通过，`frontend-typecheck.log`完整tsc退出0。历史连接配置隐藏后可明确清空或替换，nullable host搜索不再异常；JSDOM保留AntD伪元素告警，未做实际浏览器验收。
- 完整脚本：`output/audit-resume-20261019-v4-python312/script-validation.json` SHA256 `2920f54740e74bc846ee977a85dac690521b6735c9e83fb5bc251a8f1059139c`。独立工作区Python3.12.14、固定PyYAML6.0.2/cryptography48.0.1与显式JDK21下，scripts332项全部通过无跳过，Spark25项中21项执行通过、4项跳过，源码摘要始末一致。原Python3.11完整回归0x80000003及PS7/coreclr崩溃保留，未确认根因；精确单例复验成功不能消除原失败。未更改系统Python、安全配置或现有服务。
- S13 AI有效RED：`output/audit-ai-owner/run-20261019-v1/postgres-db4a18a5191c40e2bdf1bd066f49a233/report.json` SHA256 `c6df2372251f1ad7b392d00ce81df59407f3dad3faebaf0cae2cd47ee9131490`，实际PG/MyBatis/短事务+Ed25519/MVC+离线模型6项中4失败、0error、0skip；同名新UID可读取/继续/删除旧会话，固定UID的新AV正常、旧proof失效两项通过。会话存取的活跃身份由fixture提供，尚非实际auth删除重建整链。此前首轮缺真实Mapper bean的6error夹具失败独立保留，未算漏洞证据；两轮自有JVM/容器/volume/端口均已清理。
- S13 metadata有效RED：`output/audit-history-owner/run-20261019-v1/unit-c97a09cc55974a7f94f6a0bd78f609ef/report.json` SHA256 `4ec48e2a9d4d21297c09630663304d41e4c202ef742aa50154ec3eed6174fad7`，历史8项中7失败，JDBC日志3项全部失败，均0error/0skip。真实MyBatis/H2与Ed25519/MVC证明旧同名历史可读/删/清、旧NULL猜归属、新写缺UID/requestId和历史错误秘密回显；同UID新AV保留本人SQL的正例通过。三个日志测试确认驱动原文进入日志，不能靠控制器DTO解决。旧quality wire夹具修正后4项通过，全部源摘要始末一致；当前PG个人审计归属反例在接续。
- S13 AI后续GREEN：普通16suite共63项通过，`unit-d58d97c53c184655a3a2dc39783c3634/report.json` SHA256 `2950192458791894989342c8abdc0f162661a5e2e00135ce2ade52f4077dcfe4`；实际PG run `a55d91c49acb4f5f860d62891dcf7c67` 的9+11共20项通过，report SHA256 `74d84717098477c22acdf9cb42943a9650ada22e3ee889717d17bd9ae913adc8`。两者均在`output/audit-ai-owner/run-20261019-v1`；源/49main class摘要一致、四清理与独立资源全0。194份原字节归档manifest SHA256 `e4d92c4418d93b3267b4caba35e1fb6d7412d63b6c4e13b301fec4fa4999303d`。V1/V2实际旧行→V5、NULL隔离/UID不可变、新写约束、run CAS/取消/迟写与UID窗口均覆盖；PG报告limits留有旧“Six”文字，实际固定套件9+11=20已在新manifest纠正说明，未改历史报告。仍非实际auth删除重建或完整发布验收。
- 116迁移后完整脚本：新增AI V5与metadata V32后，固定门禁显式由114改为实际116，没有改为动态放行。`output/audit-resume-20261019-v5-116-python312/script-validation.json` SHA256 `8f632382d500afef2af66bccc8686c6e7e5041e34f5c548fd7f6a6112bf0d5b3`；scripts332项全通过、Spark21项执行通过/4跳过、脚本源摘要末匹配。后续system V8尚待实施，届时需明确更新门禁与回归，不把116记录改成未来结果。

2026-10-19 恢复时补记前轮已完成但尚未回写的证据（历史分包时间不改写为今日执行）：

- workflow SQL五应用：`output/audit-workflow-sql-chain/run-20261018-v2/report.json`，run `b702ca3dcc81405b948781705b47c5e7`，37/37通过，SHA256 `c71562302e91fec442a70b6fc4e20adb67c5414d20f5f55d23cc7e703bf1016e`。全新114源PG、Redis及auth/system/metadata/workflow/gateway实际JAR；viewer actor显式execute授权、另一viewer批准者query/write、未批准409零写、严格闰日/非法日期、撤权零新instance、actual writer INSERT和真实一次性Quartz均已验。全部源码末指纹一致、5项内建清理及独立PID/端口/Docker标签核对全0。首轮TaskView夹具错误run原样保留。可选十应用profile未运行，不外推为九服务并发或Spark/Flink/pipeline通过。
- metadata SQL adapter：`output/audit-metadata-fixed-sql-package/final-f21faaee32cb4389bf5182373b35e1fd/delivery.json`，296单测/59suite及真实PG2项通过，SHA256 `eec24c38ac57f31e695e0155fe0b02e9e33e0a16b7c30609c282c6c1fe3182da`。固定SQL使用同一有界物理池，source/receipt双期限与独立writer，DS revision/撤权零JDBC、commit丢结果unknown不重投和24h受控时钟均覆盖；实际PG只执行metadata31迁移，不把114份source指纹当作全部执行。JAR SHA256 `aad1a8e645773a41f032188af4a1aa9682079ca730421af6bd41403ecb829cbc`。
- 114源部署gate：`output/audit-database-deploy-gate/run-20261018-114-v2/report.json`，run `5f0da19a5ef5498e9dcb3ec5599b2f21`，1正23负通过，SHA256 `084ac82f17ea866c6b41561b5cad9421ffde5af2a330816ccfa9a0596aab89ba`；九schema全部114迁移、DB未变/凭据退休及独立清理通过。后续E08修改Compose默认镜像，故此结果须按最终fingerprint重跑，不代表实际全平台构建或云端发布。
- 纯key v2五应用：`output/audit-published-key-v2/run-20261018-v6/report.json`，run `ef26519341b847bfb6c0d1fb11387c8b`，92/92通过，SHA256 `339339f9500761694e4ef081611da7793cddefe3bc10c39632a47efe3dc9d91d`。114迁移；表3行中文、metric SUM60、授权域/field限制、生命周期、配额、轮换、禁用/AV及6次真实owner/key/admission/requestId审计均覆盖。所有失败夹具报告保留；其后metadata deadline/errors/SQL adapter变更，所以最终冻结版仍需回归。
- E08实际基础镜像清单：`output/audit-base-image-package/registry-7520db1dbbf345c08e88dc657760f281/acceptance.json`，SHA256 `7bc3ffb288c28b5baf9c0085739ef0842438089ebddb8ed9a2a52dd0b40d8677`。结构扫描含未跟踪runner Dockerfile及4个build ARG，共18引用/46默认来源；16官方index实际核验、2 MinIO tag未解决。严格offline检查exit1，显式诊断模式可报告部分成功，不能当作发布通过；12工具测试、4组Compose等价及实际错误digest反例通过。没有pull镜像层、构建、启动或推送。
- E09主密钥准备：`output/audit-datasource-key-rotation/run-20261018-v1/report.json`，run `7fa05e72ea3a41a1a7cb1878f2a82564`，17/17真实PG/Java兼容检查通过，SHA256 `972d3f8d26851366921e83dbe58f4fee919a6c728a7788a0bb8d6a1faaa6ddc9`。一次性fixture实际pg_dump/pg_restore、Java旧加密→Python重加密→Java新解密、整事务失败回滚、重放/新行/错误DB/过期revision拒绝、回滚旧cipher且revision+2均覆盖；16工具测试亦通过。只执行所需6份metadata fixture SQL，不等于Flyway全量。现有数据库/真实master key未读取或修改。
- 恢复时资源检查：2026-10-19本机15.71GiB总内存、约0.50GiB可用，现有14个非audit Aetheris容器运行。它们不是本轮创建，不停止、不将旧容器健康状态作为新源码证据；先串行完成前端和轻量检查，再评估隔离JVM/PG回归容量。

2026-10-18：安全回归先红：`JdbcSqlSafetyRegressionTest` + `SqlWriteGuardSafetyRegressionTest` 共 26 例、21 失败；仅新建的进程内 H2 临时表，不涉及现有数据库。原多行 `EXPLAIN ANALYZE DELETE` 被真实 JDBC 接受。此前全仓clean test已通过；后续独立审查又补7例失败反例（Unicode/quoted函数、声明位置、STORED BY与source只读），修复后metadata全量通过；最终整合版本仍需再次验收。

已执行分项记录（报告目录被忽略，不含真实凭据；后续源代码变更后还需冻结版本的正式证据）：

- 当前109源部署gate：`output/audit-database-deploy-gate/run-20261018-109-v2/report.json`，run `54041a1912fa49d7a28ca5b3a0610a5a`，1正23负全部符合预期；迁移凭据退休、临时容器/网络/卷与私密目录清理均通过，另有独立清理核对。报告SHA256 `744068957f2cd1127dc8d421035d0d6375c022c688a286385fc1d366c50ea072`。
- 当前109源运行角色/封存辅助函数：`output/audit-fixed-helper-roles-20261018/report.json`，run `9bb3b9924fc34d31bdc102c3b3d347cc`，336项真实PG检查通过；修复严格函数签名目录及本schema runtime EXECUTE，仍拒绝PUBLIC/cross-schema/SECURITY DEFINER和未知函数。覆盖九schema重启0pending、三来源封存/心跳/immutable及metadata receipt unknown、权限负例。报告SHA256 `b7ffd842555b62d8c6794ad1047acd422183fc8dd25defeb6395d3e2bf0ce2f7`，清理和凭据退休通过；不代表九完整应用启动。
- 四schema交叉封存+真实PG manifest→Java解析：`output/audit-fixed-execution-storage/run-20261018-parser-pg/report.json`，run `3e065cc08cc74de59f21b8db5b6ad624`，4项IT通过；中文SQL/null默认值hash、actor/definition交叉绑定、唯一receipt、unknown不重投和workflow active attempt均覆盖。SHA256 `6493202d03477ec1332937d067a4489cad9e3e9c0af7d4396fa6c65c9fb040c7`；来源结束指纹一致与独立清理通过。之后新增Java接线仍须新链验收。
- 当前protocol160例真实Temurin21/Linux运行：`output/audit-protocol-runtime/9ad700c9aa594fdaacb63211a249fa3a/report.json`，run同目录，160项0失败/错误/跳过，容器/bundle清理通过并独立核对。SHA256 `702d7833e4cad8d75d4edc5f6181aeb4b948f793d7da64650887eef5ef47a1ca`。脚本当前249项全绿见 `output/audit-scripts-v2-expanded-green.log`；auth当前161项全绿见 `output/audit-auth-quality-approval-green.log`。
- metadata transfer包：`output/audit-fixed-transfer-package/transfer-20261018T130340Z-e29f9abc`封存63份证据，archive manifest SHA256 `3bdce16f17dafda522a199cc19d76069e6bc5544de203aa40d88af1d4b4de969`。原acceptance SHA256 `5774ccd74569fa0c90942ba24aa4119eae3d22298834b2322d52ed03085a30fb`；237单测及真实PG run `28275bc306294610b3bc03e57bd962ec` 的1项IT通过，涵盖双副本一次JDBC、queued撤权/DSrevision零写、commit/完成写失败unknown、重启不重投与durable queued恢复；清理通过。auth/source仍为真实签名HTTP替身，后续quality通用adapter改变源码后须重新回归。
- quality来源包：`output/audit-quality-fixed-source-package/delivery.json`及同目录两轮真实PG报告已从target复制保留，最终75单测+2个隔离PG IT通过；source SHA256 `1c10a5a14ccd8d5940b9b03398444d45ad3509676403dd634be06be00ce7a8be`。审批、cron CAS、claim/seal、unknown阻槽、旧定义表名对账及quarantine/result原子提交已覆盖，metadata实际整链未由此包证明。
- serving普通USER包：`output/audit-serving-v2-user-package/delivery.json`，100项/19suite全绿；source SHA256 `72ff8cd8dd9241f53244fc9e40841c1304816ad2f2116d0453c9c55a4c767f1b`。旧HMAC/plain属性RED2后拒绝；原USER转发、batch≤500、401/403/503、错aud/过期/dispatch及签名HTTP丢响应不重发已验证。API Key不能借此进入通用USER查询；resolve/key资产专用链仍待实施。
- workflow普通USER包：`services/workflow/target/workflow-v2-delivery.json`，194项/37suite全绿；source SHA256 `03233315a673ba16d5b7e543ddbd14b79033e0eaff04d9a0cd6e7e099c687bbd`。仅6个main文件接线，原执行器/SQL/POM/config hash保留；实际应用/nonce DB/固定执行未验。六应用固定执行脚本已编写，工具安全定点27项通过（含原identity脚本），尚未启动实际六应用。
- Servlet当前真实复验：`output/audit-nonce-store/e32ed49394ec4aadb9b97a77e11c93d7/report.json`，60普通单测及PostgresNonceStoreIT/TomcatProofWireIT各1项通过，报告SHA256 `8201d661db12bb9b17ade076acfe2fd5320d1b6d863375744207e345d77bcce2`。双Tomcat 24次重复仅1进入业务、23签409；header断连/body408、零严重异常、线程/端口/temp关闭与PG/网络清理通过。独立核对三次run的唯一标签资源与本模块JVM均0。先前失败run `6f559cccbaed4e9c9dfaa6d0bee5786b`、`3e29b8b936764795b2c9d62e5f56b663`保留；第二轮诊断证明assert先读active=2而线程快照已active=0且全在queue等待，属于收尾采样竞态。SessionValidator仍本地替身，不外推为真实auth业务联验。
- API-key v2窄授权：`output/audit-auth-published-key-package/delivery.json`，当前auth171项/26suite全绿，先红路由记录保留；source SHA256 `b81823fef85bcd5c69c835479848c8904ccee0bf943b3b7c13a2956b7c6dc033`。仅metadata+无USER可断言固定owner/asset/target，真实权限引擎证明domain/table匹配、field/row/未知范围不视作ALL；账户/grant仍替身。待实现完整契约见 [api-key-v2-contract.md](api-key-v2-contract.md)，尚无key成功主张。只读复核还确认rotate忽略CAS可能并发双签发、metric审计误把sourceTableId记datasourceId，纳入后续S06/P04必要修复。
- auth 100、system 27、gateway 41项第一包单测通过；后追加路径规范化和serving精确路由后两模块回归通过。
- workflow 132、ingestion 23、quality 29项第二包单测通过。
- `output/audit-sql-boundary/report.json`：run `8551df05ced34d93a07d2c20e3d38e59`，独立审查修复后的PG SQL/角色/metadata Flyway通过且cleanup passed。
- `output/audit-scheduler-boundary/report.json`：run `0a7af38f6f4243c5b6d5b3f9856d3bb8`，最新V7下12项真实PG/Quartz JobStoreTX回归通过且cleanup passed。
- `output/audit-postgres-archive/report.json`：run `89cfa48b2e144b7ebe4711ad9e2b643c`，实际备份/恢复7类断言通过且cleanup passed。
- CI/脚本第二包`scripts/tests` 151项通过，含4项实际Bash入口mock部署/回滚；6份YAML、R1矩阵、镜像清单、Bash语法通过。未在云端执行新workflow。
- 出站策略两模块各23例与真实回环HTTP 3例通过；JDBC、Flink云元数据目标和SQLServer serverName旁路先红后修复。Spark生产构造context复测通过。
- `output/audit-execution-recovery/report.json`：run `b6fda04ea17045cba6351ae51ae8f23c`，旧running迁移、租约fencing、unknown并发阻断、并发人工对账、摘要事务、定义快照/删除保护与quality隔离一致性通过且cleanup passed。
- `output/audit-database-roles/report.json`：run `509cf62d7f304cd3b9e21c5c8dfd2035`，九服务313项权限检查、93份迁移、存量dump/restore与owner/ACL迁移、历史V3及未知history反例、live收据与被篡改inventory拒绝、迁移账号退休和cleanup通过；主代理已独立核对93项源hash与当前一致。未按新账号启动九个完整应用。
- 权限包auth108、workflow169、governance28、serving86、metadata196、ingestion37、quality40全量通过；新增cron/启用要求execute已覆盖。metadata两class损坏经clean test重建恢复，不是源码缺陷。其后根代理新增本人权限提示/MVC通过；治理更新4例MVC首轮2失败、修复后全量32通过，workflow新增业务日历后全量170通过。
- auth V7→V8真实迁移一例通过：run `02499a636f5148a19923657166212588`，显式grant/自定义角色保留、viewer默认授权撤销与cleanup通过，详见权限契约。
- 前端权限提示、委派入口、业务日期、unknown保留/详情乱序、Flink串行轮询及READ/WRITE表单整合后，全量37文件108例通过。凭据表单定点11例、type-check与生产build亦通过。jsdom不代表真实浏览器验收；antd/charts大chunk警告仍存在。
- P05 AI全量49例及真实PG7例通过；run `da289d844fd84d18a8ff3bb9a71180de`，cleanup passed，详见`ai-governance-contract.md`。本轮没有付费模型调用。
- S06历史失败均原样保留：真实启动发现ServingSecurity构造器注入错误，真实auth JSONB Mapper未读取actions/scope，以及metadata 403被误映射500，均先红后修复。最终run `f6595f6b775f4d2cade03699c0d6851c`，25/25检查通过，5条query audit主体均为key owner viewer、异常主体0。仅data_service.execute+table.query两条窄grant即可成功；无JWT/cookie。auth111单测、真实Mapper3例通过；临时JVM/容器/网络/jar/私密文件清理有独立复核。详见`api-key-chain-contract.md`。本报告源指纹早于后续POM/Docker依赖变动，不能代替未来S08冻结链验收。
- 部署gate历史93源run `049d14e1a2da4060a6bc228c5dd2b393`，1正23负及11项工具安全测试通过；全93迁移/9runtime/fresh receipt/实际compose_identity均实际运行。10种Spring/Hikari/JVM覆盖在live校验前拒绝；所有负例无准入记录、原DB指纹未变，清理通过。详见`database-deploy-acceptance.md`。
- S08库core115、配置6、transport24合计145例通过。原始GET/POST断线wire反例观测3次请求，要求JDK21启动`disableRetryConnect=true`及`redirects.retrylimit=1`后同一用例观测2次通过；该历史包当时尚未验证Temurin，后续152例已实际运行（见下）。测试制品SHA `17910e08f40560a77ac49b226b0b5055a9dd521145bda19afe73f8093bf65845`已离线安装本机cache，10应用全reactor compile通过；该历史包当时未构建应用镜像或切换服务安全协议。
- S08独立复核确认首次投递的USER头未与workload操作绑定。新增2个实际wire检查先红，再将必需`userProofSha256`加入签名/ExpectedRequest，无用户头也绑定emptybytes hash；新增两有效USER替换、移除/插入、格式与旧缺字段向量拒绝。现全量152例通过，当前JAR/cache SHA均为`117eeff2cd82211935f58a34533912b959ad9be90daf064d88b195cd96423be1`。早期向量保留作拒绝回归，新向量另存，不回填旧报告。安装准备约束见`protocol-installation.md`。
- 同一152例已在真实缓存Temurin21.0.12.1+1-LTS/Linux amd64、无外部网络容器全部通过：run `422e41459d064008aae94720fd4a25b6`，源码/产物hash末尾一致、容器/bundle清理通过；6项工具安全测试通过。仅证明指定库/JRE与wire场景，不代表应用Docker构建或业务链路。详见`protocol-runtime-acceptance.md`。
- security-servlet Workload/Direct两filter先完成51例mock Servlet+实际密码学验证；添加PostgresNonceStore后55 unit通过。首轮真实PG暴露purge锁定语句额外需要UPDATE权限，去掉该锁定而不扩大权限，增加并发限批清理后run `2525372c5eca4edcadc6d5c39f7dace8`真实IT通过：64次两个store唯一claim、24次两个filter接真实PG仅一次chain并签409、无UPDATE角色可维护、重建store仍拒重放及24h保留。清理全部通过，详见`nonce-store-acceptance.md`。此处filter仍为Spring mock，真实Tomcat另行接续；九schema迁移/应用注册尚未接入。
- 脚本第二次整合202例通过，随后nonce与数仓验收脚本继续新增，应在本轮文件冻结后重跑；不沿用202例为后续变动的最终结论。
- E06 run `5f2433a8981945b9b0115aba58b72c48`，实际缓存Spark3.5.6/Linux amd64隔离执行33项通过，原始f22a32c两个失败复现；16表均有真实行，GMV/明细/缺失金额桥精确到分，跨闰日/月末，源码末尾一致，容器/bundle清理有独立核对。前三次容器/工具失败保留；6项工具行为测试通过。详见`warehouse-acceptance.md`，未连接现有WSL/HDFS或业务数据。
- Gateway真实临时Docker构建通过，实际包内security-protocol的30个class与本机已测class逐字节相同、无测试fixture且JVM禁止重试参数存在。没有启动网关；提取容器、临时镜像标签和app.jar副本已删除。`output/audit-gateway-image/report.json`保留镜像/包/log摘要；源码摘要仅构建后采集，明确不作为正式冻结release producer证据。
- S03最终run `6278832285a94f95b3d475f3424d84d1`真实PG通过；缺writer写入先红后修，查询/采集源只用reader、execute/接入目标只用writer、两密码不回显、明确禁用NULL、改名新密码及两类旧实体竞态均验证。root复核发现更早完整entity快照会恢复旧密码，真实RED `1e2f6f733eaf43c78536caa397a2dc6b`后补revision+CAS，普通metadata207例通过。V28最终SHA `636b6b2e13fe0343b8fead33b28968354d5c6184ef1e74127c9bb44f978412b4`，94源；容器与owned JVM清理通过。详见`datasource-credentials-contract.md`。
- Servlet真实联验最终run `41aef74e759548f1ae2b26a93d880ac2`，56单测+原nonce IT1+新Tomcat/PG IT1通过，脚本8例通过。双Tomcat24次同nonce仅1次业务、23签409；实际超时曾因对已提交response reset而500，root修复后header EOF/body408且无severe异常。端口、owned线程/temp与PG资源清理通过。详见`servlet-wire-acceptance.md`；测试SessionValidator不是实际auth/RBAC，ASYNC/INCLUDE/ERROR仅映射检查，未外推成业务闭环。

- 94源gate独立run `6a18eda05e6e411696c634f42eea5bb1`，1正23负、11工具安全测试通过；source/DB未变、实际compose identity与独立资源清理通过。此后S08新增九schema nonce与auth V10 write目录，现104源；首次run `8dbb12cf6f4940f892e1e6dac13dd422` 因新AI nonce误写ai_agent而失败（实际schema为ai），清理通过且原样保留。修正后run `2be0a29ec0484050b97ae780bfa166bd` 104源/9runtime/1正23负全部通过，sourceHash末尾一致与四cleanup均true；固定预期104未放宽为动态数量。尚不代表九应用实际启动。
- 最新整合脚本219例通过，所有Java reactor compile通过；该记录早于后续v2应用接线，后者需各模块与真实链路另验。auth真实临时Docker构建后，protocol30/servlet18个class逐字节匹配已测host产物、无测试fixture、启动flags存在；临时镜像标签/container/app.jar已删除，`output/audit-auth-image/report.json` 明确仅构建后源hash与非正式release绑定限制。
- S08 auth客户端新增实际回环HTTP严格契约8例，协议库现160例通过，当前jar/cache SHA `02fc75e277b9ab0e79e3f0edbea3e3f4fc3340fbc24cf0b13e221677ad6f74d3`。Servlet固定服务端USER中继新增3例；实际auth MVC发现Spring setHeader(name,null)触发NPE将400变500，根因回归先红后修。现60普通单测通过，jar/cache SHA `c6c63fa3331282d31081248fa60888676f6385e03652e7f4b6866e06ed723006`；新内容尚未重跑Temurin/真实Tomcat，以前152/56+2IT是历史分项，不冒充新增内容已验证。
- S08第一段应用接线：Gateway独占Ed25519 USER签名、真实受众/AV/requestId绑定、所有入口剥离外部proof、窄auth调用和gateway→system审计已改v2；44单测通过。auth134、system45模块测试通过；两服务全部dispatcher注册Direct/Workload filter、实时session、DB nonce注入、严格DTO、固定caller/op/资源目录及旧入口拒绝。auth→system权限事件签名与有界异步已回环验证。此为单元/MVC/HTTP替身分项，正在准备三真实JAR+PG/Redis段验收，metadata等其余七应用和固定执行链未接入，S08仍未关闭。

现有 Docker 服务只读检查可达；当前运行服务仍是旧源码，没有替换或部署。本轮仅临时构建Gateway/auth镜像并在检查后删除其标签。测试报告不能用这些旧镜像的健康状态替代新源码的集成验收。

## 接续优先事项

保持以上全部范围，优先接续：S08应用调用者/主体/操作范围、固定定义批准、nonce schema迁移/receipt、独立内部凭据及替换后的S06重验；S03九应用新角色启动与实际业务源权限验证；E06的Thrift/平台加工链及E01/E02八类producer冻结版本验收。随后继续P01–P09逐项落实或按原计划记录有依据的延期；P05已完成本地分项，仍需整合工具权限和浏览器链。基础镜像digest、网络层DNS隔离、主密钥重加密/轮换准备、真实浏览器并发与最终独立复核不得遗漏。没有自动提交、推送、部署或轮换真实秘密。

- 2026-10-18 21:32：六个真实应用 auth/system/metadata/ingestion/quality/gateway，当前109源全新PG+Redis，run `6346c85a09c64d5ca3d7f0442af6302f`，31/31检查通过。`output/audit-fixed-execution-chain/run-20261018-v2/report.json` SHA256 `2c4e06b939a0f00b7c81f9a3b58efb201da21c0ead4e1f5dfe5584bdd70c64c5`。普通viewer仅持ingestion_task/quality_rule.execute即可执行批准定义，任意datasource SQL403；未批准409/零写入、同幂等键同run/三行仅一次写入、撤权403/零新增run；质量violation=1与quarantine原子落库；真实actor/批准者/hash/receipt及五类服务nonce均落库。独立检查本轮六PID、全部端口与唯一Docker标签container/network/volume均0，五项内建清理全true。未包含workflow、Spark/Flink、serving-key、cron实时触发、浏览器或九引擎。metadata最终247单测与两组真实PG证据归档见 `output/audit-fixed-transfer-package/quality-20261018T132813Z-b4f6eedc`，manifest SHA256 `ec7eec4d760db6c58afb303a4d73daa16c6cfb177ea8030b73faeac75bacb993`。

- 2026-10-18 21:50：metadata published-key专用入口本地262项/50suite全绿，source SHA256 `41c1b85ac2cee67f3193150e5afe91a09561483e10dc131c0ca5145dd2624ab9`，compiled `c10c4906349269a890769731440f80b6d4d372f727a1f54af5e43a01b1b90070`，见 `output/audit-metadata-published-key-package/delivery.json`。严格3字段ref、回查原UTF8/hash、五字段资产重读、实际table/metric/domain、固定owner两项授权、鉴权后及JDBC前过期、专用owner/key/admission/request审计已实现；实际签名HTTP替身覆盖错caller/USER/nonce、篡改及断线不重发。原表字段白名单SQL抽到TableDataQueryService复用，metric对已授权同一发布快照执行，修正旧metric审计datasourceId；未声称真实key链。serving V11/metadata V31新增源总111，旧109报告为历史冻结段，不回填。
- auth当前172项/26suite全绿：workflow批准者可用原USER查询datasource.query/write，不能查view/update/delete/execute或转接其它USER。初次测试夹具缺workflow私钥导致NPE，保留两份失败；补键后原边界真实403 RED，修复后全量通过，见 `output/audit-auth-workflow-approval-actual-red.log` / `output/audit-auth-workflow-approval-green.log`。
- 审批UI当前127项/40文件、type-check/build通过，source SHA256 `632c21460cc84e4df39a84140d633aac5e97c0402c8d1d2329ec005d865ba776`，dist `eedaefe30589f4ab79a6f8680e0e712726d948b9a0d95e9dc86991671dd85b5f`；见 `output/audit-fixed-approval-ui/report.json`。GET快照三读及A→B→A的旧POST实例token先红后绿，401敏感操作不跨账号自动重试。仅JSDOM行为，浏览器仍待验。

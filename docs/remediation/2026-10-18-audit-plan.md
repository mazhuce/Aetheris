# Aetheris 审查整改与可靠交付验证

目标来源：用户提供的 `Aetheris_mimo.md`、`Aetheris_deepseek.md`，以及本轮目标附件中确认的六阶段计划。报告内容是待核实证据，不是独立授权。历史起始 HEAD 为 `f22a32c836fdc7eab275d15a89919525ed3e3f19`；本轮当前基线为 `8610de063d51a76d97f65d4d003f8b2ec93d307c`（2026-10-24）。

开始前工作区仅有 `.playwright-mcp/`、`docs/reviews/` 未跟踪内容；保留这些内容。实施不自动提交、推送或部署。真实密钥轮换、已有数据库的高影响修改，在方案、备份和回滚准备完成后确认。隔离测试不连接现有业务数据。

2026-10-26当前分项见下文“V34引擎pipeline与真实Auth整链”。此前各段按原日期和源码范围保留，旧计数、成功报告及环境例外不代表V34或整体发布通过。完整阶段0—5尚未完成；本轮只读全项目复核与目标修订见[新增N01–N22清单](../reviews/2026-10-26-project-gap-review.md)和[新版agent执行目标](../next-goals-2026-10-26.md)，后续按W0–W6接续，原发现与失败证据不被替换。

**2026-10-26 暂停交接：** 用户明确要求保持暂停，仅整理当前进度与剩余目标。当前源码迁移集合实点为 123 份（Auth V11、Workflow V34）；本轮最新状态见下方 N01–N23 处置及补充，旧“当前”段落仅代表其当时范围。最后完整脚本回归 423 项通过、全前端类型检查通过，仍是分项证据，整体未完成。恢复执行顺序、未修复接线及待验收范围集中在[目标文档第 1 节](../next-goals-2026-10-26.md)；基线完成后的全项目再次复查尚未进行。

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
| S03 | D P0-1/P1-3 | 平台连接隔离、查询与加工账号、按服务数据库权限 | 当前121源PG29项及六应用119项通过，runtime账号无法读取auth表，源历史/SQL/quality批准保持；数据库gate121源1正23负通过。存量账号预检、九服务同版及九类业务源待验。 |
| S04 | M C-4；D P1-2 | auth 可信身份 | 当前1236项常规及六实际应用119项通过，覆盖SQL/quality原主体及权限撤销；引擎客户端签名HTTP分项与PG29项授权夹具分开记录。实际Auth到物理引擎、浏览器、DB身份竞态及九服务同版仍待验。 |
| S05 | M H-1；D 5.10 | system 用户/配置鉴权和 DTO，settings 门禁 | 单元/MVC验证通过；角色、直连与DTO扩展字段负例已覆盖，真实部署待总验 |
| S06 | D P1-1 | 纯 API Key 经网关执行已发布 API | 120源五应用published-key102项及更早四服务25项保留各自原源码范围，已覆盖纯Key、业务3行/SUM60、字段/域范围、撤权/禁用/AV/配额/轮换/审计。当前121源纯Key及最终九服务同版仍待复验，不以SQL/quality119项替代。 |
| S07 | M M-1/M-2/M-5；D P1-7/P2-3/P3-3 | 路径规范化、资源读写契约、批量授权、列表过滤 | 分项实现/验证中；viewer显式授权、末grant撤销、domainCode、AND范围与列表过滤。本人资源权限提示及采集/质量/加工/治理/数据集/看板入口已接入。治理MVC部分更新必填冲突先红后修复；完整定义委托仍须补S08与datasource.write，暂限管理员 |
| S08 | D P1-3；M M-8 | 内部服务身份、audience、操作/资源范围、质量任务主体 | 当前V33可信SQL/quality/Spark/Flink manual/cron/pipeline入口及批准材料、单次派发和原origin对账已实现；默认Runner批准时冻结实际地址已修复。1236项常规、PG/受控HTTP29项及六应用SQL/quality119项通过；实际引擎/IO范围、混合pipeline、Flink完整生命周期新attempt及九服务总验未完成。 |
| S09 | M M-3；D P1-5/P1-6 | refresh 并发、登录容量/过期/IP维度 | 后端CAS/重放与前端独立标签页协调单测通过；真实浏览器/服务验收待完成 |
| S10 | D 模块审查/P3-4 | 禁用/降权/改密及时失效，passwordHash 防御 | 独立access_version与SQL生成/并发单测通过；真实PG生命周期、版本迁移待总验 |
| S11 | 本轮复核新增；关联M M-4/D P1-8 | 数据源严格active准入、独立保存前测试、执行前重读 | 分项验证通过；有效RED26项20失败/0error后，状态/固定执行/health53项通过，实际PG另1项通过：active实际连接、8种非active零新物理连接、重新启用恢复成功。transfer执行前重新读取两端状态/定义。保留首次17个夹具错误及后续整合失败，不代表最终平台通过 |
| S12 | M H-1/M-4；D 5.10/P1-8 | 数据源公开DTO、历史URL/参数/错误秘密保护、保存边界 | 分项验证通过；DTO21项、保存35项及既有Controller/Credentials11项通过。历史URL/params/host/db秘密隐藏、固定错误、嵌入式路径参数拒绝；无关编辑保留已隐藏且运行期拒绝的历史脏配置，明确替换/空串清除。前端14项与完整tsc通过，实际浏览器及最终整合待验 |
| S13 | 本轮复核新增；关联P04/P05/P09 | 同名账号复用下的历史、会话、审计与通知稳定UID归属 | 原UID/name/AV在窄权限读取前后复核及三项受控竞态已先红后绿，当前常规与六应用SQL/quality链通过；120源纯Key102项保留原范围。AI/历史UID原证据不重新绑定，实际DB同名并发与浏览器仍待验。 |
| R01 | D P0-3/P2-9；M Low 时区 | 显式业务时区、Clock、严格日期、SQL占位符 | 当前121源六应用119项覆盖手动、实际SQL/quality Quartz及SQL→quality→SQL pipeline业务日期、严格非法日期拒绝和实际writer正确日期。午夜、完整月末/misfire/双实例及Flink时间窗口仍需同版实测。 |
| R02 | D P2-7/P1-9 | ingestion/quality 持久化调度补偿 | 单元和12项真实PG/Quartz JobStoreTX验收通过；同事务generation、回滚、并发、重启与pending恢复；全平台部署待完成 |
| R03 | 两报告可靠性建议与旧审查线索 | 未知 SQL 执行结果、自动重试、running 崩溃恢复 | V33单次派发token、不可替换receipt/result、Flink逐步提交日志和环境/Runner容量已实现；PG29项及回环HTTP证明丢响应只读原来源、unknown占容量，默认地址变更零连接。Flink生命周期新attempt、崩溃矩阵和实际引擎仍待验，拒绝旧接口不计功能交付。 |
| R04 | M H-9；D P2-10 | 聊天会话乱序、Spark重叠轮询、卸载更新 | hook和API回归通过；会话epoch、串行/合并轮询及AbortSignal接入，真实浏览器验证待完成 |
| E01 | M C-3/H-2；D 测试与CI建议 | 当前运行证据绑定、strict一致性、非通过退出码 | 行为测试通过；v2显式context/manifest绑定当前源码、运行、制品、配置和时间；需冻结源码后重跑八类真实producer形成正式当前证据 |
| E02 | M C-3 | 九类数据源结果、业务错误与清理聚合 | 行为测试通过；完整性、重复/缺项、200+error、实际行数、清理失败反例已覆盖；真实九类重跑待完成 |
| E03 | M H-3/H-2；D 测试建议 | PostgreSQL/Flyway/Mapper/事务与跨服务测试 | 当前121源PG29项与六实际应用119项通过，包含真实PG来源/升级/fence/容量与实际业务reader/writer；Spark/Flink业务结果为受控HTTP响应。九服务同版、九源、物理引擎、浏览器和正式发布仍待完成。 |
| E04 | M H-4；D 运维建议 | 备份与恢复二进制链路 | 模拟和真实一次性PG验收通过；中文/二进制/NULL、约束/索引/序列、clean恢复、摘要、坏归档拒绝与临时文件清理；不代表已有备份已核验 |
| E05 | M H-8/M-15；D P3-5 | 干净构建与历史迁移升级 | V33真实隔离升级V32→V33通过，32份workflow history/checksum/success及SQL/quality原批准保持，仅执行新增V33；总121/workflow33。V29/V30/V31/V32旧安装/升级报告各保留原范围，存量DB inventory、约定客户旧版和完整安装/恢复仍待验证。 |
| E06 | M H-7/M-14 | 数仓语法、跨层对账和维度/脱敏断言 | 真实隔离Spark分项通过；原语法/金额两缺陷实证先红，当前21断言+11损坏数据拒绝+原表复验通过。模拟按分计算，运费/订单优惠明确0、实付缺失单列，邮箱/电话掩码。现有Thrift/JDBC/平台SQL守卫链仍待整合验收 |
| E07 | D P1-4/P2-13 | 恢复三份NUL文档、成熟度和契约文档校正 | 原三份NUL文档已恢复；当前80份Markdown及核心/整改链接检查通过，包含未提交未忽略文档。历史48/76/78份结果保留原范围，当前内容已补V32及旧证据边界；最终交付仍需随剩余源码更新复核 |
| E08 | M H-10/M-6/M-7；D P2-11 | CI成功与制品绑定、shell参数、签名和供应链 | 部分验证；同commit最新CI attempt、11制品digest与签名清单、全量迁移source、部署/回滚同DB gate及13种Action官方SHA已实现。18种基础引用中16种锁定实际官方index摘要，2种MinIO原tag在Quay及同发布者DockerHub均401，严格检查保持非零阻断。5种1panel默认值经root决策切至官方同tag，字节等价未证实、完整构建待验；12工具测试与4组Compose非镜像配置等价通过。云端CI未运行 |
| E09 | M H-5/H-6/M-9/M-10 | 私密state/备份隔离、Git/Docker规则、凭据轮换 | 分项准备/验证通过；S08新ignored目录生成10个workload+Gateway USER key拒绝覆盖。新增AES离线重加密/回滚SQL准备工具、16工具测试及17项真实PG/Java兼容/备份恢复/CAS验证通过，见datasource-credentials-contract。已有数据库与真实密钥未修改，实际轮换仍按具体环境/回滚方案另行确认 |
| P01 | M M-17；D P2-5 | 共享身份协议/方言、核心DTO/契约 | V33常规回归1236项/182suite全部通过无跳过，覆盖protocol166、servlet60、auth189、metadata453、quality90、workflow278；新增引擎签名HTTP6项与Runner状态/取消/日志精确绑定负例。全应用DTO、实际来源/范围及最终冻结版本整体验证仍待完成。 |
| P02 | M M-18/M-19；D P2-10 | 渐进lint、错误/状态兜底、关键页面拆分 | 首包分段验证通过：4文件typed ESLint、6项实际注入门禁、type-check/build/lint通过，自动polling拒绝显式处理。最新全量133通过/1工具30s超时，全部128业务断言通过；仅该工具时限调90s后6项通过，未声称134项整套重跑通过。CI已接npm run lint；页面后续治理及浏览器未验，无全站格式化 |
| P03 | M M-16；D P2-1/P2-2/P2-3/P2-4 | 分页、批量授权、连接测量/预算 | 列表可见性分项已实现并验证：隔离H2+loopback签名权限peer的13项覆盖超过1000行、后续页授权、游标一致性、批量上限和权限服务故障显式失败。真实Metadata服务、前端翻页期间变更、连接预算与池化决策仍待完成 |
| P04 | M M-12/M-13；D P2-8/P2-12 | requestId、审计身份/来源、告警、保留策略 | 首包验证通过：管理员全局/普通用户本人汇总经独立auth能力判定；普通查询记录可信requestId、安全固定错误和无高基数标签的落库失败计数。metadata305/auth176、PG两IT、五应用新增10项实际验证通过。审计/执行历史保留、告警投递与其余链路关联仍待实施；详见permission-contract |
| P05 | D ai-agent模块审查 | 服务端对话历史、工具权限、循环/时限/token预算、固定安全评估集 | 分项验证通过；只接收末问题、本人持久化历史、DB运行围栏、手动有界工具循环/token/总时限、有界worker和可观察取消。49单测+7真实PG通过；真实跨服务工具授权仍归S08，前端显式取消及供应商费用硬上限未声称完成 |
| P06 | M M-11 | Windows造数脚本编码、硬编码测试账号/清理逻辑 | 当前默认Runner兼容源完整scripts360项通过无跳过（Python3.12.14），Spark25项中21项执行通过/4项跳过。首轮360项因clean/package清除协议Surefire输入而出现1 error；实际重跑protocol166项后再跑全套通过，原失败保留。原WinError5及旧Python3.11/PS7异常仍保留，本轮原子替换观察121次无异常，不宣称历史根因已解决。 |
| P07 | M M-12/M-15；D P3-5 | 删除/回填/迁移的审计与可恢复性 | 待核实；不把全库软删除、迁移缺号或普通Flyway非幂等直接判为缺陷 |
| P08 | M M-20；D P3-1/P3-2 | 包体、死资源、死方法/头参数、临时文件 | 待核实；确认引用与用途再清理，增加合理体积预算 |
| P09 | M Low；D P3-1/P3-5 | 版本/Wrapper、默认配置、query_history隐私、API-key前缀、logout计时器、401回跳、aria、prune/启动器/固定sleep、崩溃日志 | 待核实；逐项记录证据与处置，不批量移除未知日志或用户内容 |

## 阶段0当前基线（2026-10-26 更新）

当前迁移集合固定121份，其中workflow33。V33新增引擎批准材料、派发所有权、步骤日志和精确结果绑定；SQL/quality规范投影及既有批准保持。其余服务仍为AI5/auth10/governance3/ingestion9/metadata32/quality10/serving11/system8。gate/身份链/工具测试显式固定121，没有改为动态放行。V32原当前记录已原字节归档 `artifacts/validation/history/current-worktree-pre-v33-20261026.json`，SHA256 `660d2e96ccc1b96603d65332b930591b6df69a0a8307159414e76d906505d378`。下述120及117/118/119历史段仅保留各自原范围，不计为当前同版整体通过。

V31原验证记录已按字节归档 `artifacts/validation/history/current-worktree-pre-v32-20261026.json`，SHA256 `cc7859ca6aa8ef01469c5b2183b489baa671bfd812adfbd9e005acdc20f2d445`。当前索引仍沿用 `artifacts/validation/current-worktree-20261024.json` 文件名，后续指纹不重绑定历史报告。

### 117—119迁移历史基线（截至2026-10-25）

以下“当前”仅指各次历史运行当时的范围；V31曾为119/workflow31，不能用这些旧成功覆盖V32的当前失败、缺项或附件变化。

- 当前仓库没有 `Aetheris_mimo.md` 或 `Aetheris_deepseek.md` 原始附件；可直接核验的审查正文是旧基线 `6adaa6e` 的 `docs/reviews/2026-10-08-full-review.md`。两份原报告只能按台账映射引用，不能把其缺失内容或历史摘要当成当前证据。
- 工作树 HEAD 为 `8610de063d51a76d97f65d4d003f8b2ec93d307c`，相对 `origin/main` 领先 1 个提交；保留未跟踪的 `.playwright-mcp/`、`docs/reviews/`、`output/audit-api-asset-errors-red.xml` 和 `output/audit-jdbc-write-deadline-red.xml`，不把它们替换成当前成功证据。
- 2026-10-24源迁移117个，其中workflow29；V30后为118，V31后为119、workflow31。其余服务仍是AI5、auth10、governance3、ingestion9、metadata32、quality10、serving11、system8。gate/身份链/安全测试现显式固定119，没有改成动态放行；旧116/117/118报告保留历史含义。
- 脚本回归在工作区临时目录、`JAVA_HOME=C:\Program Files\Java\jdk-21` 下执行 `332 tests`，`OK (skipped=5)`；当前 run 输出目录为 `output/audit-seed-script-package/v2-run-20261024T103012Z-e6c9c59f`，报告 SHA256 `749f3cb67d58b5639552f507a9ad93a88bd97a7b02854cf7caa918fc2390778`，用途仍是 PS5.1/PS7 mock，未接触 API、DB 或 Docker，所有子进程已退出且未创建容器或数据库。此前未设置工作区临时目录时出现 68 个 `PermissionError`、未设置 JDK 时出现 1 个环境前置错误，原失败与环境日志均保留；5 个跳过项不计为真实 producer 通过。
- `py -3 -B scripts/base-image-inventory.py --check --allow-unresolved` 返回 `diagnostic-unresolved`，18 个引用中 2 个仍阻断；`--resolve` 候选记录显示 `quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z` 与 `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` 均 HTTP 401。没有猜填 digest、拉取镜像或构建容器，strict release 仍必须非零。
- 前端当前工作树回归：在工作区临时目录与单 worker 约束下执行 `npm.cmd run test -- --maxWorkers=1 --minWorkers=1`，Vitest 为 48/48 文件、163/163 测试通过（退出码 0）；`npm.cmd run type-check`、`npm.cmd run lint` 和提升权限后的 `npm.cmd run build` 均退出 0。构建仍报告 `antd-core` 约 1.30 MB、`charts` 约 556 KB 的大 chunk 警告，不能替代首屏预算与真实浏览器验收；并行全量曾因资源竞争出现超时/4例失败，单 worker 重跑已复核通过。
- 当前隔离数据库部署门禁：`output/audit-database-deploy-gate/run-20261024-current/report.json`，run `0823f69cd0a14f8fb6df4bb560194166`，报告 SHA256 `815E63D4B97B515CB7A0A5E5610DE508C2B9D28707655A50FC26B0C6E94C8EEA`。在一次性 PostgreSQL 中以当前117份迁移、9个schema完成1项正例及23项负例；数据库指纹未变、源码摘要末匹配、迁移凭据退休、容器/网络/卷/私有夹具清理均为通过。该证据仅覆盖隔离数据库部署门禁，不能外推九服务启动、九类producer、浏览器、升级/恢复或正式发布；默认沙箱Docker管道不可访问，本次使用已批准的提升权限运行，旧业务容器未停止或替换。
- 本地浏览器只读验证：`vite preview` 在 `http://127.0.0.1:4173/` 提供当前 `dist`，首次访问稳定进入 `/login`，初始加载无 console error；两标签页独立导航后均保持 `/login`。使用虚构凭据提交得到预期 `403` 并留在登录页，快照和截图保存在 `.playwright-mcp/`。这只证明前端路由、登录负例和页面布局，不证明真实认证、刷新/改密/禁用/降权或跨服务状态闭环。当前验证记录为 `artifacts/validation/current-worktree-20261024.json`。
- P03 列表可见性回归在 `services/metadata` 以隔离 H2 和 loopback signed permission peer 执行：`MetadataListVisibilityRegressionTest` 共 13 项通过、0 failure/error/skip，覆盖超过 1000 项、后续页授权、游标一致性、授权服务异常和有界 batch。Surefire 文本报告 SHA256 `C286257AE815CA179A7041FA9EB27E39985702C817A7829756520EA73FC40A04`，XML 报告 SHA256 `C9D57751A2066211FB0FDA4E122837FE44477525DB09BFF8120112022489080E`；它仍不是真实 metadata 服务部署或前端浏览器整链证据。
- 当前三服务身份链：`output/audit-identity-chain/run-20261024-current/report.json`，run `dc68ac21d56848ed8a0a2abc612f66e2`，报告 SHA256 `2BE7BCB9A1CDE3E916DCCF04333AD1347BE205768A682852A4F66A2628920B15`。Gateway/Auth/System 三个当前 JAR 在一次性 PostgreSQL/Redis 上完成34/34，源码末哈希匹配，覆盖登录与Cookie、v2签名身份、伪造/旧令牌/错audience、禁用/启用、accessVersion、nonce和审计；进程、容器、凭据、临时私钥、卷五项清理为 true，独立 Docker 标签查询为空。它不覆盖其余七服务、浏览器或正式发布。
- 当前四服务纯 API Key 链：`output/audit-api-key-chain/run-20261025-current-v5/report.json`，run `bf9b29e30e8d4aa0a8c5b1a38e6c26d3`，报告 SHA256 `92882B50B05DBAA2C4487737191470F1ECAC72EA6165F6265516D636F5E76FDC`。Gateway/Auth/Metadata/Serving 当前 JAR 和117份迁移完成25/25；五次真实查询均返回精确3行，viewer从未成为admin，伪造管理员头不能改写主体，过期/撤销/配额/跨资产/未发布/错路由、逐项撤权和禁用均按契约拒绝，5条成功审计全部归属key owner且异常主体为0。源码末哈希匹配，进程、容器、凭据、临时私钥和卷五项清理为 true，独立 Docker 标签查询为空。该分段没有启动System，因此Auth/Gateway到System的异步审计投递告警不作为已验证能力，已由上一个三服务分段单独覆盖。
- 上述API Key成功没有覆盖旧失败：`run-20261025-current`（SHA256 `D615B4F7AC9BD2B2EC279246D7DD244CFA857DD774D575A8BE2103945875981D`）因旧夹具未提供v2注册表而在Auth启动失败；`current-v2`（`17AABAB1C47550E269A19E8298A2A8D09EBD95B2361541B0589DCA6D979842BE`）因旧搜索参数 `limit=100` 被当前上限50拒绝；`current-v3`（`9B99C5D5DD3A44F97F57BD3CE86BF67750E5A4B2526C3F0CA6F5530BD16F33CC`）因先签发再降权导致accessVersion变化，服务正确拒绝旧key；`current-v4`（`C41FB3CA744C3255B935FFE27DCB763A7E956CC5CED7C03146761437441399A7`）24/25通过，仅旧审计查询按 `query_kind='table'`/表ID过滤而失败。四份失败报告及清理状态均保留，不能由v5成功回填。
- 当前五应用 published-key v2 链：`output/audit-published-key-v2/run-20261025-current-v3/report.json`，run `a5baa93b043e4c83b42c25489a9b7ff9`，报告 SHA256 `AD73B15CE01FB2D265E1FA71C54375438451AC00B42EB3A2503CCD21C4F95438`。Gateway/Auth/System/Metadata/Serving 当前 JAR、117份迁移完成102个唯一case且0失败；表查询返回3行、指标SUM为60，权限域/field、密钥申请/批准/配额/撤销/轮换、accessVersion、审计范围/来源、普通查询固定错误、三向真实nonce及业务reader平台隔离均通过。源码末哈希匹配，五项清理为true，独立Docker标签与私钥目录检查为空。
- 五应用成功前两份失败继续保留：`run-20261025-current`（run `413b4128dc714863a3361f88e09420b8`，SHA256 `AE86B753DC8B1EDE0419AC38C0E976A5AB0E2D82C1DA9AB8F28B96476FBBF454`）在91项处因夹具仍要求旧 `QUERY_FAILED` 而拒绝当前正确的 `SQL_POLICY_REJECTED`；`current-v2`（run `f5da3b7cb5e64c51b419678f538b0b1e`，SHA256 `275D87350F5F11749E51CA64F4D8532EC6532E181AEB528C885AE85FB0122420`）因System计划端口被其他本机进程抢占，在0项业务检查前失败。两次运行五项清理均为true，分别归类为测试夹具问题和运行环境异常，不能改写为成功。
- 当前六应用固定执行链：`output/audit-fixed-execution-chain/run-20261025-current/report.json`，run `98b63b3a21c6432cb828f554df50b1e2`，报告 SHA256 `7EAB854E25DB470FE6CD10B99916E71EEBF6AD96D8B9510C3E6116E9B28EFC58`。Auth/System/Metadata/Ingestion/Quality/Gateway 当前JAR和117迁移完成31个唯一case、0失败；普通viewer只凭任务execute执行已批准transfer/quality定义，任意SQL被拒绝，reader/writer分离、3行业务写入、质量隔离、幂等、撤权、actor/批准者/hash/receipt和五向nonce均落库。源码末哈希匹配，五项清理为true，独立Docker标签查询为空。该段仍不覆盖workflow、pipeline、Spark/Flink、cron实时触发或浏览器。
- 当前五应用 workflow SQL/cron 链：`output/audit-workflow-sql-chain/run-20261025-current/report.json`，run `fd0fb9d8e3ab4916b74c71d935035b2b`，报告 SHA256 `D7ADCDE15E09EB08091E34DB4225DFE6CAE2EA977DC62F766DD425642E715212`。Auth/System/Metadata/Workflow/Gateway 当前JAR和117迁移完成37个唯一case、0失败；普通用户按批准定义完成实际writer写入，非法业务日期400且零attempt，撤权与篡改拒绝；一次真实Quartz触发成功，业务日期与服务端日历一致，schedule/binding generation、cron actor及receipt封存，三向nonce落库。源码末哈希匹配，五项清理为true，独立Docker标签查询为空。未覆盖pipeline、Spark/Flink、跨月/闰日真实长时运行、双实例或浏览器。
- `docker compose config --quiet` 通过；`py -3 -B scripts/base-image-inventory.py --check` 以 2 个未解析镜像引用退出 1；`platform-release-acceptance-strict.ps1 -ValidateExisting` 退出 1（`Release evidence validation failed`）。现有 aggregate 不能被复用为当前冻结版发布证据。
- 当前证据可证明分项实现和部分隔离运行，不能证明九服务同一冻结版、真实浏览器、九类 producer、完整升级/恢复、云端 CI、镜像构建签验或生产环境验收。历史 `passed`、旧源码指纹、缺附件、跳过项和环境受阻均不计为当前通过。

## 阶段2 V30执行不确定性记录（历史分段，2026-10-25）

本节保留V31之前的结果；“当前”均指该次运行的118迁移基线。V31结果另列，不通过后续指纹重新绑定这些报告。

- 收尾核对发现上节脚本报告抄录摘要只有63位，不能作为有效SHA256。原报告未改写，现场实测完整摘要为`749f3cb67d58b5639552f507a9ad93a88bd97a7b02854cf7caa918fc2390778e`；原错误值及原验证记录保留在`artifacts/validation/history/current-worktree-pre-v30-20261025.json`。该报告仅保留历史mock诊断用途，不计入当前118迁移验收；补记摘要不重新绑定源码、运行或附件。`332 tests`包含5个跳过项，不能写成332项全部实际通过。
- 有效RED归档：`output/audit-execution-uncertainty/run-20261025-current/red/report.json`，SHA256 `BA1454203BFBC404D4D6002C063C3A283AAF0418B25E6959B831EF213693C900`；6 tests/6 failures/0 errors/0 skips。证明unknown/取消质量结果可被waiver放行、缺失上游可豁免、远端等待中断或缺失结果被标失败、Spark取消unknown提前释放容量。更早默认沙箱Mockito attach管道Windows error5及中断日志单独保留，不算业务断言。
- 当前workflow全量：`output/audit-execution-uncertainty/run-20261025-current/unit-final/report.json`，SHA256 `72DC71B32025D588907057F07CCCE0B5A940D73E361E83AE07715FA85F365754`；45 suites/239 tests/0 failure/error/skip。新增单调等待窗口、换代结果拒绝、派发异常保留unknown、完整质量结果与普通失败区分、显式waiver正例和取消确认对照；没有真实远端引擎主张。
- 当前真实PG：`output/audit-execution-uncertainty/run-20261025-current/postgres-v3/report.json`，run `1a922bedf68947f9933dd7db2234c067`，SHA256 `6853F6F792B52BCF34CDEAC78EB2C742E6D4CFD152779393D2D94F067F63ADAB`；4 tests/0 failure/error/skip，旧V29→新V30、118迁移、普通workflow runtime角色、真实数据库状态/容量和受控远端响应。源码末哈希一致，五项清理为true，专属Docker标签为空。该结果不代替可信pipeline入口、父子v2身份或真实Spark/Flink。
- PG首轮 `postgres-v1`，run `76ecec151c484b838bc5ce9a8c36bb64`，SHA256 `D5F7B67A9FD247226D82A9F9BC70EA6770CF2AC58D0F0480F1D9DDB1ABE43D35`，4项中1个真实数据库error暴露queued CHECK遗漏；保留失败和脱敏SQL证据。中间 `postgres-v2` 4/4通过早于最后派发异常修复，其SHA256 `6A3F65F027576AE3F2C2E64AB77B65ED833E18D98F69155366D62606AE4A6772`仅保留当时范围；没有用后续成功覆盖它们。
- V30及回退准备见[执行恢复契约](execution-recovery-contract.md)：现有安装须先核实Flyway history/约束并备份，正常migrate、不repair；应用回退保留兼容V30，如需整库回退按原快照恢复并核对history。本轮未改变现有数据库、真实密钥、提交、远端或部署。
- 当前118迁移数据库gate：`output/audit-database-deploy-gate/run-20261025-118/report.json`，run `14fdcb2d198e438cb0a4946c0d7b9334`，SHA256 `FAA51431DB34741F616827B99EB977365C9CC53794F20B2A95E31A1BCAAD7BC6`；1正23负全部通过，九schema、源摘要末匹配、数据库未变、迁移账号退休、容器/网络/卷/私密夹具清理通过。此为合成manifest的真实数据库/Compose准入门禁，不是实际应用发布或11个正式镜像验证。87项audit工具测试、12项数据库gate工具测试及118迁移静态检查通过。

## 阶段1—2 V31可信SQL pipeline接续（2026-10-25）

本节为119迁移工作树的分项验证，不能替代阶段5同一冻结版总验。共享契约与迁移由主代理统一修改；独立子任务仅作只读协议/约束复核和官方镜像获取调查，所有重型构建与容器验收串行执行。

- 实现：`PipelineExecutionStore`封存原UID/AV、明确pipeline kind、批准定义/拓扑及run/node/attempt/fence；依赖只认同run或显式retry来源链的原成功凭证。恢复不查同名账号、不追随新attempt、不重构新图；全成功但最后run写失败仍可收口。V31父run锁与node membership阻止并发封存污染。普通响应去除SQL计划和私有身份/批准JSON；公共parent仍只供workflow-child，未将pipeline伪装manual。
- 最终真实PG：`output/audit-pipeline-authority/run-20261025-current-v6/report.json`，run `e7fd70d540f8479a97daaf6cd0d3ef1f`，SHA256 `7411d59e0e8e4ab619ed5db9d8d72c227bf8a14da2b553de6d27672f9ab5717a`。11/11、0 failure/error/skip；包括双连接实际Lock等待、封存后55000拒绝、并发恢复唯一、显式多次retry来源、原actor与新attempt隔离、最后完成写故障。普通runtime，身份和receipt/物理完成为受控夹具；不是Auth/Metadata HTTP或真实业务SQL证据。原字节30份workflow迁移/总118升级只新增V31，history/checksum/success不变、pending0，5个新helper仅workflow runtime可执行。最终V31 SHA256 `21696ab9487297f56f51ccdd6b29be20a87c207bcaf430f12f005ea54a6f42d3`。
- 最终五应用：`output/audit-workflow-sql-chain/run-20261025-v31-pipeline-v2/report.json`，run `6622e51cc18041898f182594630fb7f9`，SHA256 `d0d417abaeffa52607aa658423ddea7cd2992ed6670a2b6691d60ea4cfed9e02`。Auth/System/Metadata/Workflow/Gateway当前JAR、119迁移和临时PG/Redis完成61个唯一case、0失败。两节点写入精确中文业务行，上游实际输出由下游SQL读取，writer为业务账号；两receipt关联原非管理员UID、批准者、revision及pipeline fence。伪造管理员头未改主体，未批准/非法日期/撤权拒绝且无新run/attempt/业务写入；响应不泄露计划SQL或身份JSON。原手动和真实Quartz链同时通过。源码末摘要一致，五项清理true；未覆盖混合引擎、全部排队撤权故障、浏览器或九服务总验。
- 完整相关单测：`output/audit-pipeline-authority/unit-a39a75c6f2e34af7b06421db9bcb53d0/report.json`，SHA256 `e13594e0cda9db3b6d05e7978fa1813b8460dbdad76cc1aa0cfda0515d366d8b`。workflow45 suites/241、metadata70 suites/440、security-protocol8 suites/160，共841项，均0 failure/error/skip。每份报告要求本轮run marker，归档保留原摘要、执行结果与失败诊断，过滤JVM属性及stdout/stderr；不混计先前IT报告。源摘要始末一致。
- 119迁移不确定性回归：`output/audit-execution-uncertainty/run-20261025-v31-final/report.json`，run `75b4627c5175482497cb0707e214a346`，SHA256 `6771c54d8505e2c7029d3782ef2515d44359323479ca2c5b0ef6318e45f40523`。4/4、0 failure/error/skip，实际V29→V31、质量失败凭证/waiver策略及过期拒绝、unknown占位、Spark未知取消保留容量和确认后释放。远端为受控替身，不是混合pipeline入口验收。
- 119迁移数据库gate：`output/audit-database-deploy-gate/run-20261025-119/report.json`，run `85c6950899a443ce9e9821913ff0e862`，SHA256 `871edca94855a5baf47940fd1bd7b83702d0f1819b4ec0f6b21209cf7ee9d2a3`。1正23负、0失败；源末匹配、数据库未变、凭据退休、四项清理true。仍是合成manifest的真实DB/Compose准入，不是11个正式镜像部署。audit工具90项、数据库gate工具12项、角色契约18项通过；workflow静态6个工作流、119迁移及78份文档检查通过。

本轮整合失败和中间成功均保留，不能由最终通过覆盖：

| 运行 | 原结果与分类 | 处置/范围 |
| --- | --- | --- |
| pipeline-authority v1 | V30→V31 history检查通过后角色准备ValueError，尚无业务用例 | 新5个SQL INVOKER helper未纳入精确清单；Python/Java按schema+完整签名补齐，未开放任意函数 |
| v2 | 10项中1 failure/9 errors | Mockito夹具Long被Java varargs推断为Object[]，明确转换long修正；不是业务RED |
| v3 | 10项中5 failure/0 errors | 代码缺陷：逻辑success与物理succeeded混用导致结果/恢复证据不匹配；修正映射，保留有效失败 |
| v4 | 10/10通过 | 早于并发封存父锁，只保留旧范围，不能冒充最终11项 |
| v5 | 11项中1 failure/0 errors | 新观察事务缓存pg_stat_activity首次快照，未看到真实锁等待；显式刷新统计快照，仍要求Lock和55000，不放宽断言 |
| workflow-sql pipeline v1 | 61/61通过 | 早于最终V31父锁字节；v2另起新run重建JAR/119迁移，不给v1换摘要 |

所有原报告、测试附件和SHA256集中在`artifacts/validation/current-worktree-20261024.json`；V31前原记录按字节归档`artifacts/validation/history/current-worktree-pre-v31-20261025.json`，SHA256 `83ca79395d414a34bcb9bf5490249a3d672a2a31b687f6863e20af9d8a96a179`。独立核对pipeline-authority/workflow-sql/uncertainty/database-gate四类标签的容器、网络、卷均0，临时私钥/DB私密夹具目录及自有测试JVM均0。当前基础镜像strict检查因两项MinIO锁退出1，发布`-ValidateExisting`也退出1；releaseEligible仍false。没有改现有数据库、真实密钥、提交、远端或部署。

## 阶段1/2 V32子质量闭环（2026-10-26）

本节保留引擎接续前的V32检查点。此处“当前”指各次运行当时的范围；后续身份竞态修复不重新绑定这些报告。最新证据另列于下方接续专节。

实现与权限边界见[子质量契约](workflow-quality-child-contract.md)。父定义批准时锁定子规则revision/hash；手动、cron、pipeline均保留真实主体，metadata实际查询前回查父来源并验证四项权限。失联只读原receipt，unknown不重投；结果、隔离和审计同事务；父attempt绑定独立child result/hash和原receipt，旧attempt、缺证据和不真实waiver不能推进。两侧恢复最多32条/5秒并公平前移游标。

- 六真实应用：`output/audit-workflow-quality-child/run-20261026-v3/report.json`，run `8b9fff6b041240bd9ff319718b3b851a`，SHA256 `7115e92f2330032bb3c635ddd1a7219340a6c29a690f19b4db38794adceadaea`。当前120迁移，Auth/System/Metadata/Quality/Workflow/Gateway共119项、0失败；SQL旧链、质量手动、一次真实quality Quartz及quality→SQL pipeline均实际完成。业务writer写入精确中文日期行。非管理员actor和独立父批准者两类撤权后均无新增reader连接；6份不同路径的连接日志已核摘要、时间、真实计数并防附件替换。源码末摘要一致，五项清理true。
- 实际PG约束与恢复：`output/audit-workflow-quality-authority/run-20261026-v5/report.json`，run `99753c20748e47888feb07455c175994`，SHA256 `176a46d7da1568e258aab691091e1991ebea0d41c5cb683dadad78917f548597`。workflow19+quality7共26/26，0 failure/error/skip；包括旧history/SQL批准保持、固定父子、旧attempt拒绝、unknown占位、合法waiver、原receipt回收、写失败回滚、双实例仅一次隔离/审计及超过32坏来源后的公平性。普通runtime角色，无跨schema放权；远端父/receipt部分是受控夹具，不能声称HTTP/实际业务SQL已由此证明。源末匹配、五项清理true。
- 当前完整相关单测：`output/audit-workflow-quality-child/unit-ac6b125b81694d10944c2e192bc2620a/report.json`，run `ac6b125b81694d10944c2e192bc2620a`，SHA256 `2962ea90fce32a2c6f168035440177be03bd3a2c217a702d24f9920482d4510b`。protocol166/auth176/metadata453/quality90/workflow255，共1140项、174 suites、0 failure/error/skip。逐XML匹配本轮marker，保存原XML及脱敏归档摘要、编译class与源末一致性；不混入旧IT。

- 120迁移数据库gate：`output/audit-database-deploy-gate/run-20261026-120/report.json`，run `75a7003e50854ad69f8d1ccd674c1cc9`，SHA256 `cd72dd1dc144ab8576a9fae7d173bfd9ef4e9dd2e31b85775b210eb980fe49f5`。1正23负全通过，源末匹配、数据库指纹未变、迁移凭据退休、四项清理true。使用合成未签名manifest测试真实PG/Compose准入，不能充当11镜像正式部署。
- 共享协议Linux兼容：`output/audit-protocol-runtime/a2233a0f60da4e2589b0863d1d91f3da/report.json`，run同目录ID，SHA256 `215890dc4f1493a9e3ec37230a21ee4817929ab7054fa4252c882c02b590d31c`。缓存Temurin21、无网络/只读/非root隔离容器166项全部实际通过，源码/产物始末匹配、容器/bundle清理通过；仅证明协议库兼容，不是应用镜像/发布验收。
- 工具与静态检查：审计107、数据库gate12、角色18项通过；6份workflow、79份Markdown、120份迁移、Compose config及diff check通过。新增quality workflow主机/端口两行使11条镜像来源行号平移；仅同步锁清单坐标并验证18个引用/46处来源完全相同，16份原官方摘要证明和2项MinIO阻断均保持，原锁及首次coverage失败另存。严格镜像检查仍因2项未解锁退出1，严格发布既有证据检查仍退出1。
- 独立源码复核覆盖主代理的workflow/quality实现与V32迁移，未发现新的可复现阻断；这是源码意见，不替代上述运行证据，也未新增持续超载/客户迁移证明。

- 完整脚本当前复验：`output/audit-workflow-quality-child/scripts-acb5fb24c14d411bb4f883653a14e3ac/script-validation.json`，run `acb5fb24c14d411bb4f883653a14e3ac`，SHA256 `f2c8fbf60abc705a829feb52a11e7a490de5adc414da1669f32fe309e425aa66`。隔离Python3.12.14，scripts357/357、0跳过；Spark脚本25项中21实际通过、4跳过。源末一致、自有临时目录已移除。无付费模型、真实API/DB或应用Docker执行。
- 完整回归首轮 `scripts-4b18f7d26494480ba4312bf02cdf8974/script-validation.json`（同上output父目录），SHA256 `ec62570ac97e7ad34f6a997b78208a2b2ffb068c0a152159db4f689dfc7640b2`，357项中1 error：Windows在原子替换manifest时WinError5。未修改release-evidence写入/验收规则；定点17项通过，第二整套仅增加观察，121次原子替换无异常。根因仍未确认，原失败与观察附件保留在 `final-checks/full-script-environment-observation.json`，不能用复验通过抹去首次运行，也不据此关闭环境诊断。
- 独立宿主清理清单 `output/audit-workflow-quality-child/final-checks/independent-cleanup.json`：七类审计标签的容器/网络/卷均0、临时私钥和DB夹具0、匹配的自有Java进程0。旧sandbox TEMP根已在宿主视图确认为不存在；未删除未知目录或改变ACL。当前记录保持partial、releaseEligible=false。

本轮原失败与中间成功完整保留：

| 运行/记录 | 分类与原结果 | 修复及边界 |
| --- | --- | --- |
| quality-authority v1 | 代码缺陷；18项中7 error，DB旧投影的非流任务allowedLatenessSeconds默认为0，被新codec错误拒绝 | 接受null/0、仍拒非零；v2的18项成功早于恢复用例，不能充当最终26项 |
| quality-authority v3 | 19项workflow通过，7项quality中1 error；Mockito重设答案调用旧null matcher | 修正夹具doAnswer，v4的26项通过早于最终DTO变更；当前证据另起v5 |
| quality-child v1 | 代码缺陷；80项后失败，生产NON_NULL把pending结果的null字段省略，严格10字段客户端保留unknown | Result显式ALWAYS并补真实生产序列化配置回归；不放宽客户端字段或将unknown改成功 |
| quality-child v2 | 96项后失败；负例留下unknown占用，后续正例复用相同规则被正确阻断 | 正例先行，两种撤权使用独立规则/父任务；不清除unknown、不放宽唯一占位；最终另起v3 |
| 首轮Auth ACL断言 | 过时测试预期拒绝workflow查询quality_rule.execute | 只为workflow+原USER开放精确execute，邻近action/caller仍拒绝，归档旧失败XML |
| 首轮审计工具107项 | 1 error；协议runtime producer仍固定旧160项 | 显式固定当前166，并拒绝160/165；不改成动态数量，原失败日志保留 |
| 首轮新Python17工具测试 | 运行环境异常；旧sandbox TEMP创建/清理WinError5 | 使用工作区自有临时目录，最终17项通过；宿主提升权限只读核对确认原sandbox TEMP根已不存在；未通配删除或改变ACL，原WinError5仍保留 |

当前仍有明确阻断：Spark/Flink手动入口的用户名降级、Spark排队派发主体/定义重验和丢响应处理、Flink恢复时当前定义/active fence核对；这些本轮只读定位，未声称修复。真实浏览器并发、全部远端故障、九类数据源、最终同版九服务/前端、约定旧版与备份恢复、CI/镜像构建扫描签验和八类正式producer仍须完成。两项MinIO锁未解，releaseEligible仍false。没有操作现有数据库、真实密钥、提交、推送或部署。

## 阶段1/2 引擎传输与身份竞态接续（2026-10-26）

完整阶段0—5目标保持不变。本轮修复实际阻断，尚未接通Spark/Flink完整可信来源。[引擎执行契约](workflow-engine-execution-contract.md)区分已实现、定点验证和必要剩余工作；没有新增迁移，总集合仍120/workflow32。

已实现：Spark POST的5xx、断线、空/畸形/错key/digest/ID/状态、409及重定向只向原runner按原key只读核对，404不重投；64KiB响应限额、重复字段拒绝及安全固定错误。排队派发在POST已发生、本地fence变化后保留容量，不以状态冲突证明远端停止。独立引擎授权仅允许workflow workload且USER FORBIDDEN，绑定完整attempt/revision/manifest/资源快照hash；检查原actor execute、原批准者update和平台管理能力。该接口尚未被生产Spark/Flink来源消费。

复核发现权限读取期间可能同名重建或撤权，影响原有fixed-execution和published-key及新增engine授权。三处在返回结果前再次核对原UID/name/AV，不能采用期间新账号的权限。实际数据库原子快照/分布式撤权窗口仍未由此证明，不能把受控MVC注入写成真实DB并发验收。

- 有效Spark RED：`output/audit-engine-execution/preflight-20261026/red/report.json`，SHA256 `88562c9d712169d693085fd0a5d918a5798661d0e0ffe62add59638b860e8971`；真实双origin HTTP共11项，9 failure/1 error/0 skip，原500被误判、默认origin回退、错误句柄和秘密错误透传均可复现。修复后11项全通过，所有不确定场景仅1次POST。
- 旧fence容量RED：同父目录 `stale-capacity-red/report.json`，SHA256 `6f6d9df3b717cb86b3f2feae7fa855c96e8ce48f9952f884a9593436b5bc7194`；1 failure/0 error/skip。JDBC/协调器/runner为受控夹具，证明原代码POST后错误释放capacity；修复后通过，不冒充实际Spark或PG竞争验证。
- 身份竞态RED：同父目录 `identity-races-red/report.json`，SHA256 `2ff0e1d4f1068bfcbcb9214c638d4d653e5b21f7f1fff379d13c5dd09d1c01c6`；三个入口各1项有效failure、0 error/skip。保留原XML摘要和归档；修复后覆盖actor、批准者、父批准者和key owner的UID替换、AV变化及禁用。
- 最终相关单测：`output/audit-engine-execution/unit-9dd6adc19e3b4741832e736e5aa9db78/report.json`，run `9dd6adc19e3b4741832e736e5aa9db78`，SHA256 `0f29e9dae3c0b5283d4c019aa92ae6fee1abc1bfc4fd80d094ad493a33b88c5e`。1225项/181suite、0 failure/error/skip；六模块fresh marker、原XML及归档摘要、source/class绑定齐全；同一reactor运行共享库，未另行重复跑库。
- 五真实应用published-key：`output/audit-published-key-v2/run-20261026-identity-race-regression/report.json`，run `231f229e34dc4b298d38dc5553dd5053`，SHA256 `08ee7b50a34981ebf387bc5cd5746ed565394e84561f799751bbca939f752ba7`。当前源码/120迁移102项通过，精确行/指标、非管理员主体、逐项撤权、nonce及审计验证；五项清理true。
- 六真实应用固定链：`output/audit-workflow-quality-child/run-20261026-auth-races-final/report.json`，run `0933f2c1742045f58ab5fd94872b024b`，SHA256 `8133bd6e5c1feb169862055c8d1e90b56458af3db39762f49e4e02bdcacbc91a`。119项通过，包括实际业务reader/writer、SQL/quality手动、Quartz/pipeline及撤权零新增连接；六份连接附件摘要校验、源末一致及五项清理true。没有调用Spark/Flink物理引擎。
- 两组实际应用JAR原字节分别保留在各runId目录的`retained-jars/`，对应`retained-artifacts.json`校验实际报告原SHA，未重写原报告/源/运行；后续构建不会使这些已测制品丢失。两组的私有安装/端口分别独立，不计为同一正式冻结配置的全平台验收。
- 当前PG兼容回归：`output/audit-workflow-quality-authority/run-20261026-auth-races-final/report.json`，run `f9ccc3c03d6f448b8335d64fbb88092a`，SHA256 `5adda9218541a8a0d9e4a5034ce74e7d4440c5ab9b1285b90cdfdd39ee760f03`。26/26、0 failure/error/skip，原V31 history/SQL批准和V32父子恢复/围栏/事务约束保持；源末一致、五项清理true。远端回执部分仍为受控夹具。

引擎接续前的当前记录按原字节归档 `artifacts/validation/history/current-worktree-pre-engine-20261026.json`，SHA256 `f3f609396ee04a5437b8fb18f26f8af6a1ffd1f789d1229b0556cb3f1ff33d2a`。中间1222单测、`run-20261026-engine-regression-v1`应用及对应PG运行早于最终身份竞态修复，保留原范围，不计为此检查点通过。历史脚本357通过仍仅覆盖其未变脚本源；第一次WinError5和4项Spark跳过仍保留，未声称原因已解释或重新执行。

当前审计工具107项通过，文档/迁移/工作流及diff检查另留日志。独立宿主清理核对11类审计标签容器/网络/卷均0、临时私钥和数据库夹具0、自有测试JVM0；已测JAR作为证据保留。严格镜像仍因2项MinIO锁退出1，严格发布既有证据检查退出1。当前记录继续partial/releaseEligible=false。

必要剩余工作：Spark/Flink manual/cron/pipeline原主体和批准资源快照；排队重新授权及持久化派发所有权；Flink恢复/升级的新批准和active fence；status/cancel/result精确回执、未知状态及物理容量对账；runner/Flink账号、挂载、网络和真实IO范围；实际引擎/浏览器/九类数据源、明确旧版及备份恢复、冻结制品CI签验和八类正式producer。没有以禁用引擎或旧用户名桥缩小目标，没有修改现有数据库/真实密钥、提交、推送或部署。

## 阶段1/2 V33可信引擎来源接续（默认Runner兼容前的检查点，2026-10-26）

完整阶段0—5目标继续保持active。当前实现已接通Spark/Flink的可信手动、cron和pipeline来源；尚未完成正式交付验收，S08/R03仍未关闭。

- Spark明确批准实际job版本、制品key/type/hash/mainClass、参数、输入输出、环境、Runner集合及凭据指纹。排队后重验当前定义/资源与原actor、批准者权限，实际提交消费封存快照。环境总预算和Runner预算均由数据库序列化检查，unknown仍占用容量。
- 每个attempt的派发token/origin一旦写入不可替换。丢响应后只读原origin/key，不再POST；receipt绑定原manifest、引擎材料摘要、requestKey、远端句柄与artifact，终态和结果行数不能事后改写。晚到旧fence不覆盖新实例。
- Flink逐步持久化session/setup/INSERT的提交前摘要和已知句柄，步骤必须匹配原脚本；部分提交或丢失响应保持unknown。原Gateway的提交完成不是业务完成，终态必须查询原JobManager及其绑定Job ID。旧暂停/恢复/升级接口拒绝修改新封存attempt，替代的新批准、新物理attempt及前端流程尚待实现，不能算作这些功能已交付。

本轮证据：

| 证据 | 结果与边界 |
| --- | --- |
| `output/audit-engine-execution/unit-v33-full-175d034c159d46cb96cfd4cba4b30c43/report.json` | run `175d034c159d46cb96cfd4cba4b30c43`，1236项/182suite，0失败/错误/跳过，fresh marker及源码末摘要一致；SHA256 `b011420adfc46ebecc6f440827e9b309bdbab4e2bd3f5bb7c2cdb981f6ac3a90`。包括实际Ed25519 HTTP，但权限数据仍为受控响应。 |
| `output/audit-workflow-engine-authority/run-20261026-v33-final/report.json` | run `92bde5ada48c46b0b3717e74bdae6afc`，27项全部通过，SHA256 `819b4163fc44c49fd3a27e9fdcc788815a9faeba1bf93bfea38e0ea20cac5be3`。实际PG runtime角色、V32→V33原history/SQL/quality批准保留、竞争、容量及实际Spark/Flink回环HTTP；身份授权与引擎业务结果为受控夹具，没有启动物理Spark/Flink。五类内建清理均true。 |
| `output/audit-workflow-quality-child/run-20261026-v33-regression/report.json` | run `08d3092cdf9b489ab81a4b8dfe41b8aa`，121份迁移下六真实JAR的119项通过，SHA256 `3ae3d7ae184181c96fe1f372893828238b64e41523b042beb7b13c20cef40370`。Auth/System/Metadata/Quality/Workflow/Gateway实际业务reader/writer、手动/Quartz/pipeline及撤权零reader连接；6份连接日志摘要和五类清理均通过。6份实际测试JAR原字节留存，manifest SHA256 `ba4708938e54d402c55a380f7e98448f2fe4634813c350e3a7dc55a37fcb566e`；未运行物理Spark/Flink。 |
| `output/audit-engine-execution/scripts-64b8bbf173134a05a5592ea70d4e6149/script-validation.json` | scripts360项全部通过无跳过；Spark25项中21项通过、4项跳过，源摘要始末一致且自有临时目录清理完成。首轮 `scripts-911e06c6d4f949d3bd4c6a162e1fef4d/script-validation.json` 的360项1 error原样保留：六应用clean/package删除live Surefire报告，协议producer正确拒绝；实际重新运行当前protocol166项补齐输入，再执行全套通过，未复制旧XML。两次原子替换观察均121次/零异常，旧WinError5根因仍未确认。 |
| `output/audit-database-deploy-gate/run-20261026-121/report.json` | run `265fe318474d4e9ea15dd413ecbba6f5`，121源真实PG/Compose身份门禁1正23负通过，SHA256 `8738ae0d57e6d84e65df9aeb5abe7ca41894435d011e546a826efe5ba33f19c9`；数据库指纹未变、源末摘要一致及迁移凭据退休/清理通过。使用未签名的受控manifest夹具，不冒充正式发布或应用部署。 |
| `output/audit-engine-execution/unit-v33-first-red/report.json` | 原276项中的10项error原样保留：7项为新类型分支的空值代码缺陷，3项为旧入口/窄Spring上下文夹具；没有通过允许用户名桥修复。 |
| `output/audit-engine-execution/unit-v33-191a4add197741a5b8d0d5512d6da0f2/report.json` | 278项中1项error原样保留：新增环境总预算的mock数据缺失。补齐显式预算后完整1236项通过，没有放宽环境限制。 |

独立只读清理记录 `output/audit-engine-execution/preflight-20261026/v33-independent-cleanup.json` 核对12类审计标签的container/network/volume、自有Java、私密key/DB夹具及两次脚本临时目录均0；实际测试JAR按证据要求留存。`--check`镜像门禁和将partial索引当发布报告的校验均返回1，两个未解镜像锁及缺少正式producer不能被当前分项通过消除。

首轮PG22项报告 `run-20261026-v33-initial/report.json` 属于后续硬化前的V33源范围，保留原记录；最初CLI输出路径不符合JSON契约的错误日志也保留在 `preflight-20261026/v33-postgres-initial.log`，未冒充数据库执行失败或成功。原V32的五/六应用及旧脚本证据不重新绑定到V33。

必要剩余工作：默认Runner兼容前尚未完成的实际Auth至引擎整合负例、撤权/停用/定义变更零物理提交、混合引擎pipeline、Flink暂停/恢复/升级、进程终止故障矩阵、物理引擎账号/挂载/网络/IO范围、真实浏览器、九数据源、约定旧版与备份恢复、冻结制品CI与镜像签验以及八类正式producer。两项MinIO锁仍阻断发布。没有修改现有数据库、真实密钥、提交、推送或部署。

## V33默认Runner兼容与当前验证（2026-10-26）

继续完整阶段0—5目标。静态复核发现种子Runner使用`config://default`，初版V33材料捕获器会拒绝它，影响正常默认作业批准。本轮在批准时解析并冻结实际配置origin；队列读取封存origin，派发、恢复不再传递可变别名。配置变更不沿用旧批准。没有通过重新接回用户名入口或使用当前默认地址恢复旧作业解决兼容。

- 当前常规回归：`output/audit-engine-execution/unit-v33-full-d6baecb8a346447785fbca1205ba9c88/report.json`，run `d6baecb8a346447785fbca1205ba9c88`，1236项/182suite全部通过无跳过，源摘要始末一致；SHA256 `6ee6ebd85ef832c965f2f6c9b73828a42c03b4c765928565a23ecdfa015c09ed`。
- 当前PG/受控HTTP：`output/audit-workflow-engine-authority/run-20261026-default-runner/report.json`，run `1f92188f1a2846aaa68d0b2d55c3a609`，29项全部通过，SHA256 `45557e2eb199a43b3b2214b10cb834f8ca7eeee8ceb1a293d393ca59565c8c7e`。新增默认别名正常提交一次，以及批准后改配置时新旧两个HTTP origin零连接/零提交；5类内建清理均true。Auth决策及引擎业务结果仍为受控响应，没有运行物理Spark/Flink。

- 当前六应用：`output/audit-workflow-quality-child/run-20261026-default-runner/report.json`，run `1782428da00e41a8ad7a5a17d6028820`，119项通过，SHA256 `e6f169f4608c972215d11b03225cd14604e49ed4723c3843402f5e2c613f2644`；当前6个JAR、121份迁移、实际SQL/quality reader/writer及手动/Quartz/pipeline和撤权零连接，6份连接附件及5类清理全通过。实际JAR留存manifest SHA256 `36922ad2fadbf47d7c837375518a19833053ac4eacf6e3eeaeae60b5e2f57210`；类/资源/共享库一致性报告 `default-runner-jar-parity.json` 通过。该批未调用物理Spark/Flink。

- 当前脚本：`output/audit-engine-execution/scripts-425b06e66d464879bfbe340eeb517ad0/script-validation.json`，run `425b06e66d464879bfbe340eeb517ad0`，scripts360项通过无跳过，Spark25项中21项执行通过/4项跳过；SHA256 `2ce3e398bdc7b571f6af339228fb3bc43a02990512272516725f9e40b6872ec9`。当前源摘要始末一致、自有临时目录移除，原子替换观察121次无异常。clean/package之后先实际运行protocol166项补齐live输入，再运行全套；依赖顺序与失败即停已写入 `scripts/README.md`，没有复制旧XML。
- 当前留存JAR及6份连接日志摘要逐项校验通过，独立清理记录 `default-runner-independent-cleanup.json` 核对12类审计标签、审计Java、私密夹具及全部脚本临时目录均0。121源数据库gate1正23负仍匹配未变的数据库/迁移代码范围；不是新的应用部署。

先前当前索引按原字节保存为 `artifacts/validation/history/current-worktree-pre-default-runner-20261026.json`，SHA256 `e873b87a21ebfeabe93b78b86dd121b7956009d70be45f9983f28ff08b183ba2`。上节27项PG、旧1236/119/360报告保留其修复前源码范围，不改摘要或迁移集合使其覆盖本轮。实际应用及脚本回归均已按修复后的源重新执行；这些分项仍不等于同冻结配置的整体验收。

## V34引擎pipeline与真实Auth整链（2026-10-26）

实际五应用整合暴露出V32的`pipeline_success_evidence`统一要求Metadata回执：Spark/Flink虽已有封存引擎回执和成功结果，仍不能放行SQL下游。新增两项真实PG回归在V33上分别失败，原29项通过，未将业务行缺失解释为成功。V34仅替换该函数：SQL/quality继续要求Metadata回执；Spark/Flink严格核对原actor/批准/pipeline节点/instance/attempt/fence及封存材料、引擎receipt/result摘要、origin/key/handle和远端成功。V1—V33原字节保持，现为122份迁移、workflow34份。

- 当前常规回归：`output/audit-engine-execution/unit-v34-full-3ad5850011654fb4a9946c08e4e6cdb3/report.json`，run `3ad5850011654fb4a9946c08e4e6cdb3`，1236项/182suite全部通过，无失败或跳过；SHA256 `81ef1770fa0a3e816694f87e2d17e37f52f85d7f76b6b74dc8a69666b170359b`。
- 当前PG：`output/audit-workflow-engine-authority/run-20261026-v34-pipeline-green/report.json`，run `61c2f792e2db4a8c83e6000f8b441401`，31项通过、7项producer检查通过；SHA256 `5795c2b92c0544921ee7fc5988aa80be8647b46040f942c50f53ac361701616c`。实际按V32→V33→V34逐步升级，33份旧history/checksum/success及原SQL/quality批准保持，五类清理均true。Auth决策与引擎业务结果仍为受控响应。
- 当前六应用：`output/audit-workflow-quality-child/run-20261026-v34/report.json`，run `0bc2f71727234095b38d527067677451`，119项通过；SHA256 `542e86f06a5ce4b334c3fa2762d0a472bfc0ebe5903d726455cac839c7df942c`。实际重新构建并运行Auth/System/Metadata/Quality/Workflow/Gateway，SQL/quality手动、实际Quartz、pipeline及两类撤权零reader连接均通过，6份连接附件与五类清理通过。留存6个实际JAR的manifest SHA256 `d65e1e46d2b0e015d81d3b7b0fa9bc097997c6fe9622ad624c0be47a73078d14`；`v34-six-app-jar-parity.json`逐字节核对当前类、资源及共享库通过。
- 当前真实Auth引擎链：`output/audit-workflow-engine-chain/run-20261026-real-auth-v5-v34/report.json`，run `319d4161d3c44f28a15b426f6a8da483`，92项通过；SHA256 `9cd3d94c65560628ed25fea6eae4051f4ccfe6c79e680dcb92f7d6d84a154b66`。使用上项同一构建中的5个JAR，119项旧运行结果没有复制进新报告。真实非管理员actor与独立批准者、manual/实际Quartz/pipeline来源、丢响应仅一次POST、原origin读回、取消确认前保留容量、Flink session后撤权不提交INSERT，以及Spark→Flink→SQL三节点和实际writer行均通过。排队后撤权、任务停用、定义篡改、artifact撤销、批准者禁用、同名账号重建及Auth不可用均零引擎派发；4条真实Auth拒绝事件落库。53条脱敏HTTP trace完整留存且无截断，五类应用清理及两类peer清理均true。

- 当前脚本：`output/audit-engine-execution/scripts-8d7567b083604edc90a4974aa9c1d70b/script-validation.json`，run `8d7567b083604edc90a4974aa9c1d70b`，369项通过无跳过，Spark脚本25项中21项执行通过/4项跳过；SHA256 `b5f50064f584a375dee7e4376d654a736e892e9682b5cf39ac1a097efaa08c60`。源摘要始末一致，自有临时目录已移除；原子替换121次、0异常。打包后实际重跑protocol166项再执行脚本，没有复制旧XML；旧360/368项报告保留各自源码范围。
- 当前数据库准入：`output/audit-database-deploy-gate/run-20261026-122/report.json`，run `02fe59f578304b87a5243f68bcda89c5`，122源1正23负通过；SHA256 `e94b1e7eb05653c307782a8035f19318ee176ce8d5245e6a40a9c8b3c142c771`。源摘要、数据库未变、迁移凭据退出及四类清理均通过；manifest为明确的unsigned synthetic fixture，不是正式签名发布或九服务部署。

独立清理 `output/audit-engine-execution/preflight-20261026/engine-chain-independent-cleanup.json` 核对13类审计标签的容器/网络/卷、全部工作区Java、私密key/数据库夹具及脚本/依赖临时目录均0。当前分项索引为 `artifacts/validation/current-worktree-20261024.json`，保持partial、releaseEligible=false、frozenReleaseContext=false；此前索引原字节留存 `artifacts/validation/history/current-worktree-pre-engine-chain-20261026.json`，SHA256 `3585ca525bb2b38636aeb83a1636f1069cb1e1c204dd07dbcc9813908e6a69c3`。文档完整性、workflow契约及diff检查只证明对应静态范围；镜像严格门禁仍以两项未解锁非零阻断，当前索引的最终字节拒绝验证另存 `output/audit-engine-execution/preflight-20261026/v34-partial-index-release-rejection.json`。

这组HTTP peers不执行Spark/Flink业务代码。未验证物理引擎的账号、挂载、凭据与网络/IO隔离；未完成Flink新attempt暂停/恢复/升级、包含全部四类型节点的同一pipeline、进程终止矩阵、真实浏览器、九数据源、九服务同冻结制品、约定旧版/备份恢复和八类正式producer。两项MinIO锁仍阻断发布，S08/R03不能关闭。

本轮失败均原样保留，并另附`failure-analysis.json`：`run-20261026-real-auth-v1`在Docker未运行时停止，0项；v2在25项后因独立Python缺时区数据停止；v3在65项后因新增psql夹具输出不是单个JSON停止；v4在77项后暴露混合pipeline缺陷且业务行缺失被空JSON解析遮蔽；`run-20261026-pipeline-receipt-red`为31项中明确2项失败。修复夹具仅改为顶层SELECT和显式JSON输出，没有放宽断言或改旧报告。

Docker启动另有独立环境故障：`dockerInference`零字节通信端点出现Windows 1920，后端启动崩溃。核实本轮进程路径/启动时间后退出崩溃实例，单端点不能改名，再将仅含两个零字节通信端点的`Docker/run`目录改名保留；未删镜像、容器、卷或配置。引擎版本28.3.2实际就绪探针通过，原通信目录仍在`run.audit-quarantine-20261026T0453`以供回滚。这是本次恢复结果，不是Windows重解析项异常的长期根因修复。独立Python补入官方固定`tzdata==2026.5`并留存wheel摘要，容器创建前检查时区；早前Python3.11/PS7及原子替换异常继续保留原结论。

## F-ID逐项处置索引（阶段0）

下表将 2026-10-08 全仓审查中的 F01–F35 逐项绑定到工作包。状态只描述当前证据强度：`已实现` 不等于 `已验证`；`部分验证`、`未验证`、`阻断` 和 `环境异常` 均不能通过最终发布门禁。

| 报告发现（来源/影响条件） | 处置去向与当前实现 | 当前证据 | 剩余工作及验收标准 |
| --- | --- | --- | --- |
| F01 SQL 守卫换行绕过（R/S；具备写入或高权限连接时放大） | S01；结构、词法和只读/写入守卫已修 | 26 例先红及隔离 PG 分项记录；源码指纹早于当前冻结版 | Hive/实际目标引擎与副作用负例；同冻结源码、连接账号和 run 全部通过 |
| F02 平台库暴露为普通数据源（S/U；可触达平台表时） | S03；schema/reader/writer 角色和状态门禁已实现 | 109 源九 schema 336 项真实 PG 分项 | 九应用实际启动、存量 DB inventory、平台表/文件旁路均拒绝 |
| F03 AI 丢失用户权限（S/U；受限用户调用数据工具时） | P05/S08；用户快照、工具重验和预算已实现 | AI 62 项及 7 项 PG 分项，metadata/auth 仍为签名替身 | 同冻结 JAR+PG 的受限/越权差分，工具调用主体和资源范围完整落库 |
| F04 伪造身份头（S/U；内部端口或被控服务可达时） | S04/S08；签名、audience、nonce 和主体检查已实现 | protocol160、Servlet60+2 IT、三服务分项 | 九服务及 pipeline/child/Spark/Flink 的篡改、撤权、禁用负例 |
| F05 撤销最后授权恢复全读（R；撤销末 grant 时） | S07；授权模型已改，逐项撤销单调性未单列 | 台账现仅有分包权限证据，缺 F05 专项 run | 同角色/资源撤销前后允许集合单调不扩大，历史兼容行隔离 |
| F06 dashboard 使用错误权限类型（S；看板列表/数据集访问时） | S07/P03；映射修复方向已登记 | 原报告明确，当前无独立真实 API/浏览器证据 | dataset/dashboard 类型、逐条过滤、翻页变更和后续页授权实测 |
| F07 serving 使用空上下文（S；空间/域继承授权时） | S07；权限上下文接口已接 | serving 分项 115 项及 1 项 PG | 同冻结版跨域、撤权和列表/单条一致性验证 |
| F08 查询入口绕过字段/资源范围（S/U；SQL/metric/table 入口） | S01/S07/S06；窄 query/write 和字段白名单已实现 | published-key 102/102 与 SQL 分项 | 九类入口统一拒绝旁路，敏感字段必须服务端约束 |
| F09 禁用/降权/改密旧令牌仍有效（S/U；旧 JWT/session） | S09/S10；access/session version 和 CAS 已实现 | 单测及部分 PG 生命周期证据 | 真实 PG、双标签页、登出/改密/禁用/降权浏览器验证 |
| F10 serving V3 重复迁移（R；启动 Flyway 时） | E05；当前源已为单一版本序列 | 当前源 117 个、旧失败历史保留 | 读取存量 Flyway history，按明确旧版本升级/回滚，禁止盲目 repair |
| F11 CI smoke 主密钥非法（R/S；CI 初始化 metadata 时） | E08；固定合法 32-byte Base64 已静态修复 | CI 源码校验通过，云端未运行 | 同提交 CI/smoke 启动且密钥不落日志 |
| F12 JDBC URL 校验旁路（R/S；创建/修改数据源且驱动可用时） | S02；参数、URL、TLS、DNS 和重定向校验已实现 | 出站模块与 loopback 分项 | TLS、真实引擎和 DNS rebinding 负例 |
| F13 Flink health 任意出站地址（R/S；登录用户可控 URL 时） | S02；目标允许清单与精确 origin 已实现 | loopback 与危险目标分项 | 真实 Flink/网络层目标隔离，禁止重定向及云元数据 |
| F14 单 schema 同步误标其他 schema stale（R；多 schema 采集时） | S01/S03；同步范围与角色契约已改 | PG 角色/迁移分项 | 多 schema 实际采集核对仅更新目标 schema |
| F15 SQL unknown 当失败重试（S/U；写入响应丢失时） | R03；unknown、fencing、对账和不重投已实现 | workflow/ingestion/quality PG 分项 | 真实写入丢响应、晚完成、重启和重复提交零副作用 |
| F16 pipeline 远端超时被确定失败（S/U；远端仍运行时） | R03/S08；unknown 等待和对账模型已实现 | 分项受控时钟证据 | pipeline/Spark/Flink 实际远端故障注入及结果凭证 |
| F17 running 崩溃永久阻塞（S/U；ingestion/quality 进程崩溃时） | R02/R03；租约、恢复和 pending 对账已实现 | Quartz JobStoreTX 12 项真实 PG | 双实例、重启、过期租约和物理资源释放实测 |
| F18 Flink HTTP 无明确超时（S/U；health/poll/cleanup 阻塞时） | S02/R04；客户端和 poll deadline 已加边界 | 单测/客户端分项 | 实际 Flink 超时、取消和清理线程不泄漏 |
| F19 PowerShell 备份恢复链不可靠（R/S/U；Windows 运行恢复时） | E04/E09；容器 pg_dump/restore、头校验和清理已实现 | 模拟及一次隔离 PG；工作区测试曾受 Temp 权限影响 | 干净 PG 备份→损坏归档拒绝→恢复，中文/二进制/NULL/序列/耗时完整记录 |
| F20 旧 passed 遮蔽新失败（R/S；发布门禁选历史附件时） | E01；context/manifest/report 显式绑定并拒绝历史复制 | strict 行为测试和旧报告拒绝分项 | 同冻结源码、配置、迁移、制品的八类 producer 全部新跑 |
| F21 GHCR 与 Compose 名称不一致（S/U；部署拉取错误镜像时） | E08；部署脚本已显式设置前缀 | 静态 compose/deploy 检查 | 固定 digest 拉取、compose 展开、健康和回滚实测 |
| F22 CI/扫描/签名不是部署前置（S/U；失败制品仍可部署时） | E08；门禁逻辑已实现 | 16/18 镜像已锁，2 MinIO 401；未构建/扫描/签验 | 可获取来源、SBOM、扫描、签名/验签及部署准入全部同提交绑定 |
| F23 业务日期缺少严格校验（S；午夜/月末/闰日输入时） | R01；服务端日期、Clock、占位符二次校验已实现 | 单测和 workflow 分项 | 手动/cron/pipeline/misfire 在最终冻结版一致 |
| F24 domain code 列表/单条不一致（R；范围授权为 domain 时） | S07；AND 范围和 domainCode 契约已实现 | auth/metadata 分项 | 列表、单条、翻页期间变更真实一致 |
| F25 多服务 GET 全读（S；管理面或受限主体读取时） | S07/S08；资源过滤和本人范围已接 | MVC/权限分项 | 九服务真实主体/资源范围负例，不能由前端按钮代替 |
| F26 网关 may-mutate 与服务 may-manage 矛盾（S；委托定义/授权修改时） | S07/S08；窄委托和批准者契约已实现 | auth/workflow 分项 | 定义篡改、撤权、批准链和固定 receipt 全链实测 |
| F27 本地 Spark 制品可直接 approved（S；未批准制品进入执行时） | E08/S08；制品 hash、批准和 runner 绑定已接 | 静态/分项检查 | 实际 Spark runner 拒绝未批准或错 commit/blob 的制品 |
| F28 Runner 重启丢失超时监控（S/U；宿主重启而子进程存活时） | R03；租约与恢复模型已实现 | 分项恢复测试 | 实际 runner 重启、存活进程再绑定、超时和清理 |
| F29 `.env.example` 配置未完整传容器（S；维护/Flink 默认启动时） | E08；配置项已登记 | 仅静态 compose/config 证据 | clean compose 展开和服务启动逐项核对 |
| F30 内部表/方言与宣称范围不一致（S/U；Hive/Thrift/多引擎查询时） | E06/S01；目标引擎和金额口径已定义 | Spark 隔离 33 项及数仓分项 | Thrift/JDBC/平台加工链实际目标引擎对账 |
| F31 前端旧响应覆盖新状态（S；并发请求/卸载/切换时） | R04；epoch、AbortSignal、串行轮询已实现 | hook/API 分项 | 双标签页、聊天切换、Spark/Flink 轮询和页面卸载真实浏览器 |
| F32 前端 admin/viewer 门禁缺少委托 RBAC（S；委托主体操作时） | S07/P02；后端契约和部分提示已接 | JSDOM/分项测试 | 真实浏览器仅作呈现验证，最终以服务端授权矩阵为准 |
| F33 生产包陈旧依赖公告（V；制品包含受影响坐标时） | E08/P09；历史静态命中保留 | 原报告 384 坐标/242 命中，当前制品 SBOM 未生成 | 冻结制品 SBOM/OSV/镜像扫描，按可达性绑定门禁 |
| F34 前端公共包体积大（R/S；低带宽/首屏时） | P02/P09；渐进 lint 和构建治理 | type-check/build 通过但有 chunk 警告 | 先测量预算再决定拆分；不能以格式化替代验收 |
| F35 报告脚本使用 Python 3.12 语法（S；CI Python 3.11 执行时） | P06/E01；独立 Python 3.12 回归通过 | 332 项工作区回归；CI 3.11 未执行 | 兼容基线明确，CI 同提交运行；保留 PS7/Temp 环境失败 |

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

## W0补充：N01–N22逐项处置与新版工作包映射（2026-10-26）

本节只追加本轮状态，前文按原日期、源码及运行范围保留；不改写旧成功、失败、跳过或环境异常。发现来源为[2026-10-26全项目复核](../reviews/2026-10-26-project-gap-review.md)，执行与验收入口为[W0–W6目标](../next-goals-2026-10-26.md)。下表中的文件名与行号用于定位复核时的代码，不表示后续修复后仍是同一行。关联S/R/E/P表示承接关系，不表示新增问题与某项原报告发现完全相同。

W0持续登记原22项及后续N23，W6负责同一冻结源码、制品、配置和迁移集合下的独立总验；表内W1–W5是各项实施归属。N01/N02、N03/N04/N06、N16–N18和N23已有下列范围内的实现与证据；其余状态按逐项记录，不能将组件或浏览器分项数量计作所有N项整体关闭。

### 来源追溯与局部证据登记

- 两份原附件仍缺失。本轮仅在本仓库追溯：`git rev-list --all --count`为138，加入`--reflog`为145；对全部refs/reflog使用大小写不敏感的根路径及递归路径查询，`git log --full-history`和`git rev-list --objects`均未找到`Aetheris_mimo.md`、`Aetheris_deepseek.md`同名路径。`git fsck --full --no-reflogs --unreachable --no-progress`退出0，对列出的7个不可达commit再作同名全历史查询也无匹配。未恢复文件，未扫描仓库外用户目录。这只说明本次可追溯Git历史中未找到同名原附件，不能证明它们从未在外部、未跟踪目录或其他文件名下存在。继续沿用现有M/D映射并保留来源缺口；未取得原文前不能声称两份原报告已重新逐条复核完毕。
- 复核基准证据：[只读盘点报告](../../output/audit-project-review-20261026-fd8faf07c76b/report.json)，SHA256 `0fedb917915744444d227254c98b3add3cb4447db31907fe65f610c8d5d27a75`。N01中的React Query探针是实际纯内存复现，第二主体查询回调未被调用并取得前主体缓存；它不是浏览器或真实登录整链复现。其余未修复项的等级沿用源码确认，不新增运行通过结论。
- **N03局部证据**：[验收页报告](../../output/audit-acceptance-ui/d637078ce5ff4f3790e7a92a19f54da8/report.json)，run `d637078ce5ff4f3790e7a92a19f54da8`，SHA256 `264ca3319e5c47ff96e202591653d21ce656db62cc8d89e9c9a37ead3a130125`。`status=passed-local`、`frontendLocalValidationOnly=true`、`releaseEligible=false`；包含先红后绿和本地57项断言，使用替身HTTP及实际前端解析、状态与刷新逻辑。**前端局部验证，后端当前runtime binding/正式verifier endpoint尚未接入**；没有实际后端、浏览器、附件字节核验或正式producer验收，也没有全量typecheck/build。不能把客户端`verified`正例替身当作服务端已具备可信证明。
- **N04局部证据**：[模式契约报告](../../output/audit-flink-mode/f7eb623b4e2c4a3da1c865b673e210c0/report.json)，run `f7eb623b4e2c4a3da1c865b673e210c0`，SHA256 `27acfd32a9b85665bb05b310a90653fcfa3493a93a06edce0a2a75f16e4d6fa4`。公开创建/批准MockMvc、真实store/material和签名Auth peer的6项局部测试先红后绿；DB projection是夹具。**未运行PostgreSQL或物理Flink，不是发布验收**。
- **N06局部证据**：[终态分类报告](../../output/audit-flink-terminal/973e6a514d8449128b2159554c9858e5/report.json)，run `973e6a514d8449128b2159554c9858e5`，SHA256 `1741e410a3262900bbfdc5bc19734e945a97e6ffe684911e20e063c5f49cf5d1`。执行器终态分类9项单测先红后绿，source与receipt持久化是夹具；新增PG断言仅编译，未执行。**未运行PostgreSQL、物理Flink或DAG整链验收**。

上述报告按自身记录的源码、时间和范围解释，不因本次台账追加而改摘要、换路径或绑定当前全部工作树。下一次实现或配置变更后按受影响范围生成新证据，旧失败和旧局部结果继续保留。

### N01–N22处置表

| 项 | 实施归属 / 关联原包 | 来源与触发条件 | 当前实现与证据级别 | 剩余工作与可验证验收 |
| --- | --- | --- | --- | --- |
| N01 | W1；S09/S10/S13/P02/P03 | 复核N01；原`DashboardWidgetContent.tsx:128`的key只有datasetId，`main.tsx:15`全局缓存10秒；SPA更换主体时命中前主体缓存。 | **客户端已实现，组件及受控HTTP浏览器分项验证**。主体UID、会话/权限代际与取消/清缓存已接，报告见下方W1补充；N23另有真实Auth会话证据。尚无真实业务数据下完整页面权限矩阵的证明。 | 验真实A登出B登录、同名账号重建、撤权和登出时请求未返回；同datasetId不同权限数据不得串读，旧响应不得回填新会话。正常授权查询仍成功，不把N23的会话检查代替全部业务页面。 |
| N02 | W1；S08/S09/S10/S13/R04 | 复核N02；原`api/http.ts:42`在refresh或`/me`后重发旧config，原主体写请求可能随新会话执行；批准接口已有单独禁止重试。 | **客户端已实现，组件及受控HTTP浏览器分项验证**。固定发起主体/代际，修改请求401不自动重放，仅同主体安全读取可重试一次；报告已归档，见W1补充。真实业务副作用矩阵仍待验。 | 验A写请求延迟401后切B、改密/降权/禁用，零旧写重发与零业务写入；同主体合法刷新仍成功，批准接口保持单次语义。不能以HTTP夹具零重发代替真实业务库零写入。 |
| N03 | W1；E01/E02/E07/P09 | 复核N03；原`acceptance/index.tsx:141/191`信任摘要并无条件显示九源成功，`api/acceptance.ts:67`仅类型断言；public schema1无当前run/context。 | **前端已实现、局部验证，整链未接通**。见N03报告；历史明确分离，当前DTO校验、AbortSignal、有界刷新与到期降级已有本地正负例。后端可信绑定/正式verifier未部署。 | 服务端从当前安装身份独立派生binding并核验源码、必要制品、配置、迁移、八gate及实际附件；九源业务断言不得用HTTP200代替。验：真实有效当前证据显示成功；历史、当前失败、过期、错上下文、缺附件/替换附件及404/403/503均不假绿；浏览器验证刷新、乱序、卸载、到期，不能靠永久禁用成功态收口。 |
| N04 | W2；S08/R03/E03 | 复核N04；`TaskController.java:195`/`TaskFormModal.tsx:193`公开bounded与`WorkflowEngineMaterial.java:103/112`原batch约定冲突。 | **模式边界已修复，公开入口/真实PG分项验证**。原局部报告保留；下方五服务113项报告验证当前公开bounded创建/批准/封存及手动、cron、pipeline协议路径。Flink物理结果仍为受控HTTP。 | 继续验证真实存量值、实际引擎执行与业务输出；不合法模式及未批准定义在物理提交前拒绝，当前HTTP peer成功不能关闭物理验收。 |
| N05 | W2；S08/R03/R04/E03 | 复核N05；`TaskExecutionService.java:552`有意拒绝sealed attempt使用旧生命周期接口，新executor尚无完整替代。 | **未修复，源码确认的功能缺口**。旧拒绝是安全边界，不能回退到共享令牌或把pipeline伪装成manual。 | 原作业savepoint/pause/stop/cancel绑定独立持久化操作与fence；resume/upgrade实际重新提交才建新批准绑定/attempt。验物理Flink保存点、恢复升级、撤权、丢响应及回退；旧停止unknown时不释放容量、不新投，恢复/重试不能仅凭用户名。 |
| N06 | W2；R03/S08/E03 | 复核N06；原`WorkflowFlinkExecutor.java:41`把所有FINISHED判成功，与旧reconciler对无界任务意外结束的语义冲突。 | **终态分类已修复，单测/真实PG及五服务分项验证**。31项PG与113项五服务报告含有界成功、无界意外完成失败且SQL下游零attempt/写入；后者使用实际Auth，物理引擎结果仍为HTTP peer。 | 继续验物理bounded完成、streaming意外结束及有批准停止/升级意图的不同终态，receipt/attempt持久化一致；unknown先对账，状态文字不能替代业务完整性检查。 |
| N07 | W2；S07/S08/P03 | 复核N07；`LakehouseTableController.java:29`要求lakehouse_table，Auth的`RbacConstants.java:52`及`resourceAllowed`目录未注册，用户决策前即拒绝caller。 | **未修复，源码确认**。不能因管理员也失败而放开任意资源类型，尚无真实Auth正例。 | 补窄资源/操作/上下文目录。验真实Auth下管理员及指定授权用户正常查询；跨表、撤权、错caller、未知资源拒绝，无额外数据库读取；权限服务故障明确不可用而非假空。 |
| N08 | W2；S08/R01/R03/E03 | 复核N08；`LakehouseMaintenanceExecutionClient.java:25`及Schema change沿用旧token/maintenance路由，当前Workflow proof目录不接收。 | **未修复，源码确认**。没有Metadata→Workflow→Runner当前可信维护/Schema执行闭环。 | 定义窄v2主体、批准定义、资源、业务日期、派发/对账凭证。验真实维护及Schema change执行和读回，撤权/定义篡改零派发；超时/重启先查原attempt，禁止恢复旧共享token旁路。 |
| N09 | W2；S08/E01/E02 | 复核N09；`ReleaseGovernanceService.java:63/85`表审批影响分析与质量门禁仍调用旧reader，目标V2过滤拒绝。 | **未修复，源码确认**。已有发布记录接口不等于审批/质量证据整链可用。 | 以窄只读操作读取目标版本影响及质量结果，绑定版本、来源与新鲜度。验真实表审批和带质量门禁发布登记成功；过期、错目标、篡改、撤权与权限服务故障阻断。实际生效语义另见N22。 |
| N10 | W2；S08/P04/E03 | 复核N10；`LineageEvidenceClient.java:59`是旧客户端，新fixed SQL/Spark/Flink成功路径缺替代写入；sealed Spark提前分支，不能误称其仍调用旧client。 | **未修复，源码确认的接线遗漏**。没有当前成功结果到血缘的完整运行证据。 | 以同attempt/result/hash幂等写入并可读回，失败可诊断和补偿。验真实SQL/Spark/Flink任务后来源证据一致，重复/重启不重写，伪造或跨资源拒绝；血缘失败不得伪造业务回滚或吞掉缺口。 |
| N11 | W3；S07/P03 | 复核 N11 原缺陷；Quality 历史/隔离、Ingestion/Quality/Governance 主列表和 Workflow 实例原先存在 LIMIT 后授权或全量加载后过滤。 | **已实现，聚焦验证**：四类服务新增 ID keyset `/page`，游标绑定主体/accessVersion/筛选/size，5000 候选扫描预算，逐窗口批量授权；认证旧数组超过 200 条显式 503。Maven 聚焦测试与共享扫描器已通过；真实 PostgreSQL、>1000 条、浏览器翻页变化/撤权、Auth503 仍未验证。 | W3/W6：同一冻结版真实 Auth/数据库/浏览器证明合法资源完整可见，故障不假空，无全量无界加载或静默截断。 |
| N12 | W2；S01/S08/E02/E06 | 复核N12；`LakehouseQualityGate.java:13`空观测返回allowed，goldGate仅查询消费，未约束实际Gold派发/发布。 | **未修复，源码确认**。查询接口不构成强制门禁，当前没有producer到规则版本/表快照的证据闭环。 | 定义必需规则与证据新鲜度并接入实际推进点。验缺规则、过期、错snapshot、failed/unknown零推进；真实生产者产出同版本合格结果后才允许执行，核对实际目标数据与凭证。 |
| N13 | W5；S07/P07/E03 | 复核N13；`MetricDefinitionService.java:243`、`LogicalModelService.java:201`、`SemanticDimensionService.java:183`直接下线/清publishedVersion，无消费者依赖阻断。 | **未修复，源码确认**。基础语义登记已存在，不能把多表/派生指标等后续规划混入本项关闭。 | 查实际dataset/dashboard/API依赖，阻断仍被发布消费者引用的下线，或执行明确迁移流程。验新旧版本、并发发布/下线、权限撤销及迁移后消费结果；失败不留下半下线状态。 |
| N14 | W4；S04/S08/E05/E08/E09 | 复核N14；`AuthV2SecurityConfiguration.java:93`等强制密钥/registry，根Compose十个Java服务及roles override未配置；`ci.yml:130`裸启动，installation.py只生成。 | **未修复，源码确认的默认交付缺口**。现有真实JAR producer临时注入的身份配置不证明默认安装可用。 | 准备正式安装入口、独立密钥/registry文件权限和最小DB账号，以及具体变更/回滚。验干净目录/配置启动Gateway及九服务、缺配置预检拒绝；不能依赖开发机私有文件或宽权限账号，现有环境不被替换。 |
| N15 | W4；E03/E08/P01/P06 | 复核N15；`platform-build.yml:48`逐服务test未先准备本仓SNAPSHOT安全库或用根reactor；主CI已有根reactor。 | **未修复，源码确认准备缺口，冷CI未复现**。不能表述所有CI均坏，亦不能将本机缓存测试视为干净runner成功。 | 用明确reactor或共享库准备。验空Maven缓存和干净Linux runner实际执行；Windows/Linux原始字节、依赖准备及支持Python版本一致。保留第一次失败和环境诊断，重试成功不覆盖历史。 |
| N16 | W3；R04/P02 | 复核N16；原workflow页迟到A依赖覆盖B，删除却用当前selectedId，数据与修改对象错配。 | **已实现，React组件分项验证，真实页面浏览器待验**。详情/删除绑定对象和代际，响应owner不符保持未知。15项工作流回归包含A→B→A、旧删除完成和候选依赖方向。 | 以真实浏览器复验切换、关闭/重开和删除时切换；组件测试不能替代实际对象副作用。 |
| N17 | W3；R04/P03 | 复核N17；原workflow页无界Promise.all，并将依赖失败转为空数组。 | **已实现，有界读取/取消/逐项未知与重试，组件分项验证**。403/503/timeout不伪造零依赖；祖先、后代和已存在边均约束候选项。 | 真实浏览器及大规模列表的负载、取消、部分失败仍待验证；不能显示不完整图为完整。 |
| N18 | W3；R04/P02 | 复核N18；原表详情标题先切换后无条件写旧字段/预览，catch吞权限和连接错误。 | **已实现，11项组件回归通过，真实页面浏览器待验**。拆出useTableDetail，字段与预览分别取消/标记代际、保持可见错误并重试。 | 实际切表、关闭、乱序、403/503、空结果及卸载仍需浏览器证据，不将会话浏览器14项移作本页证明。 |
| N19 | W3、W5；R04/P05 | 复核N19；`ai/chatbi/index.tsx:31`无同步请求锁/代际，Enter与示例可重复发送，旧回答无条件写回。 | **未修复，源码确认，浏览器未复现**。普通聊天已有epoch；服务端历史/预算已有实现，不得归为全部缺失。 | 固定免费模型替身验证重复触发、超时、取消、切换、卸载；当前提问仅有对应回答，旧响应不覆盖，重复动作不重复扣预算；真实Auth工具权限/预算整合仍须W5验收。 |
| N20 | W3；R04/P02 | 复核N20；`dashboards/stage.tsx:23/45`、`editor.tsx:84`失败只toast，无可重试错误态或路由旧响应隔离。 | **未修复，源码确认，浏览器未复现**。与N01缓存主体隔离分别验收，不能一项修复代另一项。 | 验浏览器404/403/503退出加载并可重试，A→B不出现旧画布；编辑/预览/发布视图及卸载都保持对象边界，正常合法数据可打开。 |
| N21 | W3；R03/P02/P04 | 复核N21；原`operations/tasks/index.tsx:46`只统计success/failed，终态分母为零显示100%，全timed_out可显示零失败/100%成功。 | **已修复展示口径，函数/组件分项验证**。success/(success+failed+timed_out)，cancelled/skipped/unknown/运行态单列排除；无完成样本显示“无样本”，范围明确为当前返回最多100条。后端无另一成功率聚合公式；先LIMIT再授权的完整性仍归N11。 | 固定全超时、全unknown、空及混合终态已在组件验证；真实浏览器、全量分页和错误/旧响应页面整合仍待验。不改真实执行或恢复语义，不把当前样本当全部任务统计。 |
| N22 | W5；E01/E02/P07/P09 | 复核N22；原`development/releases/index.tsx:173`承诺下游生效/回滚停止，`ReleaseGovernanceService.java:77`、`ReleaseRecordController.java:142`只改记录状态。 | **登记语义已修复，组件分项验证**。状态/动作/确认提示与实际登记副作用一致；原API保持，不新增实际部署、下游停用或数据回退。实际消费方切换/停止仍未实现。 | 真实浏览器复验登记状态及失败提示；若以后承诺实际生效，须不可变目标版本、执行回执、消费方readback与回退验证。当前N09审批/质量接线缺口另行处理。 |

### 与既有F01–F35及S/R/E/P的交叉索引

本轮核对既有台账仍包含F01–F35全部标识及S01–S13、R01–R04、E01–E09、P01–P09全部工作包；原F表已有逐项处置去向，未发现需要编造原文来补齐的缺号。下表补充新版工作包承接，既有逐项验收及其失败记录继续有效，不由N表替换。

| 原工作包 | 新工作包承接 | 必须保留的原验收边界 |
| --- | --- | --- |
| S01–S03、S11–S12 | W1、W2、W4；最终W6 | SQL/JDBC/TLS/出站、明确active才可建连、历史秘密DTO与存量连接预检、九类真实源及平台/业务数据库最小权限；停用/越权须证明零连接或零写入。 |
| S04–S10、S13 | W1、W2；最终W6 | 签名主体、稳定UID、API Key、禁用/撤权/改密、所有执行入口及浏览器会话；原权限资源类型、字段/域约束不能被缓存修复代替。 |
| R01–R03 | W2；最终W6 | 严格业务日期及午夜/月末/闰日/补跑/misfire、批准/binding/generation、双实例/回滚/重启、attempt/fence/租约/receipt、unknown先对账及物理取消；四类型须在同一pipeline验证。 |
| R04、P02–P03 | W3、W5；最终W6 | 全服务合法资源完整可见、稳定分页/有界授权、故障不假空，复用已存在的聊天epoch和Spark/Flink串行轮询；真实浏览器并发、卸载及旧响应矩阵仍待验。 |
| E01–E03、E08 | W4；最终W6 | 旧成功/当前失败/错版本/缺项/附件替换必须阻断；两项MinIO锁、冷CI/V2安装及实际构建、扫描、签验、部署准入和八类真实producer不能省略。 |
| E04–E06 | W4；最终W6 | 干净新装、核实旧库Flyway history后明确升级、中文/二进制/NULL/约束/序列/损坏归档恢复及耗时/清理；数仓实付、优惠、运费、缺失金额口径后经目标引擎/JDBC/调度跨层对账，不盲目repair。 |
| E07、E09、P06–P09 | W0、W4、W5；最终W6 | 文档与支持描述、私密状态/忽略、依赖与Runner制品来源、Wrapper/版本/许可证、临时文件和危险清理边界；主密钥真实变更须先具备重加密、验证与回滚。 |
| P01、P04–P05 | W5；最终W6 | 测量连接/建连/并发后定预算，保留凭据变化/撤权/回收/物理取消；requestId、结构化诊断、query_history隐私/范围、真实告警投递及安全保留，AI历史/工具权限/轮次/耗时/token预算用免费替身整合。 |

F项之间的覆盖关系需按原问题分别关闭：N01不等于F06的资源权限类型修复；N03前端历史分离不等于F20正式发布门禁验收；N04/N06局部测试不关闭F16/F18的真实执行与超时边界；N16–N20不能替代F31要求的聊天切换、Spark/Flink轮询、卸载和旧响应浏览器验证。F01–F35的旧实现/证据与本轮新增缺口并存，后续逐项引用新冻结结果，而不是将旧表统一改为“通过”。

当前W0仅完成本节台账增补及受限来源追溯；原附件来源、未修N项、必要整合验收及正式发布阻断继续保留。长期容量/HA和客户生产验收依各自实际证据另行成立，现行八类producer中已有的隔离HA运行门禁仍须执行。

## W1本轮实现与验收补充（2026-10-26，会话与验收界面）

本节更新上述W0登记时仍在执行的状态，不覆盖旧范围报告。[本轮组件报告](../../output/audit-session-boundary-20261026/report.json) run `session-boundary-f430e142dad44be7aa54bebc1f388f7f`，SHA256 `9336d8267603116678395d0579fa55d21b603354a22e0cf3f669703267577c33`；`releaseEligible=false`，不是冻结整体验收。342个Web/Auth源码文件及111个前端dist文件有摘要；原正式分项索引仍为原SHA `9fa0c5cabf4979eec5c5b33c8d1e62ca15674c5140b9cc89e0c20c6923787790`，未重绑。

- **N01/N02已实现客户端边界并完成局部验证**：登录、refresh、me返回稳定UID与accessVersion/permissionVersion；查询按主体与会话代际隔离，变化时取消/清除缓存并卸载旧受保护页面。HTTP丢弃旧代响应，仅同主体安全读取可重试一次，POST/PUT/PATCH/DELETE的401不自动重放。登录、登出、refresh共用Web Locks；浏览器缺锁或存储不可读写时阻断认证，迟到storage事件不覆盖新登录。改密同步隔离旧会话，去掉页面延迟logout。后端family撤销及Cookie隔离现已实现，并通过N23所列真实浏览器及退出后批准SQL/Quartz/pipeline分项；全入口批准任务和整个S09/W1仍未关闭。
- **实际验证**：全量前端52文件/240项通过；随后仅对新增Promise处理、导航await及lint入口作小修，最终相关39项、type-check、18个关键文件lint、当前构建通过。Auth定点18项通过。真实Chromium完成11个会话检查和3个登录表单/验收页检查；两个标签页一次轮换、旧写零重放、旧读丢弃、A→登出→B、权限版本变化、禁用清界面均验证。浏览器HTTP是隔离夹具，实际业务库未启动，不能据此声称真实Auth/Gateway撤权或物理写入安全已验。
- **N03追加浏览器证据**：真实登录表单与真实页面、仓库原有两份历史public JSON；当前verifier返回404时保持0/5和未验证，两个历史passed只在历史区展示。当前runtime binding与正式后端verifier仍未接入。
- **失败与限制保留**：10项前端RED；Auth沙箱访问拒绝/中止（未获得新XML）、宿主15项RED中的缺DTO字段；旧测试夹具与期望调整前失败；逐步扩大lint后的Promise诊断；浏览器历史日期探针误按ISO显示而失败（实际页面使用toLocaleString）均留原文件。后一次通过不证明沙箱环境根因已解决。构建仍有antd/charts大chunk警告，未放宽阈值。
- **清理与边界**：自有Chromium contexts已关闭，两次Vite实例均退出并实测55173端口关闭，Java自有临时目录已删除；没有启动容器、操作现有数据库或真实密钥，也未提交、推送、部署。

### N23：并发刷新后的服务端登出撤销缺口（W1/S09，已实现，数据库/浏览器/批准 SQL 链分项验证通过）

原始发现来自本轮独立只读复核：原 Gateway 已按当前 access jti 撤销，但 refresh 先成功 CAS、logout 携旧 sv 更新 0 行后仍返回 204，迟到 refresh 可安装未拉黑的新 jti。该描述保留为修复前的历史原因，不能套用为当前实现状态。前端锁与 HTTP 夹具通过仍不能关闭整个问题。

实施边界与下一步：

1. 新增独立、持久化browser session family（sid、uid、签发时account AV、refresh CAS版本、到期、撤销），密码登录创建sid，refresh保留sid；USER proof和批准材料的account AV不改。现有jti黑名单继续保留。
2. access/refresh均绑定sid，Gateway使用专用gateway-only V2检查family；不把新要求强加到cron/pipeline固定主体的sessions/check。旧无sid token在统一切换后明确拒绝并重新密码登录，禁止按用户名补family。
3. 为覆盖过期access及被并发消耗的refresh，设计仅能撤销其sid的独立HttpOnly、SameSite、有限有效期logout凭证。旧refresh重放不得撤销新family；迟到的已撤销family Cookie即使安装也不能通过后端校验。若撤销无法确认，必须明确未确认，不报完成。
4. 只新增合法Auth迁移；先隔离新装/升级测试和Auth/Gateway统一切换、回滚方案，不操作现有库、不repair。不得用logout递增account AV的捷径破坏已批准任务。需验证原批准cron/pipeline在浏览器登出后仍遵守原批准与撤权契约。
5. 验收含refresh/logout两种顺序、响应丢失/迟到、access过期、refresh重放、旧sid logout对新sid无影响、双Gateway、重启与撤销库503。浏览器、实际Auth/Gateway、真实临时数据库均须参与；无确认状态不能复活或继续访问。

2026-10-26 当前执行证据：

- 新增 Auth V11 `browser_session`，以 sid、稳定 UID/name、账号 AV/SV、独立 refresh 版本和固定有效期实现持久化撤销；Gateway 专用 V2 family 检查不复用固定执行的 account session 检查。refresh/logout 不改账号 AV/SV；DB 故障返回 503，不能写成确认退出。独立 logout 凭证不随 refresh 轮换，Redis 故障不阻止撤销请求到达 Auth。
- `output/audit-browser-session-postgres/run-20261026-v2.json`，run `a38f555210b7462b9decd3ede34ad1af`，SHA256 `06d9e094cb14989fbdb9e5d0e0f60d49d19c252e79deeec63511755260b6a365`：真实 PostgreSQL 的 12 项测试通过，含两实例并发 CAS、两种撤销顺序、竞态、过期、同名重建、旧 family 与新 family 隔离、账号状态/版本变化。Auth V10→V11 保留旧 history/checksum；当前 123 份迁移及 runtime ACL 实测通过。源码首尾一致、五项自有资源清理均为 true。此报告不证明浏览器或实际任务批准链。
- `output/audit-browser-family/087562e57675460da19987057390f3e3/backend-all/index.json`：当次 Auth/Gateway/公共安全库 47 个 suite、480 项通过；前端 `frontend-final.xml` 72 项通过，之后新增“失败登录保留未确认撤销提示”1 项有效 RED，再与登录页合计 11 项 GREEN。它们只绑定当次组件源码；后续 Cookie 协议调整必须重验。
- 浏览器整合执行器 `scripts/audit-browser-session-runtime.py` 使用实际服务、两个 Gateway、Chromium 及缓冲真实响应的本地代理。`run-20261026-v1.json` 是 Hikari 环境变量绑定错误导致的夹具启动失败；`v2` 在前端构建阶段失败，旧执行器未留底层错误，单独构建成功不证明该异常根因已解决。两次均 0 项业务验收且清理完成。后续运行另用新 run，不能改写这些报告。
- 全局同名 Cookie 问题取得真实 RED：`output/audit-browser-session-runtime/run-20261026-v3.json`，run `3922e7b209bb4c0aa1d55187ae26af6b`，SHA256 `8094092c4ce1596201ad551cc9a7b2590dddc60203bf94cdbe127dd9f55e039c`。13 项中 9 通过、4 失败：A 迟到 refresh 使 B 的实际 `/me` UID 从 2 变 1，旧 logout 的 204/401/503 均使 B 实际请求变 401。已清理；不改写为成功。
- 改为共享 `BrowserSessionCookies` 的规范 sid selector 和每 family 独立 access/refresh/logout Cookie；只有密码登录写 selector，其他响应只能影响自己的 family。Auth/Gateway 两端拒绝 selector/凭据重复、旧 flat Cookie 回退和签名 sid 错配。新鲜 Java 回归 `cookie-unit-v1/index.json` 共 50 suite、509 项通过（SHA256 `582d7fd498aee58302db37409890dae75c6335bfafb63927351b751acd56a11a`）；详细路径仍在上列 `audit-browser-family` run 下。
- 真实 GREEN：`output/audit-browser-session-runtime/run-20261026-v4.json`，run `3732dcdc2e8c4bb08bf4f9eefdbf0b43`，SHA256 `404c11e78066b15b2901bae544f81e1fdd0fedc9e14888fe01663f04bf412a58`。14 项通过，使用实际 Auth/System、两个 Gateway、PostgreSQL/Redis 和 Chromium 155：包含原四条反例、双标签一次 refresh、重放无 Set-Cookie、已撤销 family 迟到响应、旧 family 退出不影响 B、跨 Gateway/重启、Redis 故障、DB503 的重载/失败登录后提示与重试、真实过期 access 与已消费 refresh 仍可独立退出。账号 AV/SV 前后均 0；这还不是批准任务运行证明。
- 该 GREEN 源码首尾一致，绑定 123 份迁移、三份实际 JAR、前端制品集合、四进程配置摘要、时间和 run；五项清理 true。独立 `independent-artifact-cleanup-check.json` 重新核对当前三份 JAR/前端字节匹配、标签容器和临时私钥不存在；记录的 Java PID 及该 TEMP 的 Chromium 进程查询为空。自有 TEMP 的 JVM/Node 缓存仍保留，未假称所有临时文件已经删除。
- 执行器的另 15 项轻量安全回归通过，含缺项/重复/失败/源码变化/清理失败不能 passed，输出冲突保留原字节，restart/pause 只作用本 run，代理 release 前不发送任何 HTTP 字节。
- 退出后批准任务链：`output/audit-workflow-sql-chain/run-20261026-browser-logout-v3.json`，run `fa5fa18a70294931b08ce628974d4071`，SHA256 `fadde486c157efd82688d9d1319512f04a02d274dbcdd3fd7df025a05cb66ffd`，五个实际服务、真实 PostgreSQL/Redis 的 80 项检查通过。执行人及批准人的 family 均已持久撤销、旧浏览器 `/me` 返回 401、账号 AV/SV 不变；随后原 Quartz 任务产生正确业务行和 writer。两节点 SQL pipeline 在自有业务表锁下确认真实 writer 等待，再退出两人、正常释放锁，下游 attempt 的 `queued_at` 晚于两次撤销，原 plan/invocation/approval 未变化。最终两行内容、日期、writer、receipt、fence、主体及批准者均核对；另行撤权后新 pipeline 403，零新增 attempt、run 和业务行。
- 上述 SQL run 源码首尾一致、当前 123 份迁移绑定、五项清理 true，独立 `independent-cleanup-check.json` 确认记录的五个 Java PID、锁进程 PID、该 run 标签容器和私钥目录均不存在。它不证明物理 Spark/Flink、混合四类型流水线或整个平台已验收。后续脚本异常清理修复也不能改绑该报告。
- 原 SQL `browser-logout-v1` 因 System 端口 63495 被占用而 0 项失败，精确占用者未确定；Windows 默认动态端口段实测为 49152–65535，随后夹具改用有限重试的 20000–29999 loopback 监听端口，不将端口原因推断写成环境根因已解决。`v2` 已完成 Quartz 写入，但新证据查询误用不存在的 `execution_attempt.created_at`，按真实 `queued_at` 修正后才有 `v3`；两份失败及各自清理记录原样保留。
- N23 的上述专项已有真实结果，仍须纳入 W6 同版本冻结后的必要验收；其余 W1 安全场景和全产品验收尚未完成，W1/整体目标不关闭。

### N21/N22 任务统计与发布登记的组件证据

`output/audit-task-release-ui/4a105d3fb83344c0bc6279a6bb746063/report.json`，SHA256 `f4256a46b2e353b5161744deba42ed8bbaaf4e644fe3e2dbd3da600bb13e2f36`：Node 24 单 worker，统计函数 14 项、任务组件 3 项、发布登记组件 5 项，共 22 项通过。原 8 项 RED 和首次 GREEN 的 AntD 表格测量副本定位歧义均保留；改为 columnheader 语义定位后通过，未降低产品断言。源码首尾一致、自有 TEMP 已清理。范围是函数/jsdom，不是实际浏览器、后端发布或全量列表验收。

随后扩大 lint 到本次三个生产文件：初次命令的 6 条 ignored 警告不计 lint 通过；真正纳入规则后发现 3 处已有 Promise 处理错误，补 effect 明确拒绝处理及同步点击回调。`lint-fix-v1/report.json`（同目录，SHA256 `0de3e0a3417cdd0b063c6b86b01e45651dbf43a9aea5748b73ab633215c85d8e`）绑定新源码，原 22 项再次通过，三个文件 lint 0 error/0 warning；旧报告和 lint 失败均未覆盖。全前端 typecheck 及最终脚本回归另存独立记录，不扩大此组件报告范围。

最终集成类型检查 `integration-typecheck-v2.json`（同目录，SHA256 `4d2d3a2f8608a7bae8f9d6c5e92e500c504a796c7c21c53335c5b21908683933`）：Node 24.19.0 执行全前端 `tsc --noEmit`，2026-10-26 11:32:05–11:32:19 UTC，退出码 0；全部前端源码和声明的包/类型/lint配置首尾一致，日志为空且 SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。`releaseEligible=false`，不计为前端构建或浏览器通过。

### 2026-10-26 脚本回归与夹具失败留存

完整日志沿用 `output/audit-browser-family/087562e57675460da19987057390f3e3/`。`scripts-full-v1.log` 的 384 项有 2 failure、3 error：两处旧迁移计数 122、两处测试复用已禁止覆盖的输出路径、协议运行输入测试依赖被干净打包删除的宿主报告。分别改为完整迁移路径/摘要集合、每例独立输出目录、测试自有协议输入夹具；真实 protocol producer 的 fresh XML/类/JAR 准入规则保留。

`scripts-full-v2.json`/`.log` 绑定 worktree `26e6f1cecf023e1196403c82eb5d3f460bde2483d38c111b1c0d61060c77ce33` 且首尾一致；408 项仍有 1 failure、1 error。前者是引擎 fixture 写死旧 92 个 case、实际已继承 111 个必要 case；后者是本次命令未设置 `JAVA_HOME`，真实新密钥准备测试明确拒绝。这是命令依赖准备错误，不能归为应用安全缺陷，也不能跳过该测试。两次完整失败保持失败。

锁夹具另有真实 RED：`sql-lock-safety-red.log` 26 项中 1 项证明 Popen 启动异常后新日志句柄未关闭；主线补关闭并原样重抛，同一测试源码的 `sql-lock-safety-green.log` 26 项通过。范围仅为执行器所有权和异常控制流；不重写已结束 SQL run 的绑定，也不代替全回归或发布证据。

修正引擎契约固定计数、明确 JDK 21 及收口本轮脚本后，`scripts-full-v3.json` / `.log` 完整重验 **423 项通过、0 failure/error/skipped，退出码 0**。Python 3.12.14，显式 `JAVA_HOME` 为 JDK 21；测试日志耗时 188.928 秒，包装记录开始/结束为 2026-10-26 11:32:08–11:35:27 UTC。源码首尾均为 HEAD `8610de063d51a76d97f65d4d003f8b2ec93d307c`、worktree `455aedd3c192900034de21b4946b036a3524f479af74a9753469f40c1198ba76`。报告 SHA256 `51539957a0f22c3a9b64badd7f3f83fd1ac6de9b29a77c7abc63f73b917ec320`，原始日志 SHA256 `b7542ef53df0113d72ea40a5b853b527e0e8a086f80452d79335bf3689a8fac6`，`releaseEligible=false`。这绑定交接文档更新前的源码范围，与前两次失败独立；属于本机脚本组件回归，不充当冷 CI 或 W6 同一冻结版本下八类 producer 的正式发布结果。

### N06 追加 PostgreSQL 状态链证据

`output/audit-workflow-engine-authority/run-20261026-terminal-n06-v1.json`，run `fa16d3ee004b4cd78ffdb75f8aa8029a`，SHA256 `3c0ac6e99f2ef941d13bc40f2ec8285cd58ac8600a54232c1ea49aeb43f229ad`：31 项真实 PostgreSQL IT、0 failure/error/skipped，源码首尾一致、五项清理 true。`flinkPipelineUsesItsOwnEngineResultToOpenTheSqlDownstream` 实际运行有界正常终态与 streaming 意外 FINISHED 不放行下游的数据库断言；不再只是编译。已知 Workflow V32→V33→V34，其他服务为当前版本，121→122→123 份混合夹具迁移，旧 history/checksum 与 SQL/quality 批准保留。不是任意旧客户版本升级；Auth 决策及引擎业务结果仍为受控 peer，不能代替公开入口整链或物理 Flink。

公开入口联调 `output/audit-workflow-engine-chain/run-20261026-flink-modes-v1.json`，run `507fd331328c40ce83e986bbc05fb476`，SHA256 `531e5a6298c5224e7ffeefea8c0ad1bda3106c143e6a5faf8ffd40fcabb74e68`：新鲜五服务构建，已执行的 92 项检查通过，但新增等待 Flink job ID 的夹具把尚未绑定的 SQL NULL 当 JSON 文本解析，提前 `JSONDecodeError`，整轮 failed。五项资源清理和两个 HTTP peer 清理/trace 项为 true。修复仅将该类待绑定查询明确返回 JSON null，不改基础 SQL 解析器或批准/终态规则；后续必须用新 run 重验，不能把本轮局部通过升级为完成。

后续新 run：`output/audit-workflow-engine-chain/run-20261026-flink-modes-v2.json`，run `d529b00439014d5eb4438a159b86e483`，SHA256 `f411c7bbc8a93bea4eb610cd57d15395441b3f64b4e4f7447fb89f1fce14f526`，113 项通过、源码首尾一致、五项资源及两项 peer 清理通过。实际 Auth/System/Metadata/Workflow/Gateway、PG/Redis；公开 bounded 正例完成，streaming 手动意外 FINISHED 保留 `FLINK_UNEXPECTED_FINISH` 结果；两节点 Flink→SQL pipeline 为 failed/下游 skipped，SQL instance/attempt 均空、计数为0、业务行0、`pipeline_success_evidence=false`。原响应丢失零重复提交、取消占位、授权撤销/定义变更/同名重建/服务故障零派发亦在本 run 重验。

独立 `independent-artifact-cleanup-check.json` 重开报告与 trace 摘要、五份当前 JAR 字节匹配，记录 PID、该标签容器和临时私钥目录均不存在。仅 SQL 使用真实业务 JDBC；Spark/Flink 是受控 HTTP peer，不是四类型混合物理流水线或正式 producer 验收。默认 fresh build 已接入，显式旧 JAR 复用仍严格要求双参数及原锚点，不能以坏复用自动回退。新增 nullable 轮询测试 24 项通过，缺行/空串/非法JSON仍拒绝，失败原 run 不改写。

### N03 后端验证与当前安装的接线约束（只读复核，未实施）

`release-evidence.validate_aggregate` 可复用，但不能把其 exit 0 直接变成 API 的 `verified`：现 context 的制品/配置值仅做格式与上下文一致性检查，缺实际安装对照；R3 managed、R4 event-time/control-plane、R5 maintenance 仍需补齐各自内容、清理和附件验证。System 的全局 JSON `NON_NULL` 也不符合前端必须显式包含 `currentBinding:null`、`evidence:null` 的严格契约。

当前 System 发行镜像只有 JRE，不应为这个 GET 临时引入完整 `.git`、Python、源码或 Docker socket。落地边界是部署主机复用原验证链，并从实际实例、镜像、有效配置、registry 与数据库 inventory 独立取得安装观测，发布短租约只读视图；System 只核对安装 ID/代际、期限、最新 attempt manifest 和原始文件摘要。变更安装前先失效旧视图，GET 不续租，不开启旁路管理员服务；目录只读挂载以支持原子替换。`generated-not-installed`、`verified-not-deployed` 和 last-good 签名清单都不能单独证明当前运行状态。预期 OCI 引用需解析为本地 image ID 后再与 container.Image 比较，不能混用两种摘要。

上述依赖 W4 的正式安装/发布锚点，当前没有部署该能力，也没有验证完整正例。后续须保留 401/403/503 差异，补旧成功后新失败、来源/配置/迁移错配、缺项、过期、附件替换、校验中变化及观测停止后的降级负例；不能以长期返回 unavailable 作为 N03 完成。

### N07/N08接线前的只读调查补充（未实施）

不能只把`lakehouse_table`加入Auth目录就关闭：维护jobId入口须从记录取得真实tableId再授权；列表须按实际lakehouse ID做visibility/batch检查；实体请求/响应需白名单DTO。`/complete`不能允许浏览器提交成功凭证。time_travel结果含真实业务行，view无query权限时不能从历史resultJson读出行。LakehouseTable没有既定普通table/domain映射，不能因数字ID相同继承权限。

维护/Schema旧client需替换为metadata-only submit/status/cancel、workflow-only来源lookup和独立Auth最小授权；从封存材料恢复原UID/AV及批准者，不按用户名拼USER。先持久化新的attempt/fence、目标/SQL/业务日期/retention cutoff和摘要，status/cancel只使用原receipt句柄。Schema当前changeId充attempt、动态截止时间和可变请求键须一并处理；存量未封存运行只做原记录对账/受控清理，不从当前定义重发。上述为只读调查后的实施约束，PG与物理Runner仍待真实验证。

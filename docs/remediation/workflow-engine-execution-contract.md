# Spark/Flink 可信执行与恢复契约

2026-10-26。本节承接V32 SQL/quality来源。V33实现Spark/Flink可信manual/cron/pipeline来源、实际批准材料、一次性派发、原origin恢复与精确结果绑定；V34补齐引擎节点放行下游的数据库成功证据。当前V34的1236项常规回归、31项真实PG/受控HTTP、六应用119项及真实Auth引擎链92项通过，各自保持独立配置与源码范围。引擎HTTP结果为受控响应；物理Spark/Flink、暂停/恢复/升级的新attempt及实际IO范围仍未完成，不能据此关闭S08/R03。

## 主体与批准

运行actor必须来自已验签的手动请求、明确绑定的cron generation或原pipeline run。保留UID、username、accessVersion、requestId与实际kind；恢复线程不能重新按用户名取得当前权限，也不能将pipeline伪装为manual。

普通actor可获固定workflow task的execute能力。Spark程序、Flink connector和计算环境属于执行定义，由具有平台管理能力及该任务update权限的实际用户明确批准。批准不赋予actor任意数据源SQL、代码上传、替换connector或选择其他计算环境的权限。派发前同时核对actor与批准者原UID/AV及权限；同名重建、禁用、撤权、降权或服务不可用均拒绝新派发。

权限读取结束、返回允许结果前，再核对同一原UID/name/AV仍然有效，防止读取期间同名账号重建或AV变化使按username的权限读取串到新主体。既有fixed-execution及published-key授权也采用这一收口；受控竞态反例与实际应用回归分别记录，不把MVC注入当作真实数据库并发已验证。

独立授权接口保持为：

| 字段/接口 | 冻结约束 |
| --- | --- |
| `POST /internal/v2/permissions/engine-execution-authorize` | operation `auth.engine-execution.authorize`，仅workflow workload，USER FORBIDDEN |
| 根对象 | 严格四字段 `schemaVersion,binding,actor,approval`；版本1；最大16KiB；拒绝重复/未知字段、尾随内容及浮点运行键 |
| binding | `engine,taskId,instanceId,attemptId,fence,executionRevision,manifestSha256,definitionSha256,engineDefinitionSha256`；engine仅spark/flink，其余ID为正整数，三个摘要为小写SHA256 |
| actor | `kind,uid,username,accessVersion,requestId`；kind仅manual/cron/pipeline |
| approval | `uid,username,accessVersion,revision,definitionSha256`；revision/hash必须匹配binding |
| response | 严格 `binding,allowed,reason,checks`，完整回显binding；检查actor task.execute、批准者task.update及platform.manage；nullable字段必须显式出现 |

该端点只检查原身份及窄权限集合。workflow仍必须独立证明封存来源、当前定义与资源范围，不能把任意调用该端点或返回allowed当作原来源已成立。原metadata-only的fixed-execution接口不扩大caller集合。

## 定义与物理资源

来源存储在现有execution revision/approval/manifest上增量实现；SQL与quality既有规范投影字节语义保持。V33在隔离PG实际验证V32→V33，32份旧history/checksum/success与SQL/quality原批准保持。历史引擎actor/批准不回填，旧不可信unknown不得自动重放；新来源必须明确重新批准实际材料。

Spark批准材料必须固定job版本、实际artifact key/type/digest/mainClass、参数schema与默认参数、输入表及输出表/URI/写入模式、环境身份与能力、允许的runner ID/origin集合、资源与超时。只固定jobDefinitionId或旧definition_digest不足以证明实际执行材料。排队后重新核对当前批准和资源状态，实际提交消费封存材料；runner选择及原origin一旦绑定不可替换。

存量`config://default`在批准时解析为当前配置中的实际HTTP origin并写入材料；排队选择也读取该封存origin，不能把别名传到派发和恢复。配置变更须重新批准；受控PG/HTTP负例已验证变更后新旧两个origin均零连接/零提交。此项兼容不是在恢复时重新采用当前默认地址的许可。

Flink必须固定脚本、gateway与JobManager origin、batch/streaming模式、事件时间/水位/迟到策略、允许的恢复材料及有效超时/容量。恢复、暂停和升级操作必须核对当前active attempt/fence及原封存定义。body提供的新SQL或当前Task的新endpoint不能混入旧attempt；升级需明确的新批准，保存旧运行终止与savepoint证据后创建相应物理执行记录。

声明的输入/输出和artifact hash本身不是IO沙箱。实际runner/Flink账号、挂载、文件、数据库、对象存储与网络范围还必须验证；程序不能借共享高权限凭据获得未经批准能力。此项仍是必要验收，不能用接口授权测试代替。

## 提交、回执与容量

每个物理attempt只能有一次提交所有权。持久化原request key、选定origin、封存参数与派发状态；崩溃或响应丢失后只查询原来源，不能换origin、换key或重发POST。确认本地未派发与结果未知必须分别记录。没有可证明的远端结果时保留unknown及物理容量，不因逻辑超时、旧fence、取消请求或恢复异常提前释放。

Spark提交传输现执行以下规则，实际wire证据另列在台账：

- 成功句柄必须绑定原request key及artifact digest，ID、状态和字段类型合法；响应最大64KiB，拒绝重复字段及尾随JSON。
- POST的5xx、断线、空/畸形/错绑定响应，以及408/409/425/429/重定向均无法证明未执行。只向原runner按原key GET核对；GET 404仍保持unknown，禁止第二次POST。
- 约定的明确提交前拒绝400/401/403/404/405/413/415/422可记为已知拒绝。它们不能授权重用同一个执行记录去提交不同定义。
- 不把runner错误body原样返给用户；使用固定安全错误，避免URL、SQL、连接参数和秘密进入响应。
- POST已发生但本地结果落库或active fence变化时，仍须保留资源，等待原物理运行对账。数据库状态冲突不是远端已停止的证明。

上述artifact比较覆盖submit即时核对与封存来源的后台by-request恢复。后台使用原材料中的digest及原origin/key，当前job或Runner地址发生变化也不能替换读回目标。status/cancel/logs响应绑定原句柄、key与artifact，取消终态须含可解析的确认时间；日志游标有界且前进。数据库receipt严格绑定以下11项：`schemaVersion,engine,instanceId,attemptId,fence,manifestSha256,engineDefinitionSha256,origin,requestKey,remoteHandle,artifactSha256`。终态证据、状态、行数和错误不能在完成后改写。受控PG/HTTP验证不能代替物理Runner的实际进程与写入核查。

Flink原生Gateway没有可默认依赖的幂等submit契约。取得session/operation前的断线也可能已发生业务写入；V33在每个session/setup/INSERT前追加原语句摘要，返回后追加已知句柄，步骤须与封存脚本匹配且不可替换。异常后不自动关闭未知作业、不重放POST；session/operation无法取得时保持unknown。Gateway完成仅证明提交，业务终态必须读原JobManager上绑定的Job ID。

V34仅替换`pipeline_success_evidence`，不修改V1—V33原字节。此前函数统一要求`metadata_receipt_id`，合法的Spark/Flink成功节点因此不能放行下游；实际混合链暴露后，新增两项真实PG回归先失败。当前SQL/quality仍要求Metadata回执；Spark/Flink必须具备原派发标记、封存材料摘要、原attempt/fence/origin/key/handle的引擎回执、匹配摘要的成功结果及绑定原handle的远端成功状态，且仍满足原pipeline actor、批准、节点与当前instance绑定。仅将状态改为success不能放行。修复后的31项PG回归全部通过，包含原29项及Spark/Flink分别放行SQL下游的正例。

新封存attempt的取消通过原句柄及JobManager确认，容量在确认前保留。旧savepoint/pause/upgrade/resume接口目前拒绝修改新封存attempt；这些功能尚待以原作业终止证据、明确的新批准及新物理attempt实现，并同步前端，不能把拒绝旧接口计为功能完成。

## 必要验收与当前边界

V33仅在本轮自有隔离数据库执行。现有数据库的升级尚未获本轮操作授权；实施前须读取实际Flyway history并保留校验和、批准/cron绑定、活动attempt和外部作业清单，验证可恢复备份，再安排受控停止新派发的窗口。不能只依据仓库版本推定存量库为V32，也不能执行repair使校验通过。

V34同样只在自有隔离数据库执行。升级演练实际按V32→V33→V34逐步迁移，V33既有33份history/checksum/success及原SQL/quality批准保持；当前源为122份迁移、workflow34份。该迁移不清空任务或执行证据。回退前应停止新的引擎pipeline派发并保留已启动运行的对账能力；恢复旧函数会重新拒绝引擎上游成功，不能将其当作正常业务回滚完成。现有库必须另行准备明确版本的备份、受控变更与验证，不删除Flyway记录或改写旧迁移。

迁移提交前失败按事务回滚；提交后不得直接删列、改history或让旧应用恢复用户名派发。若已有物理作业，先由当前来源/receipt核对并确认远端终态，保留unknown和容量；平台库快照恢复本身不能撤销外部业务写入。应用回退、外部副作用对账及数据库恢复必须作为同一具体变更方案验证，不能将本轮隔离V32→V33成功当作客户环境回滚已验证。

原Auth端点/客户端分项的账号/权限持久层为夹具，原PG29项保留V33/121源范围。当前PG31项使用workflow实际runtime及V34/122源，Auth决策和引擎业务结果仍为受控响应；六应用119项覆盖真实Auth与SQL/quality。新增五应用92项实际运行原批JAR、Auth持久层和服务签名链，验证原UID/AV、独立批准者、手动/Quartz/pipeline、排队撤权与变更零派发、原origin恢复、取消容量及三节点混合链，保留53条脱敏HTTP trace。引擎业务结果依然为受控响应。各分项边界与当前证据见整改台账，不重新绑定旧报告。

仍须完成实际业务Spark/Flink及副作用核查、物理IO隔离、进程崩溃矩阵、Flink暂停恢复升级，以及包含SQL/quality/Spark/Flink全部四类节点的同一pipeline。当前三节点链不外推为这些场景通过。所有报告绑定各自的源、运行、配置、迁移、制品与附件，不重新绑定旧成功或抹去本轮RED。

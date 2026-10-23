# S03：业务数据源 READ / WRITE 独立凭据

本包分离业务数据库读取与写入账户。九服务访问平台自身 schema 的 runtime/migration 角色是另一条边界，没有替现有业务数据库创建账号或授权，也不因字段分开就宣称生产数据库权限已收窄。

## 持久化与接口

metadata 新增 `V28__separate_business_write_credentials.sql`，给 `metadata.data_source` 增加 nullable `write_username` / `write_password`，并增量替换现有 invoker revision 函数，使 writer 改名/改密/禁用也推进 connectionRevision。V17 等旧 SQL 不变，既有 username/password 保持 READ，存量 writer 保持 NULL；没有自动复制旧密码或猜测 writer。当前全仓源迁移由93变94，需要发布 gate 重新枚举/哈希；旧证据不能事后贴成94源通过。

`username` 继续用于编辑读取账户；`password` 与新增 `writeUsername` / `writePassword` 都禁止 JSON 回显。writer 响应只有 `writeConfigured`。两密码经现有 AES-256-GCM secret service 加密保存；write_username 是账户名称，不是密码密文。writeConfigured 只表示存在一套完整且与 reader 名称不同的写配置，不证明密码正确、账户权限最小或写入健康。

新增 request-only `disableWriteCredentials:true` 表示明确禁用 writer；不能同时发送 writer 账户/密码。关闭 UI 开关并保存会发送此标志。service 使用真实 NULL 更新两列，不依赖 MyBatis 默认忽略 NULL；controller 成功后记录 datasourceId、actor 的禁用日志，不记录用户名密码。普通保存不隐式清除已有 writer。

更新时空 password 仅在同一个读取账户下保留；读取账户改名必须给新密码。writer 输入留空可以保留已有账户/密码；填写不同 writeUsername 必须给新明文 writePassword，不能把旧密文移到新账号。新增 writer 必须两字段完整；同用户名按 trim + 忽略大小写保守拒绝。新账户不接受沿用旧密文的更新。创建只读数据源仍可不填 writer。

凭据更新使用数据库条件更新，WHERE 绑定更新前 connectionRevision 及 read/write 账户与密码状态。内部完整 entity 若携带旧 revision，必须在合并前拒绝，不能用 service 新读到的版本为旧密文背书；无 revision 且与当前值不同的密文也拒绝。API 的 connectionRevision 被 JsonIgnore，用户无法伪造内部 fence；新明文配置仍可作为明确授权变更。改密/禁用导致版本推进，并保守将 READ 健康设 unknown。CAS/版本冲突返回 HTTP409，需要刷新后重新提交；这不是对所有普通展示字段的全面乐观锁。

## JDBC 用途边界

| 路径 | 凭据 |
|---|---|
| query、SELECT1 probe、schema/table/column discovery、preview | READ |
| transfer source | READ |
| execute | WRITE |
| transfer target，含 replace DELETE+INSERT | WRITE |

WRITE 缺失、不完整或与 READ 同账户时，在调用任何 DriverManager/JDBC 前拒绝；transfer 在打开 source 前先检查 target writer。绝不 fallback READ。两种用途共用该数据源已有 host/port/database/jdbcUrl/connectionParams 和相同出站校验，不存在 writer 自带 URL。

复用现有连接参数 allowlist：URL userinfo、user/username/password、role/options、INITSQL 等不能覆盖实际选择的账户。测试同时检查自定义 URL 与额外参数；密码解密在建立连接前完成。SQL只读/写入白名单、超时、目标标识校验和原有事务规则保留。

这不保证 READ 账号在数据库里真的只读。operator 仍须核对其有效角色继承、跨 schema/table 权限、默认权限、函数执行及连接级身份设置；writer 应只具备当前任务所需目标表 INSERT/DELETE/必要 SELECT，以及确需的 sequence 权限。不要把两个不同的超级用户当成最小权限。Hive noSasl 等不认证环境也不能靠字段存在宣称认证已生效。

## UI 与兼容迁移

原账户标为“查询 / 采集读取账户”，writer 可选；未配置就不能数仓加工或成为接入目标。写账户不回显，编辑时留空保留；若主动填写账户名称，表单要求同时填写新密码。关闭并保存是显式禁用。

列表显示 writer 配置状态，健康列及按钮明确为“读取连接健康/测试读取”。SELECT1 成功不证明 WRITE。未保存配置的 READ probe payload 不携带 writer 秘密。原本依赖 reader 同时写入的加工/接入任务会有意失败，需 operator 单独配置 writer；升级不能维持这种隐式写权限兼容。

## 实证与边界

先 RED：`DataSourceWriteCredentialsTest.writeWithoutIndependentCredentialsFailsBeforeAnyDriverCall` 在旧实现中失败，原因是缺 writer 的 execute 实际成功使用 READ JDBC（期望异常但未抛出）。修复后明确检查 DriverManager 零调用。

隔离真 PG 入口：

```powershell
py -3 scripts/tests/test_datasource_credentials_audit.py
py -3 scripts/audit-datasource-credentials.py
```

脚本仅 inspect 已缓存 postgres:16-alpine，缺镜像直接失败，不拉取。使用随机127.0.0.1端口、512MiB/1CPU/tmpfs/无既有卷的独立容器；只在该数据库创建 fixture reader/writer。JDK21 java.home 实际核验，直接启动 JDK Maven JVM；随机 run 标记同时绑定 Maven/Surefire Java，finally 核对退出并仅清理匹配标记的孤儿；只删匹配本轮标签的容器。

真 IT 覆盖 V27→V28 保留 reader 且 writer NULL、Flyway validate/0pending，同一个 DataSource query/probe/discovery/preview 使用 reader，execute/transfer target 使用 writer。业务表记录实际 current_user：transfer read_actor=audit_reader、write_actor=audit_writer。验证 fixture reader 真实无 INSERT 权限、writer 无平台 metadata 读取权限；缺失/不完整/同账户写入零副作用；真实 MyBatis 加密保存、保留、更换账户拒绝、禁用两列实际 NULL。先预读完整带revision entity，再同用户名改密或禁用，旧entity仅改description必须拒绝；同时保留service读取后到UPDATE的并发反例，覆盖两个不同窗口。

原 run `d1ffdab767f04526a6c3c39e108a70ce` 仅覆盖读取后CAS窗口，不能作为完整旧entity安全证明。新增首次完整旧entity反例 run `1e2f6f733eaf43c78536caa397a2dc6b` 实际失败：同writer改密后旧entity被接受；该 failed 记录保留。revision修复后 run `d617ca68f09348079435fd020cd18eb3` 通过两个窗口及旧无版本密文反例。最终补齐全测试源摘要后的 run `6278832285a94f95b3d475f3424d84d1` 再次通过，一个真实PG集成场景，源摘要一致，owned Java 与容器清理均 true；额外只读检查本包标签容器和Java标记残留均为零。V28最终SHA256为 `636b6b2e13fe0343b8fead33b28968354d5c6184ef1e74127c9bb44f978412b4`。

`output/audit-datasource-credentials/<runId>/report.json` 和脱敏 postgres-it.log 保留源摘要、镜像ID、JDK、端口、PID、测试与清理。第二轮 `29578de38aad411d9c1a197ac820b69f` 的清理计数失败原样保留：短/长容器ID重复处理造成 false，实际容器已删除；修正为 --no-trunc 后重新跑过，没有覆盖失败报告。

最终 metadata 常规207项通过，脚本3项通过；前端定点11项（含真实表单交互）、tsc与build通过。jsdom getComputedStyle伪元素提示及现有bundle体积warning不记作功能失败，也不把mock页面测试当浏览器验收。本包没有真实应用HTTP/浏览器链路、生产业务库权限或所有JDBC驱动写账户行为的验收结论；真实PG结果只对该隔离fixture负责。正式发布仍需新制品、94源、数据库迁移/ACL及生产配置收据重新绑定。

## AES主密钥重加密准备与回滚

2026-10-18新增离线工具 `scripts/security/datasource_key_rotation.py`。它没有数据库连接或apply命令，只读取私密文件并在全新ignored目录生成 `apply.sql`、`rollback.sql`、`plan.json`。实际库与密钥切换仍需具体环境授权；不要直接替换主密钥后重启。

工具沿现有Java `DataSourceSecretService` 的 `v1:base64(12-byte nonce || ciphertext || 128-bit tag)`，使用32字节AES密钥、随机新nonce、无AAD；不是新密文版本。Python依赖固定于 `scripts/requirements-audit.txt`，AESGCM接口见 [Cryptography官方文档](https://cryptography.io/en/48.0.0/hazmat/primitives/aead/#cryptography.hazmat.primitives.ciphers.aead.AESGCM)。密钥只从文件读取，不放命令行；null/空字符串保留，旧明文、坏密文、错误/相同密钥均拒绝整个计划。plan只含摘要和计数，SQL含加密凭据，必须与备份一样私密保存。

实施顺序：

1. 确认全部持有该主密钥的metadata副本及凭据写入者；停止这些写入和相关worker，核对已有unknown任务，保存实际配置与旧密钥。全程不混用两把主密钥的副本。
2. 以现有database roles工具取得只读inventory，完成custom格式备份并在一次性库实际恢复。工具复用 `verify_backup`：核对文件SHA/PGDMP头、cluster systemIdentifier、databaseOid、inventory摘要及24h内的备份/恢复时间。恢复记录仍是operator提供的证据，单靠一个JSON字段不能替代实际恢复。
3. 在同一停写窗口将 `metadata.data_source` 全表的 `id,connection_revision,password,write_password` 导出至私密文件。snapshot精确三字段 `schemaVersion=1,inventorySha256,rows`；每行精确 `id,connectionRevision,password,writePassword`。inventory摘要用 `scripts/database/contract.py` 的 `digest`，沿原inventory保存OID文本，不改变备份绑定。旧/新key文件均是单个base64编码32字节值。

```powershell
python scripts/security/datasource_key_rotation.py `
  --inventory <private>/inventory.json --snapshot <private>/snapshot.json `
  --backup-receipt <private>/verified-backup.json `
  --old-key-file <private>/old.key --new-key-file <private>/new.key
```

4. 审阅新目录中的plan计数、源/目标key指纹、备份摘要及两份SQL摘要。SQL以cluster/OID/database名限定目标，整表EXCLUSIVE锁后先核对全表数量、全部ID/版本/密文，再进行一笔事务更新；锁5秒/语句60秒超时均使操作失败。执行需要已有operator权限读取 `pg_control_system()`，工具不授予权限。由用户确认后，operator才能执行 `psql -X -v ON_ERROR_STOP=1 -f apply.sql`。
5. DB事务成功后，在仍停写的状态将全部metadata副本改为新key并启动；验证真实READ及获授权的WRITE、隔离权限和审计。apply推进修改行的connection revision，旧封存执行不能因此恢复。通过验收前保留旧key、备份和私密计划，不自动删除。
6. 需要回滚时先再次停止写入。确认数据库仍精确匹配本计划apply后的密文/版本/全表数量，再由用户确认执行rollback并恢复旧key；回滚恢复原密文，同时继续推进revision，绝不把旧fence复活。重复apply/rollback、后来新增/删除/修改的数据源均使旧计划拒绝。若不再匹配，应核对当前状态并生成新的恢复方案，不能强制覆盖或自动整库恢复。

隔离实证：`scripts/audit-datasource-key-rotation.py` run `7fa05e72ea3a41a1a7cb1878f2a82564`，17/17项通过；报告 `output/audit-datasource-key-rotation/run-20261018-v1/report.json`，SHA256 `972d3f8d26851366921e83dbe58f4fee919a6c728a7788a0bb8d6a1faaa6ddc9`。实际Java类双向兼容、中文/READ/WRITE/null/空值、真实备份恢复、后行报错全事务回滚、旧key拒新密文、重复/错OID/新增行/迟到fence拒绝、回滚恢复原密文并推进版本均已验证。16项工具测试通过，独立核对临时容器/网络/卷、自有Java/Python和私密输入/计划目录均无残留。

该PG夹具只执行六份明确的源SQL文件以验证真实凭据列和revision触发器，不是全31份metadata Flyway升级或全平台验收。生成的apply/rollback只在一次性库执行；现有数据库、已安装密钥和部署均未改变。

## 2026-10-19：公开响应与历史连接配置边界

本增量只处理数据源 API 的秘密边界，不修改内部 `DataSource` getter、凭据列、密钥、历史数据库或 JDBC 执行逻辑。`DataSourceController` 的列表、详情、创建、更新统一返回显式 `DataSourceResponse`：保留前端实际使用的 id/name/type/host/port/databaseName/username/managed/description/status/时间、`writeConfigured` 与 `connectionHealth`；不包含 password、writeUsername、writePassword、disableWriteCredentials、connectionRevision。读取账户名称仍是可编辑公开配置，写账户只显示是否配置。

`jdbcUrl`/`connectionParams` 仅在通过 `DataSourceConnectionInputValidator` 的连接选项校验时回显。该 wrapper 复用运行期 `JdbcConnectionParameters` 的属性白名单与结构化 URL 校验，保留现有引擎前缀和合法 Hive `auth=noSasl` 等参数，不另建一套密码参数黑名单。历史含 URL userinfo、密码/账号属性、未知属性、编码逃逸或类型不匹配的记录隐藏这两个字段，并返回 `connectionConfigurationRedacted=true`；合法配置仍可读取和编辑。历史 host/databaseName 也独立检查：不合支持结构的 host、网络数据库名以及带危险属性的嵌入式数据库名只隐藏对应字段并标记，不连带隐藏合法 username/host/databaseName 或合法 URL。嵌入式数据库名须构造对应完整 JDBC URL，再复用同一属性校验，防止绕开显式 jdbcUrl 检查；允许合法 mem/file/只读等既有参数形式，不改变部署默认禁用嵌入式引擎。映射不修改原 entity，不清除持久化值。此标记不表示已修复配置，也不是密码正确或最小权限的证明。

保存边界同时在 controller 和 service 执行：创建、type/host/port/databaseName/jdbcUrl/connectionParams 任一字段实际变化、非 active 重新启用为 active 时，校验按既有 NULL 忽略更新语义合并的完整配置。URL 最长8192字符、额外参数最长1000字符，拒绝 CR/LF/NUL 等不支持形式，错误固定为安全消息，不包含用户提交的未知参数名或 URL。新连接配置不得将账号、密码等移入 URL 或额外参数；仍使用独立输入字段并沿现有加密规则保存。

未改变连接原值的展示名/描述更新、隔离以及凭据重加密允许保留历史脏配置。这是为使旧记录能够先隔离、完成已有重加密而保留的窄兼容边界，不以 managed、revision 或客户端标志豁免新配置。历史脏值继续隐藏，运行期 JDBC 校验仍拒绝它；重新启用或修改连接配置必须全部改为合法值。修复时应显式提供安全替换，或以空字符串清除 jdbcUrl/connectionParams、回到结构化连接字段；NULL/省略仍表示沿用，不是清除。没有自动扫描修改、补绑或数据库迁移。

当前前端未知 `connectionConfigurationRedacted` 时不会显示专项修复提示；本包不改前端。管理员应按字段约定显式修正历史配置，不能将读取时看到的空值当作旧配置已被删除。后续 UI 应显示该标记及清除/替换操作，但不能取回隐藏的原值。

历史健康观察中的自由格式 failureMessage 不直接回显；响应按受控 failureCode 映射固定消息，未知 code 归为 CONNECTION_FAILED。探测响应也只保留公开健康/驱动信息字段。query/schema/table/column/preview 的本地异常不返回 JDBC 原文；查询失败的 history.errorMessage 使用固定消息，日志只记数据源 ID 和异常类别，不记异常堆栈、URL 或凭据。这里没有删除用户提交的 query_history.sql_text，也没有把 SQL 字面量当作已脱敏，SQL/PII 生命周期是独立后续工作。

验证由主代理串行运行 Maven，本包代理不运行 Maven 或真实 API/数据库/驱动。首次行为 RED 已由主代理实际执行：DataSourceResponseBoundaryTest 13项中10失败，DataSourceConnectionPersistenceBoundaryTest 13项中13失败，均0 errors；原 XML 与日志在 `output/audit-datasource-admission/run-20261019-v1/red-attempt1-xml`、`red.log` 保留。其后 partial-update 用例根据上述已冻结兼容契约细化为无关更新可保留、连接变化/启用必须完整校验；不是删除失败记录。新增测试使用真实 Jackson 与 MockMvc、mock 持久化/JDBC，并检查旧错误消息、历史配置、查询日志、权限403/503不被错误映射、现有引擎参数兼容。

主代理随后独立复核发现嵌入式 databaseName 和历史结构化字段漏校验。本包先新增17项：9个 embedded INIT/USER/PASSWORD 保存反例、5个历史 host/databaseName 响应反例、2个网络结构化保存反例及1个合法嵌入式模式回归。主代理实际运行109项：上述14个新缺口真实失败，其余95项通过，0 errors/skips；日志/XML保留为 `state-green-extended-dto-red*`。本轮修复严格补上述边界，不将“可保存或回显不安全配置”外推为运行期 RCE，运行期仍有独立 JDBC 校验。

最终本包新增56项全部通过：DataSourceResponseBoundaryTest 21项、DataSourceConnectionPersistenceBoundaryTest 35项，0 failures/errors/skips。与主代理状态/健康53项合计109项通过；新鲜XML保存在 `output/audit-datasource-admission/run-20261019-v1/green-attempt2-p03-red-xml`。同一轮 metadata 全量实际410项、5 failures、0 errors/skips：4项为另一包P03新反例，1项为旧quality wire夹具缺active状态；该夹具后续已修，但此处不把未重跑的结果改写为通过。源码before/after一致，主代理额外封存287个被测试class及状态/DTO源码至 `archive-state-dto`，delivery.json SHA256 `696e07bcf9f82a1c7d38d76abf4e7ddc82a3701f48bab4e7dcf711bab4855af0`。这些是class/MVC证据，不是已构建新Boot JAR或发布镜像。

主代理独立状态/JDBC真PG证据：`postgres-fd7c9c0ab9dd48998195f6198cf9d3ab/report.json`，SHA256 `7a5079dbeadb03dc58f021b44b6f2614d2f4808d86790f8c5180e19a1faf9713`，1 IT通过，覆盖31份metadata迁移、实际角色和SQL、Driver连接计数active正例2/nonactive反例0；源摘要末匹配、4项清理true。该PG夹具不是本包DTO的真实应用HTTP链，也不覆盖九引擎；不能用它替代本包Jackson/MVC边界证据。

前端已由主代理独立接手：适配 nullable host/数据库名和隐藏配置提示，提供显式清除操作，避免对隐藏host执行includes导致页面崩溃。截至本包封存时Node验证尚未反馈，故不宣称前端/浏览器整链已通过。上述不泄漏契约也不延伸为其他metadata控制器、JDBC驱动自身日志、query_history SQL或任意自由文本字段均已脱敏。

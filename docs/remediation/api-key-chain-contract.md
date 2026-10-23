# S06：纯 API Key 四服务链路验收

本文保留旧协议run的历史证据，不能证明当前v2源码。当前实现与升级约束见 [api-key-v2-contract.md](api-key-v2-contract.md)，新整链工具为 `scripts/audit-published-key-v2.py`；以下原执行命令和结果不改写成新协议通过。

本验收启动当前源码构建的真实 gateway、auth、serving、metadata Java 应用，验证 gateway → serving → metadata → 独立业务 PostgreSQL reader，以及 auth 实时权限判定。它不使用应用 mock，也不访问当前运行的任何服务或数据库；不代表浏览器、完整 Compose、全部平台或正式发布已通过。

## 执行与隔离

```powershell
py -3 scripts/tests/test_api_key_chain_audit.py
py -3 scripts/audit-api-key-chain.py
```

脚本先 inspect 已缓存 `postgres:16-alpine` 和 `docker.1panel.live/library/redis:7.4-alpine`，缺少镜像就失败，不拉取。两个容器都使用本轮随机标签、随机 127.0.0.1 端口和 tmpfs，无现有卷。数据库采用独立名称 `aetheris_api_key_audit`，通过已冻结的 database runner 安装全部当前源迁移和九服务角色。

业务 fixture 只有 `business.orders` 三行及只读 `audit_business_reader`：它仅有 business schema USAGE 和该表 SELECT，没有平台 schema、写表或管理权限；脚本查询实际 PostgreSQL 权限核对，不复用主账号做业务查询。fixture 初始化 SQL 只作用于本轮数据库。

四模块执行离线 `clean package`，避免 target 残留迁移/类进入制品；范围是 apps/gateway、services/auth、services/metadata、services/serving。`-DskipTests` 只用于该制品构建，不能把它记作模块测试通过。制品复制到本轮目录，记录 SHA256/大小，进程结束后删除临时 JAR。离线缓存缺失直接失败，不升级依赖。

Java 优先使用 JAVA_HOME，并核实真实 java.home/specification.version=21，直接执行该 JDK 的 bin/java.exe；Windows 不从 PATH 调用 Oracle javapath launcher。每个应用的 Spring 启动 PID 必须等于 Popen.pid。窗口隐藏，heap 为 256MB（metadata 384MB），每进程 ActiveProcessorCount=2、连接池最多4连接，全部仅绑定回环地址。

随机 JWT/internal token/DS master key/数据库密码只通过短生命周期进程环境或本轮内存传入，Flyway 在 runtime 禁用，各应用分别使用自身 runtime 账号。gateway 不获得数据库账号。未启动的 system、workflow 等依赖指向本轮保留且不监听的端口，不能误触默认的现有端口。system 权限事件旁路不在这条四服务验收范围；metadata QueryAudit 必须实际写入成功。

## HTTP fixture 与权限

通过真实 gateway HTTP 完成：bootstrap admin 登录 → 创建第二个 admin owner → 以该 owner 登录签发 key → 将 owner 降为 viewer → 注册独立 reader 数据源 → sync business 表 → 创建并发布绑定该表的 API。

降级后仅显式授予该 owner 两项具体资源授权：一个 API 的 `data_service.execute`、一个 table 的 `table.query`。不授 null resourceId、group/role grant、grant-all 或 admin 权限。当前 published-table 内部路径实际检查 table.query，不要求 datasource.query；脚本不为测试便利额外授予 datasource.query，也不因此放开任意 SQL 入口。

纯 key 请求使用另一独立 opener，没有 cookie jar、JWT 或 Authorization。成功必须返回实际三行、准确列名及数值，HTTP 200 加 error 字段或错误数据仍失败。另发送伪造的 admin 身份头，确认 gateway 剥离后 serving 仍按 key owner 重签，metadata QueryAudit 的 username 必须全部归该 viewer owner，不能归 fixture admin 或 unknown。

覆盖以下负例：坏 key、过期、撤销、配额第二次、未发布 API、无 execute 的另一 API、撤销 table.query、撤销 data_service.execute、owner 禁用；相邻管理路径、v1/query、GET/PUT/DELETE、尾斜线及额外路径也不能用 key 放行。授权负例严格要求预期 401/403/404/429，500 不能记作拒绝通过。撤销后立即检查，再只恢复同一条窄授权核对恢复。

过期时间仅在隔离 SQL fixture 中向过去推进；key 签发、撤销、用户状态、资源授权、发布与核心查询均走真实 HTTP。没有外部供应商 API、模型调用或付费服务。

## 报告与清理

`output/audit-api-key-chain/report.json` 是最新运行状态，开始新 run 就改为 running；每轮完整报告保存在 `<runId>/report.json`，不会把旧失败改成通过。源码及所有迁移 SHA256、制品摘要、镜像ID、随机端口、各进程 PID、HTTP 状态/数据断言、QueryAudit 计数和清理明细均记录。进程日志保存在同轮目录，结束后过滤已知秘密、API Key 与 JWT，不保存 HTTP 登录/cookie/key 响应体。

finally 只终止本轮 Popen 进程，检查其退出、端口关闭及临时 JAR 删除；独立退休迁移凭据；仅删除匹配本轮唯一标签的两个容器，并复查标签无残留。任何场景失败、源码执行期间变化或清理不全，报告都不能 passed。按需保留的失败 aftercare 是单独附件，不改写原始失败记录。

首次实跑暴露了 ServingSecurity 多构造器缺少生产注入标记导致真实 Spring 应用无法启动；最小修复使用 @Autowired，并增加真实 ApplicationContextRunner 构造回归，未用 MockBean 替换被测安全组件。首次 PATH javapath launcher 的孤儿 JVM 也单独核对精确 run 路径并清理；后续直接 JDK 路径和 PID 一致性断言防止重现。实际场景是否通过以对应 run 的最终报告为准，不能从本契约文字推断。

第二轮真实链路发现显式窄授权在 auth 中被拒绝。真实 PostgreSQL mapper 回归确认：自定义 selectEnabledFor 缺少实体 resultMap，JSONB actions/dataScope 丢失；历史 DataScope JSON 含派生 empty 字段，严格反序列化无法读取。修复只指定已有 typed resultMap，并以 @JsonIgnore 排除 isEmpty 派生属性；兼容 empty:true/false，其他未知属性仍严格拒绝，没有扩大权限判定语义。脚本在四应用构建前执行三项真实 PG mapper IT，检查 actions/dataScope 精确读回、新 JSON 不写 empty、历史 JSON 兼容以及 enabled 过滤。

合法查询恢复后，真实链路又确认撤销 table.query 时 metadata 的 403 被 serving 转成 500。MetadataQueryClient 三类 governed query 现在将下游 401/403 转为已有安全异常，使用固定文案，不传播下游响应体；5xx 保留依赖错误。单独 wire 回归覆盖三类查询的 401/403 与 503，完整链路再次检查撤权状态和恢复后的真实 rows。此 wire 回归属于客户端边界测试，不冒充真实四服务链路证据。

此报告不应事后贴签为冻结的全平台 release 证据。正式发布还需同 commit/工作树与制品绑定、完整发布验收、生产权限 override 接入以及其他服务验证。

## 本次执行证据（2026-10-18）

最终运行 `f6595f6b775f4d2cade03699c0d6851c` 于 UTC 10:03:43 完成：25 项真实 HTTP/DB 检查全部通过，93 份迁移及 398 个源文件摘要记录齐全，结束时源摘要一致。五次成功查询均返回 fixture 的三行，metadata QueryAudit 对应五条 success，全归 key owner，其他主体计数为零。撤销两类授权均立即 403，恢复各自窄授权后均为真实 200 rows。完整报告为 `output/audit-api-key-chain/f6595f6b775f4d2cade03699c0d6851c/report.json`。

同轮真实 PostgreSQL mapper IT 3 项通过；独立 serving 定点回归 12 项通过，auth 全模块常规测试 111 项通过（不将独立 PG IT 重复计入）；脚本契约测试 8 项通过。最终四个 Spring PID 与直接 JDK 子进程一致、进程退出且监听关闭，两个临时容器已删除、迁移凭据退休；执行后的只读独立复核确认本 audit 目录对应 JVM 为零、audit 标签容器为零、最终 run 临时 JAR 为零。

历史失败 run `66d4e416d9274b8d958dbcaa1feaa957`（启动）、`77436643c3ca40dd8625afb71db29ab6`（授权 JSONB）、`2212df92bf6f40e9b7230c620ccce8d2`（撤权 500）保留原始 failed。首次 orphan JVM 的清理在首轮目录单独 `cleanup-aftercare.json` 中记录，不能倒改首轮清理失败。这些结果只证明当前四应用表型 published API key 路径；没有验证 metric 型 API、浏览器、正式 Compose 默认角色接入或生产部署。

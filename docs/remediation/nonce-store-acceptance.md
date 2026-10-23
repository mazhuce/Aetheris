# PostgreSQL nonce store 独立验收

2026-10-18，使用独立一次性 PostgreSQL 验收 `PostgresNonceStore` 和实际 `WorkloadProofFilter`。**这是 Spring mock servlet + 真实 PG/JDBC/JDK 签名验签；没有启动 Tomcat、现有应用或真实服务链。**

## 当前冻结源码的实际结果

通过 run：`2525372c5eca4edcadc6d5c39f7dace8`，2026-10-18 **10:39:39–10:39:50 UTC**。Maven exit 0：

- 修复后的普通单元测试：**55 例，0 failure/error/skipped**（DirectUserProofFilterTest 5、PostgresNonceStoreTest 4、WorkloadProofFilterTest 46）。
- 显式 `PostgresNonceStoreIT`：**1 个实际方法，0 failure/error/skipped**，1.735 秒；不是将多个内部断言计成多个 JUnit test。
- 新验收脚本的安全测试：**6 例全部通过**。

真实 IT 断言覆盖：

1. 两个 store、16 worker、64 次同 caller/nonce 的并发 claim 恰好一次成功。
2. 新建 store 对同一数据库记录再次 claim 被拒绝；同 nonce 换 caller 可以独立首次 claim。
3. 两个实际 filter、24 次同 nonce 并发请求，业务 chain 只执行一次：1 个 200、23 个 409，逐个验证真实响应签名及原 nonce/status/body 绑定。
4. 只读 DB role 的 claim 抛 SQLSTATE 42501，不退回内存成功。
5. 五条过期超过一天的记录按 limit=2 清理，结果为 2、2、1、0；一小时前到期记录仍保留。
6. 再新增六条老记录，两 store 并发各 purge(limit=2)，每次删 0..2、合计 2..4；剩余数与实际删除数一致，新记录和保留期内记录不删除。

### 独立数据库实读

IT 后验收工具再次通过真实 `docker exec psql` 查询：

- database：`aetheris_nonce_audit`，OID `16384`，system identifier `7697955702097477667`。
- 唯一业务 schema：metadata；最终 nonce 表 **8 行**，其中 4 条待下轮维护的老记录、1 条近期到期记录、3 条新记录。本次并发清理合计删除 2，符合允许范围。
- runtime INSERT=true、**UPDATE=false**；reader SELECT=true、INSERT=false、DELETE=false。
- 三组 expires_at 相邻差值为 **82801 / 3630 秒**，计数为 **4 / 1 / 3**；工具额外拒绝错误时间单位、错误保留分组或计数不一致，不只相信 Maven exit 0。

源码和现有编译 class/resource 的前后 SHA-256 清单一致。Maven 明确只执行指定 IT，检查 fresh XML 的唯一 suite/method、tests=1、failures/errors/skipped=0；空跑、旧 XML、跳过或错方法不计通过。

## 真实失败及修复来源

先前 run `42d47b77e1c94356ae25770780e84702` 实际失败，保留原始 failed 报告与脱敏 Maven 日志，没有回填成通过。

该次 1 test、0 failure、**1 error**、0 skipped：首次 purge 在 `PostgresNonceStore.purgeExpired` 抛 42501。原查询 `FOR UPDATE SKIP LOCKED` 需要 UPDATE，而 IT/最小权限契约只授予 SELECT/INSERT/DELETE。失败发生在前面的 claim/filter 断言之后，但整次 IT 仍算失败。

根修复为同一条有 LIMIT 的 CTE→DELETE，去掉选行 `FOR UPDATE SKIP LOCKED`，保留 2 秒 query timeout，不增加 UPDATE 授权，并补并发维护断言。本验收代理未修改库源码或通过增加权限隐藏问题。并发维护允许选到重叠旧行，因此后一批可能删 0，交由下一轮继续；当前实测通过且没有 UPDATE 权限。

旧失败证据：

- `output/audit-nonce-store/42d47b77e1c94356ae25770780e84702/report.json`
- 同目录 `maven.log`、`failure-evidence.json`；后者保存原 Surefire counters/error type/XML hash，原 failed 报告保持不变。
- 失败 XML SHA-256：`54DBD250AB3C19400A975B5F78D64F0386F1E5D0FA1C1FC7FB41552C32AD632F`。
- 失败 run 的容器、网络和无卷检查也全部通过。

## 环境、证据与清理

- 宿主绝对 JDK21：`C:\Program Files\Java\jdk-21`，实际 runtime `21.0.7+8-LTS-245`；PATH 排除 Oracle javapath，清除继承应用凭据和 JVM 注入选项。
- 只用 cached `postgres:16-alpine`，`--pull never`，按实际 image ID 启动：`sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`。机器报告保留 `postgres` 与 mirror 的 RepoDigests。
- 随机 owned label/name、独立随机 bridge network、loopback 随机端口、256 MiB data tmpfs、512 MiB memory、1 CPU、128 pids、no-new-privileges。
- 凭据为本 run 新生成的 URL-safe 随机值，仅通过进程环境/测试连接传入，不使用真实 secret 文件。唯一 JDBC URL 由临时端口构造：`jdbc:postgresql://127.0.0.1:<port>/aetheris_nonce_audit`，用户名 nonce_admin；IT 还再次核对 loopback 和 fixture DB 名。
- finally 按 exact label/name 验证并删除本次容器及空网络，实查 `containerRemoved/networkRemoved/volumesAbsent=true`；没有命名数据卷。失败或超时时同样清理，Maven 子进程超时按其独占 PID 终止进程树。

通过机器报告：`output/audit-nonce-store/2525372c5eca4edcadc6d5c39f7dace8/report.json`。

| 文件 | SHA-256 |
|---|---|
| 通过 report | `6693FFB9D89F0C89CBFD9C28D926FDDCE416A5F7EEBCE650B31850BE442ADAEB` |
| PostgresNonceStore.java | `AE5A1F4BDF0A6E5C16424CF2EB96054D5BDA3972F8BB476AB8027EA05CCBBE8E` |
| PostgresNonceStoreIT.java | `6A5AE2B98DAD3129A69C44FB7FF71CAF7C89451AE26BB40F88E3AE1F79861771` |
| audit-nonce-store.py | `3F9AF55DA9C7F9C91799F5FE710B4AB46D3A18401EFB287E133A2FE888C4D2B6` |
| test_nonce_store_audit.py | `738F6DB766E764190D9D9782B06FE0A969D20DCAEC4F3A789AF09F7973FE26A6` |

同目录保留脱敏 `maven.log` 与实际 `surefire.xml`；通过 run 未发现需要替换的 fixture secret，机器报告仍分别记录原 XML 和保存 XML 哈希。依赖 jar、完整源码及编译产物 hash 也逐项记录。

## 范围限制与复跑

未修改 protocol/servlet 源码、现有 POM、应用配置、真实数据库、部署或共享台账。`security-servlet/target` 的写入仅来自获授权的 Maven 构建。没有付费网络、现有服务调用、私钥安装、Tomcat 启动、业务 RBAC、execution receipt/unknown 或生产多实例验收。新建 store 对象证明同库记录的持久性，不能外推为 PostgreSQL 重启、JVM 崩溃恢复或生产可用性证明。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
python -B -m unittest discover -s scripts/tests -p test_nonce_store_audit.py -v
python -B scripts/audit-nonce-store.py
```

需要 cached PG16、离线 Maven 依赖及当前冻结 class 基线；工具拒绝覆盖旧输出。真实测试命令为 `mvn -q -o -f libs/security-servlet/pom.xml -Dtest=PostgresNonceStoreIT test`，源或 class 变动会使对应 run 失败，不能沿用旧通过报告。

# 数仓SQL与金额口径隔离验收

2026-10-18，run `5f2433a8981945b9b0115aba58b72c48`，**10:46:00–10:46:43 UTC**，容器exit 0、状态passed。实际使用缓存`apache/spark:3.5.6`的Linux amd64镜像ID及RepoDigest均为`sha256:599722640c8a42ff5ecca329a3faa5371b3bd5c752b320776d5cdd1eed3441ac`，没有拉取镜像。

此结果证明当前SQL在隔离Spark/Hive本地catalog中的行为，不代表现有WSL/HDFS、HiveServer2 JDBC、平台调度/权限或生产数仓已验收。

## 实际执行与结果

采用1000用户、128商品、2024-02-28起5天、日均基数400单的合成数据，跨闰日和月末。原始`f22a32c`的两项缺陷在同一隔离catalog实际复现：

1. 仅解析原始日期维DDL，出现预期`ParseException`，不把原生库加载等环境异常算作语法缺陷复现。
2. 原始ODS金额配合当前DWD加工后，`order-line-payment`实际抛指定断言异常，证明订单和明细独立金额不能按定义对账。

随后完整执行当前6份SQL：建库/维度、ODS、DWD、DWS、ADS和校验。**21项断言全部通过**，另用11个仅限临时view的损坏数据反例验证必须失败，再验证原表仍通过，共33项检查。未通过的执行不会发布为passed；反例只有出现指定断言的实际运行异常才算拒绝，不接受表名拼错或其他无关错误。

覆盖唯一性、去重/删除、16表非空、明细净额、订单支付、已知支付金额桥、DWD→DWS与DWS→ADS日/月对账、用户月去重、品类份额/排名、日期连续与覆盖、ADS日期属性、手机号/邮箱掩码和清洗值。反例包括DWS/ADS金额偏移1分钱、份额/人数错误、空汇总、日期缺失、原始手机号/邮箱、订单金额和明细优惠变化，均被拒绝。

本次真实结果：

| 数据 | 数量/金额 |
|---|---:|
| ODS订单行 / DWD去重非删除订单 | 2746 / 2701 |
| ODS / DWD明细行 | 6757 / 6757 |
| DWD用户 / 商品 | 1000 / 128 |
| 日期维 / 品类维 | 62 / 8 |
| ADS订单日报 / 品类月报 / 用户群体月报 | 5 / 18 / 143 |
| 已知实付GMV | 283318.21 |
| 有效订单全部明细净额 | 285646.62 |
| 实付缺失订单对应的明细净额 | 2328.41 |

金额桥实际精确为`285646.62 - 2328.41 = 283318.21`。模拟订单按整数分汇总明细减优惠；运费与订单级优惠明确为0。缺失实付不冒充已确认0元支付。qty非法值置NULL，已有明细金额仍保留，销售件数与金额分别按既定口径统计。真实来源的运费、订单优惠或未知差异应建立来源字段，不能直接套用模拟的0值。

## 工具、边界与清理

- 工具：`scripts/audit-warehouse.py`、`scripts/warehouse/audit_spark.py`；6项工具行为测试通过。
- 仅只读挂载本run的合成SQL与helper，`--network none`、只读root、非root 185、2 CPU、2 GiB、512 pids、drop ALL/no-new-privileges；catalog、Derby metastore与数据均在独占tmpfs `/tmp`。不挂现有仓库或安装凭据。
- JVM显式ActiveProcessorCount=2、768 MiB heap、ExitOnOutOfMemoryError；隔离测试关闭AQE，使用2个本地worker和2个shuffle分区。这些是验收资源配置，不是生产性能建议。
- tmpfs明确exec供镜像内Snappy原生库加载，其余nosuid/nodev；镜像若声明匿名volume即拒绝。只运行实际inspect到的image ID，避免tag在运行中漂移。
- 源SQL、工具与渲染附件SHA-256逐项保留，末尾`sourceHashesMatchAtEnd=true`。所有检查必须齐全且命名匹配，旧/重复/缺项结果不能通过。
- finally核对随机owned label后删除本次容器与bundle。`containerRemoved/noVolumesCreated/bundleRemoved=true`；root随后独立Docker查询该run容器/卷均0，bundle不存在。没有创建额外网络、访问现有数据库、提交或部署。

机器证据：`output/audit-warehouse/5f2433a8981945b9b0115aba58b72c48/report.json`，SHA-256 `92200B2A0DA29092E36792AA18C626B585EAE2E0CADE3F595ACB34DA498735C7`。

同目录`spark.log` SHA-256 `2237193D3CB4EBBAB1C2C0C61E88BAC6540AAA821131F9C71F7DD55CB89D6474`。日志仅含合成数据和预期反例异常；不包含现有业务数据或真实凭据。

## 保留的失败记录

前三次报告全部保持failed，没有回填：

- `0acf222a061c4c72b59cee204135c2b9`：容器tmpfs默认不可执行导致Snappy `.so`不能加载，未通过SQL验收。
- `16302591a2754916a72b7ea270d4744a`：JVM/执行器耗尽256线程配额，root按唯一owned标签停止挂起的容器，报告失败并清理；不能当作应用SQL错误。
- `dbde9ffef68a4381b04272dd38965d8c`：21项正例和前9项反例通过，工具的字符串替换错误连带修改`dwd_order_item`表名。修成完整表名匹配并补工具回归后重跑全部检查；没有将这类AnalysisException当作断言拒绝。

复跑先准备本地缓存Spark镜像，再执行：

```powershell
python -B -m unittest discover -s scripts/tests -p test_warehouse_acceptance.py -v
python -B scripts/audit-warehouse.py
```

工具拒绝覆盖已有run，原缺陷基线固定读取`f22a32c` Git对象；缺少该对象会失败，不静默跳过。真实Thrift/JDBC、合法加工SQL守卫兼容、平台权限链及最终冻结发布证据仍应各自验收。

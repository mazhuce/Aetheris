# 数仓分层与测试数据

Aetheris 的数仓分层建库、造数与逐层加工脚本，运行在 WSL 的 Spark Thrift Server 上。
启动 Hive 栈的方法见 [datasource-connectivity.md](datasource-connectivity.md)。

## 分层约定

| 层 | 库名 | 职责 | 分区 |
| --- | --- | --- | --- |
| 贴源层 | `ods` | 与源系统结构一致，字段多为 STRING，**保留原始脏数据**，不做任何清洗 | 事实表按 `dt` |
| 明细层 | `dwd` | 去重、类型转换、枚举标准化、过滤非法值后的事实明细 | 事实表按 `dt` |
| 汇总层 | `dws` | 按主题轻度聚合的宽表，粒度为「维度 + 日期」 | 按 `dt` |
| 应用层 | `ads` | 面向报表与指标的最终结果表，口径已固化 | 不分区 |
| 维度层 | `dim` | 公共维表，供各层关联 | 不分区 |

业务域为**电商**，与既有血缘种子数据（`ods_order → dwd_order → ads_order_stats`）一致。

## 表清单

```text
dim.dim_category          商品品类维度（8 个一级品类）
dim.dim_date              日期维度（覆盖订单区间，前后各留余量）

ods.ods_user              用户全量快照
ods.ods_product           商品全量快照
ods.ods_order             订单主表（按 dt 分区）
ods.ods_order_item        订单明细表（按 dt 分区）

dwd.dwd_user              用户明细（含 PII 脱敏）
dwd.dwd_product           商品明细（含毛利率）
dwd.dwd_order             订单明细（按 dt 分区）
dwd.dwd_order_item        订单商品明细（按 dt 分区，已打平商品与订单维度）

dws.dws_order_daily       每日订单汇总
dws.dws_category_daily    每日品类销售汇总
dws.dws_user_daily        每日用户群体汇总

ads.ads_order_stats       订单核心指标日报（GMV、客单价、退款率、环比、7日均值）
ads.ads_category_stats    品类月度销售排行（含排名与占比）
ads.ads_user_profile      用户群体画像月报
```

## 使用

```bash
# 前置：启动 Hive 栈
bash scripts/wsl-hive-test-server.sh start

# 默认 wsl 档，全流程建库 + 造数 + 逐层加工 + 校验
bash scripts/gen-warehouse-data.sh

# 只重跑指定步骤（1建库 2ODS 3DWD 4DWS 5ADS 6校验）
bash scripts/gen-warehouse-data.sh --steps 3,4,5

# 只渲染 SQL 不执行，用于检查占位符替换结果
bash scripts/gen-warehouse-data.sh --dry-run
```

### 规模档位

| 档位 | 用户 | 商品 | 订单 | 明细 | 用途 |
| --- | --- | --- | --- | --- | --- |
| `tiny` | 2 千 | 500 | 约 1 万 | 约 2.9 万 | 改脚本时快速验证语法 |
| `wsl` | 10 万 | 1 万 | 约 100 万 | 约 250 万 | 默认，单机可跑 |
| `cluster` | 100 万 | 10 万 | 约 1000 万 | 约 2500 万 | VMware 四节点压测 |

订单区间从**今天倒推**，因此「上个月」「近 7 天」这类自然语言问题始终有对应分区。

### 资源要求

`wsl` 档需要 Spark driver 至少 **3g** 堆内存。`wsl-hive-test-server.sh` 已默认按 3g 启动
（`local[N]` 模式下 driver 同时充当 executor，早期的 1g 会在写百万级订单时抛
`OutOfMemoryError: Java heap space` 并导致 Thrift server 进程退出）。

可用环境变量调整，改完需重启 Hive 栈才生效：

```bash
AETHERIS_SPARK_DRIVER_MEMORY=4g \
AETHERIS_SPARK_LOCAL_CORES=4 \
  bash scripts/wsl-hive-test-server.sh restart
```

写入动态分区的语句末尾都带 `DISTRIBUTE BY dt`。这不是可选优化：缺了它，单个 task 会
同时持有全部 `dt` 分区的 Parquet writer，每个 writer 各自缓冲一个 row group，
在单机 driver 上会直接耗尽堆内存。改这些 SQL 时不要删掉。

## 数据特征（有意设计，不是缺陷）

这三个特征决定了后续 DataAgent 与质量规则能否练出真本事：

**1. 大事实表按 `dt` 分区** —— 使分区裁剪成为真实的查询优化问题。Agent 必须学会带分区条件，否则全表扫描。

**2. 刻意注入脏数据** —— ODS 层保留以下问题，DWD 逐条清洗：

| 脏数据类型 | 位置 | 比例 | DWD 处理方式 |
| --- | --- | --- | --- |
| 性别枚举混用 `M/F/男/女/未知/空` | `ods_user.gender` | 约 6% 空值 | 归一为 `M/F/U` |
| 年龄超范围（999） | `ods_user.age` | 约 2% | 置 NULL |
| 邮箱缺少 `@` | `ods_user.email` | 约 3% | 置 NULL |
| 会员等级非法值 | `ods_user.member_level` | 约 3% | 归入「普通」 |
| 时间格式混用两种 | `ods_user.register_time` | 20% 为斜杠格式 | 统一解析 |
| 单价为负 | `ods_product.price` | 约 1% | 置 NULL |
| 品类不存在（99）或空 | `ods_product.category_id` | 约 3.5% | 落为「未分类」 |
| 订单状态非法（9）或空 | `ods_order.order_status` | 约 2% | 归入「未知」 |
| 订单重复行 | `ods_order` | 约 0.3% | `ROW_NUMBER` 去重 |
| 逻辑删除未过滤 | `ods_order.is_deleted` | 约 1.5% | 过滤掉 |
| 数量为 0 或负数 | `ods_order_item.quantity` | 约 1.1% | 置 NULL |

**3. 所有字段带中文注释** —— 这是 NL2SQL 准确率最重要的杠杆。「客单价」能否命中 `avg_order_amount`，全靠注释与指标定义。

### 业务规律

数据不是均匀噪声，含真实可分析的规律：

- **周末效应**：周末订单量约为工作日的 1.6 倍
- **增长趋势**：订单量随时间缓慢增长，叠加 ±8% 抖动
- **金额由明细推导**：每单1–4行商品，明细按分定价，实付由明细扣优惠后合计
- **下单时段**：集中在 10-22 点
- **状态分布**：倾斜到「已完成」，符合真实电商

## 口径定义

后续语义层的指标定义以此为唯一来源：

```text
有效订单 = order_status IN (2, 3, 4)   已付款 / 已发货 / 已完成
           排除待付款(1)、已取消(5)、已退款(6)与非法状态
GMV      = 有效订单的 pay_amount 之和
客单价    = GMV / 有效订单数
退款率    = 退款订单数 / 订单总数
```

`dwd_order.is_valid_order` 是这个口径的物理落点，DWS/ADS 全部基于它。

模拟造数中，`item_amount`是整行金额，不再乘一次quantity；`discount`是明细级优惠，空值按0处理且不超过整行金额。订单金额在整数分上先汇总再转DECIMAL，满足`pay_amount = SUM(item_amount-discount)`。本示例不生成运费或订单级优惠，两者明确为0，不能用“可能是运费”解释未知差异。真实来源有这些字段时，应先增加独立字段和来源映射，再修改金额桥。

约1.1%的订单故意保留缺失实付金额，用于质量演示。GMV的SQL SUM不计这些NULL；品类销售额仍计入其明细净额。因此跨口径桥为`已知实付GMV = 有效订单明细净额 - 实付缺失订单的明细净额`。校验单列缺失金额对应的明细，不能将NULL当作确认支付0元，也不能把“明细全量净额”等同于GMV。有效订单数量仍按状态统计，客单价按现有GMV/有效订单数定义；使用此模拟指标须同时了解金额缺失。

早期版本分别生成订单金额与明细金额，无法按上述业务口径对账。重新执行第2–6步会改变模拟金额；旧造数结果不是当前口径的验收证据。该脚本会DROP并重建示例表，只能在已确认可重建的演示环境执行；本轮隔离验收不连接现有WSL、HDFS或业务目录。

用户DWD只保留掩码手机号和邮箱本地部分前2位加掩码，ODS保留模拟原值。掩码是演示数据最小展示约束，不是匿名化保证。

## 校验

`06_validate.sql` 是只读的，可反复执行，同时也是后续 `quality` 服务规则的基准：

- 各层行数总览
- ODS 脏数据比例（应与设计比例吻合）
- **DWD 订单重复数必须为 0**（清洗是否生效的硬指标）
- ODS 与 DWD 行数差 = 重复行 + 逻辑删除行
- 分区覆盖情况
- **DWS 的 GMV 必须等于 DWD 明细直接聚合**（口径是否脱节）

末尾21项`assert_true`执行真正失败断言，包括各层非空、主键/明细唯一、非删除订单去重、明细金额与支付桥、DWD→DWS日汇总、DWS→ADS日/月汇总、用户月去重、品类份额/排名及舍入容差、日期维连续/覆盖、ADS日期属性、PII掩码和清洗值。beeline显式`--force=false`；断言失败不能继续报告造数成功。

`python -B scripts/audit-warehouse.py`仅使用本机缓存`apache/spark:3.5.6`，在无网络、独立tmpfs目录和元数据的临时容器执行5个跨闰日/月末的合成日期，复现原始语法/金额失败，并验证被改动1分钱、份额/人数错误、缺失汇总和PII原值等反例。实际run已通过21项断言、11项拒绝反例和原表恢复复验，详见[隔离验收及限制](remediation/warehouse-acceptance.md)；不代表现有WSL/HDFS或平台任务已经通过。

## 在 Aetheris 中查看

造数完成后，在平台里建数据源并采集，即可看到分层结构：

1. 启动端口转发（`metadata-service` 在 Docker 里，不能直连 WSL 动态 IP）：

   ```powershell
   pwsh ./scripts/windows-wsl-hive-relay.ps1 start
   ```

   注意输出的监听地址。Hyper-V Default Switch 的地址会随重启变化，
   数据源主机要填脚本实际输出的地址，不能沿用文档里的历史值。
   （`host.docker.internal` 在本机解析到 IPv6，而转发只监听 IPv4，因此不可用。）

2. 数据源管理页新建数据源，选 `WSL · Spark Thrift` 预设，主机改为上一步的地址。

3. 元数据目录页点「采集元数据」，选该数据源，库留空以采集全部。

采集后可以看到：

- **元数据目录**：18 张表，带分层标签、业务域筛选、表注释与字段数；
- **数仓地图**：按 ODS → DWD → DWS → ADS 的流向展示，点层级看该层表清单；
- **数据血缘**：点「解析 SQL」，粘贴 `scripts/warehouse/03_dwd.sql` 等加工脚本，
  即可生成 17 条真实血缘边，贯通 ODS → DWD → DWS → ADS。

### 分层推断规则

采集时按库名推断层级，库名匹配不上时退回表名前缀：

| 名称 | 推断结果 | 说明 |
| --- | --- | --- |
| `ods` / `ods.db` / `ods_tmp` | `ods` | 层级名后跟分隔符（`_` `-` `.`）或完全相等 |
| `default` + 表名 `ods_orders` | `ods` | 库名推断不出时看表名前缀 |
| `adsense` / `odyssey` | `other` | **不匹配**：层级名后必须跟分隔符，避免前缀误伤 |
| `public` / `my_db` | `other` | 未命中分层约定 |

推断结果写入 `layer` 字段，`layer_source=auto`。在元数据目录里人工改过层级后
（`layer_source=manual`），重复采集不会覆盖。

### 表注释的采集

Spark/Iceberg 的 `SHOW TABLES` 与 JDBC `getTables()` 都不返回 `REMARKS`，
因此采集时会逐表执行 `DESCRIBE FORMATTED` 读取 Comment 行。
这一轮额外查询是值得的——表注释是 NL2SQL 命中率最重要的信息来源。

## 血缘解析

`/api/metadata/lineages/parse` 从加工 SQL 提取表级血缘。

**为什么不用 JSqlParser**：实测 4.9 版无法解析 Hive 的
`INSERT OVERWRITE TABLE ... PARTITION (dt)`，本项目的四类真实写法全部抛
`JSQLParserException`。因此改用面向 Hive 方言的提取器，覆盖
`INSERT OVERWRITE/INTO`、`LATERAL VIEW`、`DISTRIBUTE BY`、`CREATE TABLE AS SELECT`。

排除项（否则会产生不存在的表节点）：

- CTE 名称（`WITH x AS (...)` 里的 `x`）
- 表值函数（`range()`、`EXPLODE()`、`SEQUENCE()`）
- 自引用（`INSERT INTO t SELECT ... FROM t`）
- 注释与字符串字面量里的 `from` / `join`

同一目标表重复解析时，先删除它既有的解析边再写入新的，因此 SQL 改写后不会残留
过期依赖。人工登记的血缘（`source=manual`）不受解析影响。

当前为**表级**血缘，不追踪字段加工路径。字段级血缘需要完整 AST，
等引入真正支持 Hive 方言的解析器后再做。

## 权限说明

造数脚本用**当前用户**（HDFS 仓库属主）连接，因为要向 HDFS 写入。
Aetheris 平台侧的数据源仍用 `aetheris` 账号**只读**访问 —— 仓库目录对其他用户开放了 `r-x`，
`SELECT` 不受影响。读写权限分离是有意为之，与后续只读查询账号的设计一致。

用 `AETHERIS_JDBC_USER` 可覆盖连接用户，`AETHERIS_JDBC_URL` 可覆盖 JDBC 地址
（例如指向 VMware 集群的 HiveServer2）。

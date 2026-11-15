// sim 学习中心的课程内容与演示数据。
// 与真实 learning-service 的接口结构对齐（见 src/api/learning.ts），
// 额外扩展两个字段：relatedFeatures（对应系统功能页）、knowledge（SQL/环节知识点）。
// 业务背景：云集电商交易域，表结构与行数按真实生产感虚构。

export const TABLES = {
  ods_orders_raw: '原始订单流水，约 3.2 亿行，按天分区，含逻辑删除、空用户、负金额等脏数据',
  ods_order_status_dict: '订单状态字典，12 行，code→name 映射',
  ods_payments: '支付流水，约 2.8 亿行，按天分区',
  ods_refunds: '退款流水，约 900 万行，按天分区',
  dim_shop: '店铺维表，约 42 万行，含店铺一级/二级类目',
  ods_users: '用户表，约 8600 万行，手机号为敏感字段',
  dwd_order_detail: '清洗后订单明细（本路径产出），按天分区',
  dws_shop_sales_daily: '店铺日销售聚合，按天分区',
  ads_gmv_daily: '全站 GMV 日报'
}

const R = (rows, filters, style) => [
  { key: 'rows', name: '结果行数', weight: rows, passed: true, detail: `与标准答案行数一致` },
  { key: 'filters', name: '口径正确', weight: filters, passed: true, detail: '过滤与聚合口径符合任务要求' },
  { key: 'style', name: 'SQL 规范', weight: style, passed: true, detail: '列裁剪、别名与缩进符合规范' }
]

const K = (title, detail) => ({ title, detail })

/* ------------------------------------------------------------------ *
 * 路径一：数据开发工程师入门（de-basic，L1-L2）
 * ------------------------------------------------------------------ */

const deModules = [
  {
    code: 'de-m1',
    title: 'L1 · SQL 基础与数据认知',
    objective: '从零开始读表、查询和过滤，理解 NULL 与 JOIN 的行为，为后续清洗打基础。',
    level: 'L1',
    relatedFeatures: ['SQL开发', '元数据管理'],
    labs: [
      {
        code: 'de-l1-01',
        title: '第一条查询：SELECT 与列裁剪',
        taskType: 'sql',
        difficulty: 'easy',
        estimatedMinutes: 15,
        passScore: 60,
        summary: '从订单流水表里取出指定列，养成"用多少列取多少列"的习惯。',
        scenario: `你是刚入职的数据开发。订单中台把原始订单流水同步进了 ods_orders_raw（${TABLES.ods_orders_raw}）。报表组要一份"当天订单的订单号、用户ID、金额"清单，明确说不要多余列——大表上 SELECT * 会把扫描和传输成本放大好几倍。`,
        objective: '只输出 order_id、user_id、pay_amount 三列，行数与全表当月分区一致，不出现重复列。',
        starterSql: '-- 试运行：先看这条 SQL 会扫多少列\nSELECT *\nFROM ods_orders_raw\nWHERE dt = \'2026-11-01\'\nLIMIT 100;',
        steps: [
          { title: '看表结构', detail: '在元数据管理里打开 ods_orders_raw，确认列名、类型与分区字段 dt。' },
          { title: '改写列裁剪', detail: '把 SELECT * 换成明确的三列，并给 pay_amount 加别名 amount。' },
          { title: '核对结果', detail: '确认输出列名与行数，检查金额列没有科学计数法或精度丢失。' },
          { title: '提交判分', detail: '提交 SQL，服务端在只读事务中执行并比对标准结果。' }
        ],
        tableRefs: ['ods_orders_raw'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '元数据管理'],
        knowledge: [
          K('列裁剪（Projection Pruning）', '只 SELECT 需要的列。列式存储下扫描的列数直接决定耗时与 IO，SELECT * 在宽表上代价极高；列裁剪也是引擎做分区裁剪之外最有效的提速手段。'),
          K('别名的两种写法', 'pay_amount AS amount 与 pay_amount amount 等价。别名在后续 GROUP BY / ORDER BY 中可直接引用，但同一条 SQL 里 WHERE 不能引用 SELECT 别名——逻辑执行顺序是 FROM → WHERE → GROUP BY → SELECT → ORDER BY。')
        ]
      },
      {
        code: 'de-l1-02',
        title: '过滤与排序：WHERE / ORDER BY / LIMIT',
        taskType: 'sql',
        difficulty: 'easy',
        estimatedMinutes: 20,
        passScore: 60,
        summary: '用组合条件筛出"已支付的大额订单"，并按金额倒序取前 100。',
        scenario: `风控同学要看 11 月 1 日支付金额超过 1000 元的订单，按金额从高到低取前 100 条做人工复核。ods_orders_raw 里 status 字段是编码，2 表示已支付；金额字段里混有 0 和负数的脏数据，需要一并排除。`,
        objective: 'status = 2 且 pay_amount > 1000，按 pay_amount 倒序取前 100 行；金额必须大于 0。',
        starterSql: 'SELECT order_id, user_id, pay_amount\nFROM ods_orders_raw\nWHERE dt = \'2026-11-01\'\n  AND status = 2\nLIMIT 100;',
        steps: [
          { title: '补全过滤条件', detail: '加上 pay_amount > 1000，并显式排除 pay_amount <= 0 的脏行。' },
          { title: '排序', detail: 'ORDER BY pay_amount DESC；注意 SQL 里 NULL 参与排序的位置因引擎而异。' },
          { title: '限制行数', detail: 'LIMIT 100 放在最后；排序在 LIMIT 之前执行，所以拿到的是"全量排序后的前 100"。' },
          { title: '提交判分', detail: '核对结果后提交。' }
        ],
        tableRefs: ['ods_orders_raw'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发'],
        knowledge: [
          K('AND / OR 的优先级', 'AND 优先级高于 OR，a OR b AND c 等价于 a OR (b AND c)。拿不准就加括号，可读性永远优先。'),
          K('排序的稳定性与代价', 'ORDER BY 在大数据量下会触发全量排序甚至 spill 到磁盘。如果只关心"每组前 N"，优先考虑窗口函数而不是先排序再过滤。')
        ]
      },
      {
        code: 'de-l1-03',
        title: 'NULL 的三值逻辑与 COALESCE',
        taskType: 'sql',
        difficulty: 'easy',
        estimatedMinutes: 20,
        passScore: 60,
        summary: '订单表里 user_id 为 NULL 表示游客单；学会判断 NULL、给 NULL 兜底，以及为什么 COUNT(*) 不等于 COUNT(user_id)。',
        scenario: `运营要统计 11 月 1 日的"注册用户订单数"。ods_orders_raw 中 user_id 为 NULL 是游客下单（${TABLES.ods_orders_raw}）。直接 WHERE user_id != \'\' 或 = NULL 都拿不到正确结果——这是新人最常见的坑。`,
        objective: '输出注册用户订单数、游客订单数、总订单数三行；user_id 为 NULL 判定为游客。',
        starterSql: '-- 三种写法，哪种能查到 NULL？\nSELECT COUNT(*) FROM ods_orders_raw WHERE dt = \'2026-11-01\' AND user_id = NULL;\nSELECT COUNT(*) FROM ods_orders_raw WHERE dt = \'2026-11-01\' AND user_id != \'xxx\';\nSELECT COUNT(*) FROM ods_orders_raw WHERE dt = \'2026-11-01\';',
        steps: [
          { title: '理解三值逻辑', detail: 'NULL 与任何值的比较结果都是 UNKNOWN，WHERE 只保留 TRUE。= NULL 永远不成立。' },
          { title: '改用 IS NULL', detail: '用 user_id IS NULL 区分游客；同时体会 COUNT(*) 与 COUNT(user_id) 的差异。' },
          { title: '给 NULL 兜底', detail: '用 COALESCE(user_id, \'guest\') 把 NULL 变成可读的占位值再分组。' },
          { title: '提交判分', detail: '三个数字与标准答案对齐后提交。' }
        ],
        tableRefs: ['ods_orders_raw'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发'],
        knowledge: [
          K('三值逻辑（TRUE / FALSE / UNKNOWN）', 'NULL 参与任何比较（=、!=、>、LIKE）结果都是 UNKNOWN 而不是 FALSE。判断 NULL 只能 IS NULL / IS NOT NULL。这也是为什么 NOT IN 遇到 NULL 子查询会整条返回空。'),
          K('COUNT(*) 与 COUNT(col)', 'COUNT(*) 统计行数；COUNT(col) 跳过 NULL。同理 SUM 会忽略 NULL 但空集时返回 NULL 而不是 0，需要用 COALESCE(SUM(x), 0) 兜底。')
        ]
      },
      {
        code: 'de-l1-04',
        title: '关联字典表：JOIN 的正确姿势',
        taskType: 'sql',
        difficulty: 'easy',
        estimatedMinutes: 25,
        passScore: 60,
        summary: '把订单状态编码翻译成可读名称；理解 INNER 与 LEFT JOIN 的差异，以及小表关联的重复行陷阱。',
        scenario: `报表不能给业务方看 status = 2 这种编码。ods_order_status_dict（${TABLES.ods_order_status_dict}）是字典表。注意：字典里有一条 status = 99（测试单）在订单表里出现了多次，INNER JOIN 会把测试单也带出来；而有些异常订单的 status 在字典里不存在，INNER JOIN 会把它们丢掉。`,
        objective: '输出订单号、状态编码、状态名称；保留字典中不存在的异常订单（状态名给"未知"），并排除测试单。',
        starterSql: 'SELECT o.order_id, o.status, d.status_name\nFROM ods_orders_raw o\nJOIN ods_order_status_dict d ON o.status = d.status_code\nWHERE o.dt = \'2026-11-01\'\nLIMIT 100;',
        steps: [
          { title: '换成 LEFT JOIN', detail: '以订单为主表 LEFT JOIN 字典，观察哪些行的 status_name 变成 NULL。' },
          { title: '排除测试单', detail: '字典中 status_code = 99 是测试单，直接在 JOIN 条件或 WHERE 里排除。' },
          { title: '给未知状态兜底', detail: '用 COALESCE(d.status_name, \'未知\') 处理孤儿编码。' },
          { title: '提交判分', detail: '核对行数与状态名后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'ods_order_status_dict'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '语义依赖图'],
        knowledge: [
          K('INNER 与 LEFT JOIN', 'INNER JOIN 只留两边都匹配的行；LEFT JOIN 保留左表全部。事实表关联字典表时默认用 LEFT JOIN——字典缺失不应该让事实蒸发，这是数仓口径稳定性的基本要求。'),
          K('重复行放大（Fan-out）', '如果右表关联键不唯一，左表一行会被复制成多行，行数悄悄变大且难以察觉。关联前先确认右表键唯一，或者先对右表去重/聚合。')
        ]
      },
      {
        code: 'de-l1-05',
        title: 'GROUP BY 与聚合函数',
        taskType: 'sql',
        difficulty: 'easy',
        estimatedMinutes: 20,
        passScore: 60,
        summary: '按状态统计当天订单量与支付金额，理解"非聚合列必须出现在 GROUP BY 里"。',
        scenario: `日报需要一行一个状态：订单量、支付总金额、平均金额。注意订单表里有负金额的冲正单，直接 SUM 会把 GMV 拉低——先决定是否排除。`,
        objective: '按 status 分组输出订单数、SUM(pay_amount)、AVG(pay_amount)；排除 pay_amount <= 0 的冲正单。',
        starterSql: 'SELECT status, COUNT(*), SUM(pay_amount)\nFROM ods_orders_raw\nWHERE dt = \'2026-11-01\'\nGROUP BY status;',
        steps: [
          { title: '明确口径', detail: '冲正单（负金额）算不算订单量？本任务口径：排除后再统计。' },
          { title: '补齐聚合列', detail: '加 AVG；非聚合列必须出现在 GROUP BY 中，否则大多数引擎直接报错。' },
          { title: '命名输出列', detail: '给三个聚合列起语义化别名：order_cnt、gmv、avg_amount。' },
          { title: '提交判分', detail: '与标准结果比对后提交。' }
        ],
        tableRefs: ['ods_orders_raw'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '指标管理'],
        knowledge: [
          K('GROUP BY 的铁律', 'SELECT 里的列要么出现在 GROUP BY，要么包在聚合函数里。这不是风格问题，是语义问题——引擎无法决定同一组里取哪一行的值。'),
          K('聚合对 NULL 的处理', '除 COUNT(*) 外，聚合函数都会忽略 NULL 行。SUM 一列全 NULL 返回 NULL；想得到 0 就包一层 COALESCE。')
        ]
      },
      {
        code: 'de-l1-06',
        title: '去重计数与近似计算',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 20,
        passScore: 60,
        summary: '统计当天独立买家数；理解 COUNT(DISTINCT) 在亿级表上的代价与近似方案的取舍。',
        scenario: `运营要"当日独立买家数"。ods_orders_raw 单日约 180 万行、买家约 60 万。精确 COUNT(DISTINCT) 需要全量去重，在大表上是昂贵的操作；数据团队约定：日报允许 1% 以内误差，可用近似算法。`,
        objective: '输出当日订单数、独立买家数（精确），并用注释说明如果要近似计算应该怎么做。',
        starterSql: 'SELECT COUNT(*) AS order_cnt\nFROM ods_orders_raw\nWHERE dt = \'2026-11-01\';',
        steps: [
          { title: '先写精确版', detail: 'COUNT(DISTINCT user_id)，注意它天然跳过 NULL（游客不计入买家）。' },
          { title: '评估代价', detail: '想清楚去重发生在哪一步：shuffle 全量 user_id 到同一节点。' },
          { title: '写出近似方案', detail: '在注释里写明近似思路（如 HLL / HyperLogLog 类函数）及误差预期。' },
          { title: '提交判分', detail: '核对买家数后提交。' }
        ],
        tableRefs: ['ods_orders_raw'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '指标管理'],
        knowledge: [
          K('去重的代价', 'COUNT(DISTINCT) 需要把所有候选值 shuffle 到一起做去重，内存与耗时随基数线性增长。亿级表上它经常是任务里最慢的一步。'),
          K('精确与近似的取舍', '基数统计类算法（HyperLogLog 等）用固定内存换可控误差（通常 <1%）。能用近似的场景（日报、大盘）就用近似，要精确的场景（财务对账）必须精确——口径先于性能。')
        ]
      },
      {
        code: 'de-l1-07',
        title: 'HAVING 与聚合后过滤',
        taskType: 'sql',
        difficulty: 'easy',
        estimatedMinutes: 15,
        passScore: 60,
        summary: '找出当天订单量超过 1 万的店铺；体会 WHERE 与 HAVING 的执行顺序差异。',
        scenario: `运营想定位"爆款店铺"：11 月 1 日订单量超过 1 万的店铺。店铺信息在 dim_shop（${TABLES.dim_shop}），需要先关联再按店铺聚合。过滤"聚合后结果"必须用 HAVING，写在 WHERE 里会直接报错。`,
        objective: '输出店铺名、订单数，只保留订单数 > 10000 的店铺，按订单数倒序。',
        starterSql: 'SELECT s.shop_name, COUNT(*) AS order_cnt\nFROM ods_orders_raw o\nJOIN dim_shop s ON o.shop_id = s.shop_id\nWHERE o.dt = \'2026-11-01\'\nGROUP BY s.shop_name;',
        steps: [
          { title: '先跑通关联', detail: '确认 JOIN 后行数与订单表一致（dim_shop 的 shop_id 唯一）。' },
          { title: '加 HAVING', detail: 'HAVING COUNT(*) > 10000；尝试把它写进 WHERE 观察报错，加深理解。' },
          { title: '排序输出', detail: 'ORDER BY order_cnt DESC。' },
          { title: '提交判分', detail: '核对店铺清单后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'dim_shop'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发'],
        knowledge: [
          K('WHERE 与 HAVING 的顺序', '逻辑执行顺序 FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT。WHERE 过滤原始行（先过滤少聚合），HAVING 过滤聚合结果——能用 WHERE 就不要用 HAVING，前者成本低得多。')
        ]
      }
    ]
  },
  {
    code: 'de-m2',
    title: 'L2 · ODS 到 DWD：数据清洗与分层',
    objective: '把脏的原始流水加工成可复用的明细宽表，掌握幂等去重、字典归一与增量加工。',
    level: 'L2',
    relatedFeatures: ['SQL开发', '物理模型', '数据质量'],
    labs: [
      {
        code: 'de-l2-01',
        title: '幂等去重：ROW_NUMBER 保留最新一条',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 70,
        summary: '原始流水里同一订单被同步了多次，取 update_time 最新的一条，并且重跑结果必须一致。',
        scenario: `上游 SDK 偶尔重发导致 ods_orders_raw 同一 order_id 出现 2-5 条记录，字段值可能不同。DWD 层要求一个 order_id 只有一行，且任务重跑（比如补数）后结果完全一致——这是幂等性，生产上最重要的要求之一。`,
        objective: '按 order_id 去重，保留 update_time 最大的一行；输出到 dwd_order_detail 的口径；重跑两次结果一致。',
        starterSql: 'SELECT order_id, update_time, pay_amount\nFROM ods_orders_raw\nWHERE dt = \'2026-11-01\';',
        steps: [
          { title: '验证重复规模', detail: '先查当天有多少 order_id 出现多次，心里有数。' },
          { title: '窗口函数去重', detail: 'ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY update_time DESC) = 1。' },
          { title: '验证幂等', detail: '把同一条 SQL 跑两遍比对结果；再想想为什么"先 DISTINCT 后随机取一条"是不行的。' },
          { title: '提交判分', detail: '行数与内容双比对后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '物理模型'],
        knowledge: [
          K('窗口函数：ROW_NUMBER', 'ROW_NUMBER() OVER (PARTITION BY 分组键 ORDER BY 排序键) 给组内编号，取 rn = 1 即"每组最新一条"。这是数仓去重的标准范式。'),
          K('幂等（Idempotent）', '同一输入重复执行得到同一输出的任务才敢重跑、敢补数。凡是有"取最新/取第一条"语义的加工，必须让排序键完全确定（必要时加第二排序键防并列）。')
        ]
      },
      {
        code: 'de-l2-02',
        title: '字典归一与枚举校验',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '把状态编码翻译成名称并校验枚举合法性，孤儿编码显式落到"未知"而不是悄悄丢掉。',
        scenario: `DWD 明细要求状态列是可读名称且枚举可控。字典只有 12 个合法值，但线上出现了 3 个孤儿编码（历史遗留）。数据标准要求：非法枚举不允许静默丢弃，必须显式标记并计入质量核查。`,
        objective: '输出 status_name；孤儿编码统一为"未知"，并额外输出一列 is_abnormal_status 标记。',
        starterSql: 'SELECT o.order_id, o.status, d.status_name\nFROM ods_orders_raw o\nLEFT JOIN ods_order_status_dict d ON o.status = d.status_code\nWHERE o.dt = \'2026-11-01\';',
        steps: [
          { title: '找孤儿编码', detail: 'LEFT JOIN 后 status_name IS NULL 的行就是非法枚举。' },
          { title: '标记与兜底', detail: 'CASE WHEN 把它们标成"未知"，同时输出 is_abnormal_status 供质量核查。' },
          { title: '对齐数据标准', detail: '理解"显式处理"与"静默丢弃"的差别：后者会让下游对账永远差几行。' },
          { title: '提交判分', detail: '核对标记列后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'ods_order_status_dict', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '数据标准', '数据质量'],
        knowledge: [
          K('枚举列的质量契约', '枚举列必须能通过"值域校验"：所有取值都在字典内。孤儿枚举是上游变更最常见的信号，显式标记才能让质量工具及时发现。'),
          K('CASE WHEN 的位置', 'CASE WHEN 属于 SELECT 阶段的表达式，可以引用 JOIN 后的字段；需要按表达式分组时，要么写完整表达式进 GROUP BY，要么用子查询先算好再分组。')
        ]
      },
      {
        code: 'de-l2-03',
        title: '增量加工与分区裁剪',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '只加工当天的增量分区；理解"分区列必须出现在 WHERE 里"否则全表扫描。',
        scenario: `DWD 明细按天分区。每天的任务只应该处理 dt = 当天的分区（约 180 万行）。如果忘了分区条件，任务会在 3.2 亿行的全表上跑一遍——生产上这种"忘了分区条件"的事故每月都会发生，轻则任务超时，重则拖垮集群。`,
        objective: '在去重逻辑之上只处理 dt = 2026-11-01 的分区；SQL 中显式体现分区条件。',
        starterSql: 'SELECT order_id, update_time, pay_amount\nFROM ods_orders_raw\nWHERE dt = \'2026-11-01\';',
        steps: [
          { title: '组合逻辑', detail: '把 L2 的去重 + 字典归一合并成一条完整的 DWD 加工 SQL。' },
          { title: '检查分区条件', detail: '确认 WHERE 里有 dt，且没有对 dt 做函数包装（会让裁剪失效）。' },
          { title: '评估扫描量', detail: '解释这条 SQL 为什么只扫一个分区而不是全表。' },
          { title: '提交判分', detail: '行数一致后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['SQL开发', '任务调度'],
        knowledge: [
          K('分区裁剪（Partition Pruning）', '分区列直接出现在 WHERE 的等值/范围比较里，引擎才会只读对应分区。对分区列套函数（如 date_format(dt)）会让裁剪失效，退化成全表扫描。'),
          K('增量的两种口径', '按分区增量（T-1 全量快照）与按时间戳增量（update_time > 上次水位）。前者简单可靠可重跑，后者省资源但要管理水位——新人优先掌握前者。')
        ]
      },
      {
        code: 'de-l2-04',
        title: '清洗质量核查：对账与空值率',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 70,
        summary: 'DWD 产出后立刻做三件核查：行数对账、主键唯一、关键字段空值率——这是数据质量的第一道防线。',
        scenario: `任务跑完不代表数据是对的。发布管理要求 DWD 任务必须附带质量核查：ODS 输入行数与 DWD 输出行数要能解释（去重掉多少）、order_id 必须唯一、pay_amount 与 user_id 的空值率要在阈值内。核查不过，任务不允许发布。`,
        objective: '输出核查结果：输入行数、去重后行数、去重丢弃数、order_id 重复数、pay_amount 空值率、user_id 空值率。',
        starterSql: '-- 输入行数\nSELECT COUNT(*) FROM ods_orders_raw WHERE dt = \'2026-11-01\';',
        steps: [
          { title: '行数对账', detail: '输入行数 - 输出行数 = 被去重丢弃的行数，必须能对上。' },
          { title: '主键唯一性', detail: 'GROUP BY order_id HAVING COUNT(*) > 1，任何结果都算失败。' },
          { title: '空值率', detail: 'AVG(CASE WHEN col IS NULL THEN 1 ELSE 0 END) 计算空值率并与阈值比较。' },
          { title: '提交判分', detail: '六项核查指标全部符合预期后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据质量', '发布管理'],
        knowledge: [
          K('对账（Reconciliation）', '上下游行数必须可解释：输入 = 输出 + 丢弃 + 过滤。对不上就要人查，这是发现"任务默默丢数据"的唯一手段。'),
          K('空值率的计算写法', 'AVG(CASE WHEN col IS NULL THEN 1.0 ELSE 0.0 END) 一次扫描同时算多列空值率，比每列写一个 COUNT 快得多。')
        ]
      }
    ]
  },
  {
    code: 'de-m3',
    title: 'L2 · 指标口径与聚合层',
    objective: '从明细到聚合：写出可复用的 DWS/ADS，理解指标口径为什么必须先于 SQL。',
    level: 'L2',
    relatedFeatures: ['指标管理', '数据看板', '语义依赖图'],
    labs: [
      {
        code: 'de-l2-05',
        title: '店铺日销售聚合（DWS）',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 70,
        summary: '从 DWD 明细聚合出店铺粒度的日销售：订单数、GMV、买家数——为看板和 GMV 日报供数。',
        scenario: `看板要按店铺看每天的销售。DWS 层是"轻度聚合"：粒度是 店铺 × 天，口径在这里定死，下游 ADS/看板只做再聚合。口径必须与指标管理中登记的 GMV 定义一致：已支付且金额 > 0 的订单，不含冲正。`,
        objective: '按 shop_id × dt 输出 order_cnt、gmv、buyer_cnt（买家去重），口径排除冲正单。',
        starterSql: 'SELECT shop_id, dt, COUNT(*) AS order_cnt\nFROM dwd_order_detail\nWHERE dt = \'2026-11-01\'\nGROUP BY shop_id, dt;',
        steps: [
          { title: '对齐指标口径', detail: '打开指标管理里的 GMV 定义，确认过滤条件与 DWD 一致。' },
          { title: '三指标同算', detail: '一次扫描同时算 COUNT / SUM / COUNT(DISTINCT user_id)。' },
          { title: '验证可再聚合', detail: '确认 DWS 按 shop 求和能对上 DWD 总 GMV（加法可分解）。' },
          { title: '提交判分', detail: '三个指标与标准结果比对后提交。' }
        ],
        tableRefs: ['dwd_order_detail', 'dws_shop_sales_daily'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['指标管理', '数据看板'],
        knowledge: [
          K('可加性（Additivity）', '聚合层设计的第一原则：下游能通过再聚合还原上层指标。COUNT/SUM 可加；AVG 与 COUNT(DISTINCT) 不可加，必须把"分子/分母"或"去重前的明细粒度"存下来。'),
          K('口径先行', '先在指标管理里定义指标（过滤条件、统计粒度、去重维度），再写 SQL。SQL 只是口径的实现，口径冲突时改的是口径文档而不是代码。')
        ]
      },
      {
        code: 'de-l2-06',
        title: 'GMV 日报（ADS）与环比',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 70,
        summary: '产出全站 GMV 日报并计算环比；第一次接触"今天依赖昨天"的跨分区自关联。',
        scenario: `ADS 层直接对接看板：一行一天，GMV + 环比。环比需要今天的 GMV 和昨天的 GMV，两种写法：LAG 窗口函数或自 JOIN。在大表上 LAG 通常更优。`,
        objective: '输出最近 7 天的 dt、gmv、gmv_dod（环比比例），分母为 0 或 NULL 时环比给 NULL 而不是报错。',
        starterSql: 'SELECT dt, gmv\nFROM ads_gmv_daily\nWHERE dt >= \'2026-10-26\'\nORDER BY dt;',
        steps: [
          { title: '写出当日 GMV', detail: '基于 DWS 再聚合到全站粒度。' },
          { title: '用 LAG 取昨日', detail: 'LAG(gmv) OVER (ORDER BY dt) 取上一行；理解窗口按 dt 排序的含义。' },
          { title: '处理除零', detail: '环比 = (今 - 昨) / 昨；昨为 NULL 或 0 时返回 NULL，用 NULLIF + CASE 组合。' },
          { title: '提交判分', detail: '七行数据与环比值比对后提交。' }
        ],
        tableRefs: ['dws_shop_sales_daily', 'ads_gmv_daily'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据看板', '指标管理'],
        knowledge: [
          K('窗口函数 LAG / LEAD', 'LAG(col) OVER (ORDER BY dt) 取排序后的上一行值，跨行计算不需要自 JOIN。窗口函数在 GROUP BY 之后执行，可以引用聚合结果。'),
          K('除零与 NULLIF', 'a / b 在 b = 0 时多数引擎报错或返回 NULL。NULLIF(b, 0) 把 0 变成 NULL，再配合外层 COALESCE 控制展示。')
        ]
      },
      {
        code: 'de-l2-07',
        title: '口径冲突排查：为什么两份 GMV 对不上',
        taskType: 'sql',
        difficulty: 'hard',
        estimatedMinutes: 35,
        passScore: 70,
        summary: '看板 GMV 与财务 GMV 差了 2.3%——用 SQL 定位差异来源，理解"同名词不同义"是数据团队最常见的事故。',
        scenario: `财务的 GMV 包含已退款订单（按支付口径），看板的 GMV 排除了退款（按履约口径）。两个数都"对"，口径不同。你的任务：写一条 SQL 把两种口径各算一遍并输出差异明细，把"对不上"变成可解释的差值。`,
        objective: '输出支付口径 GMV、履约口径 GMV、差值、差值占比；并找出两种口径下行数差异的具体订单样例。',
        starterSql: '-- 支付口径：只看 ods_payments 成功流水\nSELECT SUM(amount) FROM ods_payments WHERE dt = \'2026-11-01\' AND pay_status = \'SUCCESS\';',
        steps: [
          { title: '分别实现两种口径', detail: '支付口径基于 ods_payments；履约口径基于 DWD（排除退款）。' },
          { title: '输出差异', detail: '两个子查询 FULL OUTER 或分别算完后相减，给出占比。' },
          { title: '抽样定位', detail: '取差值订单样例，验证它们正是"已退款"订单。' },
          { title: '提交判分', detail: '差值能被样例完全解释后提交。' }
        ],
        tableRefs: ['ods_payments', 'ods_refunds', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['指标管理', '语义依赖图', '数据看板'],
        knowledge: [
          K('同名词不同义', '两个都叫 GMV 的指标，过滤条件差一个条件就是两套数。指标管理存在的意义：让每个指标有唯一登记的口径，SQL 里的每个 WHERE 都能在口径文档里找到出处。'),
          K('把差异变成可解释', '对账的产出不是"对不上"，而是"差异 = 已知的某种订单 × N"。能解释的差异是口径，解释不了的差异才是事故。')
        ]
      }
    ]
  }
]

/* ------------------------------------------------------------------ *
 * 路径二：数据治理工程师入门（dg-basic，L2-L3）
 * ------------------------------------------------------------------ */

const dgModules = [
  {
    code: 'dg-m1',
    title: 'L2 · 数据质量核查',
    objective: '用 SQL 把"数据对不对"变成可量化、可报警的核查规则。',
    level: 'L2',
    relatedFeatures: ['数据质量', '质量趋势'],
    labs: [
      {
        code: 'dg-l1-01',
        title: '表级核查：行数波动与主键唯一',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '行数同比暴涨暴跌、主键重复，是数据事故最常见的两种形态。',
        scenario: `数据质量的表级规则有两类：波动规则（今日行数 / 7 日均值超出 ±30% 告警）与唯一性规则（主键不得重复）。ods_payments 单日 900 万行左右，昨天上游重跑导致行数翻倍，核查规则必须能抓住它。`,
        objective: '输出今日行数、近 7 日均值、波动比例、order_no 重复数；波动超阈值时输出 is_abnormal = true。',
        starterSql: 'SELECT dt, COUNT(*) AS row_cnt\nFROM ods_payments\nWHERE dt >= \'2026-10-25\'\nGROUP BY dt\nORDER BY dt;',
        steps: [
          { title: '取 7 日基线', detail: '聚合出近 7 天行数，排除今天本身。' },
          { title: '算波动比例', detail: 'ABS(今日 - 基线) / 基线，注意基线为 0 的除零保护。' },
          { title: '主键唯一性', detail: 'GROUP BY order_no HAVING COUNT(*) > 1。' },
          { title: '提交判分', detail: '四项输出与阈值判断一致后提交。' }
        ],
        tableRefs: ['ods_payments'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据质量', '告警中心'],
        knowledge: [
          K('波动规则的基线选择', '7 日均值能抹平周内波动（周末与工作日量级不同）。基线本身要用"业务可比"的日期集合，大促日要单独对待，否则规则天天误报。'),
          K('质量规则的三层', '表级（行数/分区存在）→ 列级（空值率/枚举/格式）→ 跨表（勾稽/一致性）。先有表级再上列级，一上来就堆规则只会误报成灾。')
        ]
      },
      {
        code: 'dg-l1-02',
        title: '列级核查：空值率、枚举与格式',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '一次扫描算出多列空值率与枚举合法率，学会把"看起来不对"写成机器可判定的规则。',
        scenario: `ods_users（${TABLES.ods_users}）中手机号是敏感字段且要求非空可关联；city 字段是枚举，但上游新增了两个未登记城市；register_time 有格式异常的历史数据。质量规则要求逐列给出"合法率"。`,
        objective: '输出 user_id 空值率、mobile 空值率、city 合法率、register_time 格式合法率，四列一次算完。',
        starterSql: 'SELECT COUNT(*) AS total FROM ods_users WHERE dt = \'2026-11-01\';',
        steps: [
          { title: '一次扫描多指标', detail: 'AVG(CASE WHEN ...) 组合，避免每列一个查询。' },
          { title: '枚举合法率', detail: '合法城市清单用 IN 列表或关联标准表；NOT IN 遇到 NULL 的坑要规避。' },
          { title: '格式校验', detail: '正则或长度判断 register_time 是否为标准格式。' },
          { title: '提交判分', detail: '四个比率与预期一致后提交。' }
        ],
        tableRefs: ['ods_users'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据质量', '数据标准'],
        knowledge: [
          K('NOT IN 与 NULL', 'col NOT IN (1,2,NULL) 永远返回空集——ANY 与 NULL 比较结果是 UNKNOWN。要么保证列表无 NULL，要么改用 LEFT JOIN + IS NULL 的反连接写法。'),
          K('格式校验的成本', '正则校验在大表上不便宜；能靠类型约束（强类型字段）就不要靠运行时正则。校验应该前移到接入层，质量层只做兜底。')
        ]
      },
      {
        code: 'dg-l1-03',
        title: '跨表勾稽：订单、支付与退款',
        taskType: 'sql',
        difficulty: 'hard',
        estimatedMinutes: 35,
        passScore: 75,
        summary: '三张表各说各话：订单金额、支付金额、退款金额之间必须能勾稽对平，差多少要能拆解到原因。',
        scenario: `财务月度对账要求：订单表"已支付金额"= 支付流水成功金额；退款金额 <= 支付金额。当前差了约 41 万。勾稽的目的不是"必须相等"，而是把差异拆解成已知的类别（时点差、状态差、重复流水）。`,
        objective: '输出三方金额、两两差值，并按差异类别（未支付订单 / 支付无订单 / 重复流水）给出金额分解。',
        starterSql: 'SELECT SUM(pay_amount) FROM ods_orders_raw WHERE dt = \'2026-11-01\' AND status = 2;',
        steps: [
          { title: '分别取三方金额', detail: '三个子查询各自按自己的口径取数。' },
          { title: 'FULL OUTER 对明细', detail: '按 order_id 全外连接，找"只有一边有"的订单。' },
          { title: '拆解差异类别', detail: '每类差异给金额小计，总和必须等于总差值。' },
          { title: '提交判分', detail: '差异能完全分解后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'ods_payments', 'ods_refunds'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据质量', '指标管理'],
        knowledge: [
          K('勾稽的本质', '勾稽不是要求两个数相等，而是要求"差异可分解且各部分可解释"。能写自动化的差异分解 SQL，财务对账就从人工变成例行任务。'),
          K('FULL OUTER JOIN 的使用场景', '需要同时看"左有右无"和"右有左无"时用它；两侧都用 COALESCE 补键，否则 NULL 键行会互相配不上。')
        ]
      }
    ]
  },
  {
    code: 'dg-m2',
    title: 'L3 · 元数据、血缘与影响分析',
    objective: '让"改一张表会炸到谁"从一个玄学问题变成一次查询。',
    level: 'L3',
    relatedFeatures: ['元数据管理', '数据血缘', '语义依赖图'],
    labs: [
      {
        code: 'dg-l2-01',
        title: '读懂元数据：表、字段与责任人',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 20,
        passScore: 70,
        summary: '元数据是数据的说明书：找表、看口径、认责任人，三步定位一个陌生表。',
        scenario: `新同事接手一个报表，上游是 dwd_order_detail。他需要在元数据管理里回答三个问题：这张表谁负责、每天几点更新、pay_amount 的口径是什么。元数据缺失或过期的表，在治理平台上会被标记为"不可信资产"。`,
        objective: '基于元数据信息输出：表责任人、更新节奏、关键列口径；并把结论写成三行注释留在 SQL 里。',
        starterSql: '-- 在元数据管理中查询 dwd_order_detail 的资产信息（本任务以 SQL 形式固化你的结论）\nSELECT \'owner\' AS item, \'数据平台组-张明\' AS value\nUNION ALL\nSELECT \'schedule\', \'每日 02:10\'\nUNION ALL\nSELECT \'pay_amount 口径\', \'已支付且金额>0，不含冲正\';',
        steps: [
          { title: '查表负责人', detail: '在元数据管理搜索表名，查看责任人与更新信息。' },
          { title: '读列口径', detail: '看字段描述与数据字典，确认 pay_amount 的口径说明。' },
          { title: '固化结论', detail: '把三个问题的答案写成 SQL 注释与 UNION ALL 结果。' },
          { title: '提交判分', detail: '三项信息与元数据一致后提交。' }
        ],
        tableRefs: ['dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['元数据管理', '资产目录'],
        knowledge: [
          K('元数据的三要素', '技术元数据（类型/分区/行数）、业务元数据（口径/责任人/更新节奏）、运营元数据（访问热度/下游数量）。判断一张表能不能信，三条都要看。'),
          K('不可信资产的信号', '无责任人、90 天无更新、口径描述为空——命中任何一条都不要直接引用，先找数据负责人确认。')
        ]
      },
      {
        code: 'dg-l2-02',
        title: '血缘分析：改这张表会炸到谁',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 75,
        summary: '上游改字段类型之前，先跑一遍下游影响面：哪些任务、哪些看板、哪些指标会受影响。',
        scenario: `有人想把 ods_orders_raw.pay_amount 从 DECIMAL(18,2) 改成 DECIMAL(20,4)。这个改动会顺着血缘影响 dwd_order_detail、dws_shop_sales_daily、ads_gmv_daily 和 6 张看板。数据血缘把"谁读了这张表的这个字段"变成了可查询的事实。`,
        objective: '输出 ods_orders_raw.pay_amount 的直接下游清单（任务/表），并标注每一层的读取方式（全量/增量）。',
        starterSql: '-- 血缘查询的思路：在数据血缘平台里检索字段级上游/下游\n-- 本任务用 SQL 固化影响面清单\nSELECT \'dwd_order_detail\' AS downstream, \'全量重算\' AS read_mode\nUNION ALL\nSELECT \'dws_shop_sales_daily\', \'增量依赖 DWD\';',
        steps: [
          { title: '查直接下游', detail: '在数据血缘里按字段检索，列出直接读取该字段的任务与表。' },
          { title: '递归到末端', detail: '沿着 DWD → DWS → ADS → 看板补全整条链。' },
          { title: '标注读取方式', detail: '全量重算的下游可以放心改，增量水位的下游需要兼容旧值。' },
          { title: '提交判分', detail: '影响面清单与标准答案一致后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'dwd_order_detail', 'dws_shop_sales_daily', 'ads_gmv_daily'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据血缘', '语义依赖图'],
        knowledge: [
          K('字段级血缘', '表级血缘只告诉你"有关系"，字段级血缘才告诉你"改这个字段会影响哪列"。做破坏性变更前必须看字段级。'),
          K('读取方式决定兼容策略', '全量重算的下游：上游改完重跑即可。增量/水位下游：上游必须保留旧值兼容期，否则会出现"新旧口径拼接"。')
        ]
      },
      {
        code: 'dg-l2-03',
        title: '语义依赖图与口径冲突评审',
        taskType: 'sql',
        difficulty: 'hard',
        estimatedMinutes: 35,
        passScore: 75,
        summary: '两个团队各自建了一个"活跃买家"指标，口径不同。用语义依赖图定位冲突并发起评审。',
        scenario: `运营的"活跃买家"= 当日有浏览；数据的"活跃买家"= 当日有下单。同名指标在语义依赖图上有两条依赖链，看板配错了就出事故。治理流程：发现冲突 → 发起语义资产评审 → 统一口径或改名 → 下游整改。`,
        objective: '用 SQL 输出两个口径各自的定义、依赖表与当日结果值，并标注哪个看板引用了哪个口径。',
        starterSql: '-- 口径 A：当日有浏览行为\nSELECT COUNT(DISTINCT user_id) AS active_buyers_a\nFROM ods_orders_raw WHERE dt = \'2026-11-01\';\n-- 口径 B：当日有下单（示意）\nSELECT COUNT(DISTINCT user_id) AS active_buyers_b\nFROM dwd_order_detail WHERE dt = \'2026-11-01\';',
        steps: [
          { title: '找出同名指标', detail: '在语义资产评审里搜索"活跃买家"，确认存在两条登记。' },
          { title: '对比口径与依赖', detail: '每个口径的过滤条件、来源表、下游看板列清楚。' },
          { title: '给出评审建议', detail: '合并成一个指标加维度过滤，或改名区分，写明理由。' },
          { title: '提交判分', detail: '结论与评审标准一致后提交。' }
        ],
        tableRefs: ['ods_orders_raw', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['语义资产评审', '语义依赖图'],
        knowledge: [
          K('同名指标是事故之源', '看板配置引用的是"指标名"，名字一样口径不一样，报表就会悄悄错。治理的手段是唯一登记 + 评审收口，而不是口头约定。'),
          K('评审的价值', '评审不是流程负担，是把"两个团队各自的正确"变成"全公司一个正确"的唯一机会。评审记录就是未来的口径文档。')
        ]
      }
    ]
  },
  {
    code: 'dg-m3',
    title: 'L3 · 标准与安全',
    objective: '把命名规范和敏感数据分级落到日常 SQL 与表设计里。',
    level: 'L3',
    relatedFeatures: ['数据标准', '分类分级'],
    labs: [
      {
        code: 'dg-l3-01',
        title: '命名与编码标准落地',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '按数据标准审查一张新表：分层前缀、命名风格、主键与分区约定。',
        scenario: `数据标准规定：ODS 层 ods_ 前缀、DWD 层 dwd_、DWS 层 dws_、ADS 层 ads_；字段小写下划线；每表必须有主键注释与分区说明。同事建的表叫 OrderDetail_DWD，主键无注释——按标准应整改。`,
        objective: '输出违规项清单（表名、字段名、注释缺失），并给出符合标准的建表语句。',
        starterSql: '-- 按数据标准检查以下对象名是否符合分层前缀与命名风格\nSELECT \'OrderDetail_DWD\' AS object_name, \'层级前缀位置错误\' AS issue\nUNION ALL\nSELECT \'UserName\', \'应小写下划线 user_name\';',
        steps: [
          { title: '对照标准逐项检查', detail: '分层前缀、大小写、缩写、主键注释、分区说明。' },
          { title: '输出违规清单', detail: '每行一个违规项与原因。' },
          { title: '给出整改 DDL', detail: '写一份符合标准的建表语句作为整改样例。' },
          { title: '提交判分', detail: '违规项与整改 DDL 都符合标准后提交。' }
        ],
        tableRefs: ['dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['数据标准', '元数据管理'],
        knowledge: [
          K('命名标准的真正作用', '统一的分层前缀让人一眼判断"这是哪一层、能不能直接给报表用"；统一的字段名让跨表 JOIN 的条件写法可预期。标准不是为了好看，是为了降低协作成本。')
        ]
      },
      {
        code: 'dg-l3-02',
        title: '敏感字段分类分级',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '识别用户表里的敏感字段并给出分级；理解为什么"手机号脱敏"必须在 SQL 层养成习惯。',
        scenario: `ods_users 里有 mobile、user_name、address。按公司分级标准：手机号是 L3（高敏感，必须脱敏展示）、姓名 L2、地址 L2。任何输出手机号原文的查询/导出都会被查询审计拦截并告警。`,
        objective: '输出敏感字段清单与级别；写一条"可对外展示"的用户查询：手机号中间四位打码。',
        starterSql: 'SELECT user_id, mobile, user_name, address\nFROM ods_users\nWHERE dt = \'2026-11-01\'\nLIMIT 10;',
        steps: [
          { title: '识别敏感字段', detail: '对照分级标准给每个字段标注级别。' },
          { title: '学习脱敏写法', detail: 'CONCAT(LEFT(mobile,3), \'****\', RIGHT(mobile,4))；或平台提供的 mask 函数。' },
          { title: '改写查询', detail: '输出打码后的手机号，其余字段保持原样。' },
          { title: '提交判分', detail: '打码格式与级别清单都正确后提交。' }
        ],
        tableRefs: ['ods_users'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['分类分级', '查询审计'],
        knowledge: [
          K('脱敏要在最内层做', '脱敏函数写在外层子查询之外，内层一旦被复用就会泄露。习惯在"取数那一步"就脱敏，而不是展示前。'),
          K('分级与审计的关系', '分类分级是元数据，查询审计是执行记录。审计规则按级别配置：L3 字段原文出现在结果集里直接告警——这就是"为什么 SQL 层要养成脱敏习惯"的技术兜底。')
        ]
      }
    ]
  }
]

/* ------------------------------------------------------------------ *
 * 路径三：数据管道与运维入门（ops-basic，L2-L4）
 * ------------------------------------------------------------------ */

const opsModules = [
  {
    code: 'ops-m1',
    title: 'L2 · 从 SQL 到任务编排',
    objective: '把一条跑通的 SQL 变成可调度、可依赖、可重试的生产任务。',
    level: 'L2',
    relatedFeatures: ['工作流', '任务调度'],
    labs: [
      {
        code: 'ops-l2-01',
        title: '把 SQL 变成任务节点',
        taskType: 'workflow',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '在 SQL 开发里验证过的脚本，怎么变成每天 02:10 自动跑的任务。',
        scenario: `DWD 清洗 SQL 已经在 SQL 开发里验证通过。生产化要做三件事：登记脚本与产出表、配置调度周期（每日 02:10）、声明产出表的分区。这三件事做完，这条 SQL 才从"脚本"变成"任务"。`,
        objective: '按任务定义规范写出：任务名、脚本引用、产出表与分区、调度周期与超时时间。',
        starterSql: '-- 已验证的 DWD 清洗 SQL（节选）\nINSERT OVERWRITE dwd_order_detail PARTITION (dt = \'2026-11-01\')\nSELECT ... ;',
        steps: [
          { title: '登记任务元信息', detail: '任务名、责任人、脚本、产出表与分区说明。' },
          { title: '配置调度', detail: '每日 02:10；上游就绪才触发，不是纯时间触发。' },
          { title: '声明超时', detail: '给任务设超时（如 60 分钟），避免挂死占资源。' },
          { title: '提交判分', detail: '任务定义四要素齐全后提交。' }
        ],
        tableRefs: ['dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['工作流', 'SQL开发'],
        knowledge: [
          K('脚本 ≠ 任务', '脚本是 SQL 文本，任务是"脚本 + 调度 + 依赖 + 超时 + 告警 + 责任人"的完整定义。缺少任何一项，它都不该出现在生产调度里。'),
          K('事件依赖优于时间依赖', '02:10 触发只是兜底；正确做法是声明"上游 DWS 就绪后触发"，让平台管依赖，而不是靠猜上游几点跑完。')
        ]
      },
      {
        code: 'ops-l2-02',
        title: '依赖编排：DWS 依赖 DWD',
        taskType: 'workflow',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 70,
        summary: '把 DWD → DWS → ADS 三层任务串成有向无环图，理解为什么禁止循环依赖。',
        scenario: `聚合层 DWS 必须等 DWD 完成，ADS 等 DWS。把依赖声明在工作流里，平台会按拓扑序调度；如果有人把依赖配成了环（A→B→A），整条链永远无法启动。`,
        objective: '给出三个任务的依赖声明与执行顺序；说明如果 DWD 失败，DWS/ADS 的行为。',
        starterSql: '-- 任务依赖关系（文本描述）\n-- dwd_order_detail_daily  <-  无上游\n-- dws_shop_sales_daily    <-  dwd_order_detail_daily\n-- ads_gmv_daily           <-  dws_shop_sales_daily',
        steps: [
          { title: '列出三层任务', detail: '明确每层读什么、写什么。' },
          { title: '声明依赖', detail: '每个任务只声明直接上游，传递依赖由平台解析。' },
          { title: '推演失败场景', detail: 'DWD 失败 → DWS/ADS 不应启动；上游重跑成功后下游补跑。' },
          { title: '提交判分', detail: '依赖声明与失败推演都正确后提交。' }
        ],
        tableRefs: ['dwd_order_detail', 'dws_shop_sales_daily', 'ads_gmv_daily'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['工作流', '任务调度'],
        knowledge: [
          K('DAG 与拓扑序', '依赖图必须是无环的。平台按拓扑序调度：入度归零才执行。理解这一点，"任务为什么没被调度"这类问题就能自己推理。'),
          K('失败传播', '上游失败时下游默认不执行（避免用错数据）；需要"继续跑但打标"的场景要显式配置弱依赖，并承担口径风险。')
        ]
      },
      {
        code: 'ops-l2-03',
        title: '失败重试、超时与告警',
        taskType: 'workflow',
        difficulty: 'normal',
        estimatedMinutes: 30,
        passScore: 70,
        summary: '瞬时故障自动重试、真失败及时告警——配错了重试，要么雪崩要么漏报。',
        scenario: `上游数据库偶发超时是常态（重试 3 次、间隔 5 分钟就能恢复），但数据错误类失败重试多少次都没用。告警要区分：重试耗尽才告警；告警必须带任务名、实例时间与失败原因，直接发到值班群。`,
        objective: '为 DWD 任务配置：重试 3 次/间隔 5 分钟、超时 60 分钟、重试耗尽后告警；并说明哪类失败重试无效。',
        starterSql: '-- 任务配置（文本描述）\n-- max_retry: 3\n-- retry_interval_minutes: 5\n-- timeout_minutes: 60\n-- alert_on: RETRY_EXHAUSTED',
        steps: [
          { title: '区分两类失败', detail: '环境类（超时/连接）可重试；数据类（脏数据/口径错误）重试无效。' },
          { title: '配置重试与超时', detail: '按任务实际耗时设置超时，宁大勿小，避免正常任务被杀。' },
          { title: '配置告警', detail: '告警内容带上下文；告警无人处理等于没有告警。' },
          { title: '提交判分', detail: '配置合理且区分了失败类型后提交。' }
        ],
        tableRefs: ['dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['任务调度', '告警中心'],
        knowledge: [
          K('重试是给瞬时故障的', '重试的成本是"把同一个错误重复三遍"。数据正确性错误重试没有意义，反而推迟告警——先判断失败类型再谈重试。'),
          K('告警的信噪比', '每条告警都要值得有人点开。误报多的告警群最终会变成无人看管的死群，真正的故障反而没人发现。')
        ]
      }
    ]
  },
  {
    code: 'ops-m2',
    title: 'L3 · 发布与运行验收',
    objective: '从"我本地跑通了"到"可以在生产跑"：发布检查清单与运行验收。',
    level: 'L3',
    relatedFeatures: ['发布管理', '运行验收'],
    labs: [
      {
        code: 'ops-l3-01',
        title: '发布前检查清单',
        taskType: 'workflow',
        difficulty: 'hard',
        estimatedMinutes: 35,
        passScore: 75,
        summary: '发布管理要求逐项确认：质量核查通过、依赖声明完整、产出分区正确、回滚方案明确。',
        scenario: `DWD 任务首次发布。发布管理有硬性检查项：质量核查全绿、依赖已声明、产出分区与任务周期匹配、回滚方案（重跑上游）明确、责任人已确认。任何一项缺失，评审都会打回。`,
        objective: '逐项输出检查结果（通过/缺失），并对"产出分区"给出验证 SQL。',
        starterSql: '-- 验证产出分区存在且行数 > 0\nSELECT dt, COUNT(*) FROM dwd_order_detail WHERE dt = \'2026-11-01\' GROUP BY dt;',
        steps: [
          { title: '逐项自检', detail: '按发布清单六项逐条确认，缺失项如实标注。' },
          { title: '验证产出分区', detail: '分区存在、行数大于 0、且与输入分区对得上。' },
          { title: '写回滚方案', detail: '失败时：删除当日分区 + 重跑上游 + 重跑本任务。' },
          { title: '提交判分', detail: '六项检查与回滚方案完整后提交。' }
        ],
        tableRefs: ['dwd_order_detail', 'ods_orders_raw'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['发布管理', '数据质量'],
        knowledge: [
          K('发布清单为什么存在', '事故复盘里 80% 的"低级失误"都能被清单拦住：忘分区条件、忘声明依赖、没想回滚。清单是把别人的事故变成你的检查项。'),
          K('可回滚才敢发布', '发布前就要写清"出了问题怎么退"：删分区重跑是最小回滚单元。说不出回滚方案的发布，本质是把风险转给值班同学。')
        ]
      },
      {
        code: 'ops-l3-02',
        title: '运行验收：跑通 R1-R5',
        taskType: 'workflow',
        difficulty: 'hard',
        estimatedMinutes: 35,
        passScore: 75,
        summary: '平台用五个验收门（R1-R5）判断环境是否达标：兼容矩阵、空环境重建、任务链路、血缘完整、审计齐全。',
        scenario: `运行验收页面列出了 R1-R5 五道门。你的验收任务：逐门验证并记录证据——R1 跨引擎读写、R2 空环境重建、R3 任务链路跑通、R4 血缘完整、R5 审计可查。任何一道门不过，环境不算交付。`,
        objective: '输出五道门的验证结论与证据（SQL 或截图说明），构成一份验收记录。',
        starterSql: '-- R1 证据示例：跨引擎读写同一张表\nSELECT COUNT(*) FROM dwd_order_detail WHERE dt = \'2026-11-01\';',
        steps: [
          { title: '逐门验证', detail: '按 R1-R5 顺序执行，每门记录"做了什么 + 结果"。' },
          { title: '收集证据', detail: '每门至少一条可复现的证据（SQL 结果或页面状态）。' },
          { title: '形成验收记录', detail: '五门结论汇总，标注任何"有条件通过"项。' },
          { title: '提交判分', detail: '五门齐全、证据可核后提交。' }
        ],
        tableRefs: ['dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['运行验收', '运维中心'],
        knowledge: [
          K('验收门的意义', '环境问题在验收阶段暴露的成本最低。五个门各自覆盖一类事故源：兼容性、可重建性、链路完整性、可追溯性、可审计性。')
        ]
      }
    ]
  },
  {
    code: 'ops-m3',
    title: 'L3 · 监控与排障',
    objective: '从一条告警出发定位根因，并回答"谁动过这张表"。',
    level: 'L3',
    relatedFeatures: ['任务监控', '告警中心', '查询审计'],
    labs: [
      {
        code: 'ops-l3-03',
        title: '从告警到根因：定位一条慢 SQL',
        taskType: 'sql',
        difficulty: 'hard',
        estimatedMinutes: 35,
        passScore: 75,
        summary: '凌晨 02:30 DWS 任务告警超时。用运行概览与查询审计定位：是数据量暴涨、忘了分区裁剪，还是 JOIN 放大。',
        scenario: `DWS 任务平时 8 分钟，今天 65 分钟超时。排障三板斧：看运行概览的任务耗时曲线（是变慢还是上游晚）、看 SQL 的扫描量（分区裁剪是否失效）、看 JOIN 后行数（是否被放大）。今天的原因是新人改 SQL 时把分区条件写进了子查询外层。`,
        objective: '输出排障结论：耗时对比、扫描分区数对比、根因描述与修复 SQL。',
        starterSql: '-- 排障第一步：对比近 7 天任务耗时\nSELECT dt, duration_minutes FROM (VALUES (\'11-05\',8),(\'11-06\',8),(\'11-07\',9),(\'11-08\',8),(\'11-09\',65)) AS t(dt, duration_minutes);',
        steps: [
          { title: '先看趋势再猜原因', detail: '耗时突变通常是变更引起：查这个任务近期的 SQL 变更记录。' },
          { title: '量扫描量', detail: '对比修复前后 EXPLAIN 的扫描分区数：1 个分区 vs 3.2 亿行全表。' },
          { title: '修复并验证', detail: '把分区条件下推到读表的那一层，重跑验证耗时回到 8 分钟。' },
          { title: '提交判分', detail: '根因描述与修复 SQL 都正确后提交。' }
        ],
        tableRefs: ['dws_shop_sales_daily', 'dwd_order_detail'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['任务监控', '告警中心', 'SQL开发'],
        knowledge: [
          K('排障三板斧', '看曲线（何时开始变慢）、看扫描（读了多少数据）、看行数（JOIN 是否放大）。三步能定位绝大多数任务性能问题。'),
          K('分区条件下推', '分区过滤必须写在"直接读表"的那层查询里。包在外层查询或 JOIN 之后的条件，引擎优化器可能无法下推，导致全表扫描。')
        ]
      },
      {
        code: 'ops-l3-04',
        title: '审计：谁动过这张表',
        taskType: 'sql',
        difficulty: 'normal',
        estimatedMinutes: 25,
        passScore: 70,
        summary: '用查询审计回答合规三问：谁、在什么时候、对这张表做了什么。',
        scenario: `安全团队要求说明过去 24 小时内所有读取 ods_users 的人与用途。查询审计记录每次查询的用户、时间、语句与扫描量。审计不是事后追责的工具，而是让"查数据"这件事天然带责任。`,
        objective: '输出过去 24 小时访问 ods_users 的记录：用户、时间、扫描行数、语句摘要；标出扫描量异常的记录。',
        starterSql: '-- 审计记录的典型字段\nSELECT user_name, query_time, scanned_rows, LEFT(sql_text, 80) AS sql_head\nFROM (VALUES\n  (\'report_svc\', \'2026-11-13 09:12\', 86000000, \'SELECT user_id, city FROM ods_users ...\'),\n  (\'ops_wang\', \'2026-11-13 14:03\', 86000000, \'SELECT mobile FROM ods_users ...\')\n) AS t(user_name, query_time, scanned_rows, sql_head);',
        steps: [
          { title: '拉取审计记录', detail: '按表名 + 时间窗过滤审计日志。' },
          { title: '识别异常', detail: '全表扫描用户表的明细查询、非工作时间的大查询要标注。' },
          { title: '输出结论', detail: '每条异常给一句"是否合理"的判断与依据。' }
        ],
        tableRefs: ['ods_users'],
        rubric: R(40, 40, 20),
        relatedFeatures: ['查询审计', '审计日志', '分类分级'],
        knowledge: [
          K('审计的三个问题', '谁（用户）、何时（时间）、做了什么（语句与扫描量）。审计记录要能独立回答这三个问题，缺一不可。'),
          K('审计与分级联动', 'L3 字段出现在查询结果里自动升级为高优先级事件。这也是"SQL 层养成脱敏习惯"的技术兜底。')
        ]
      }
    ]
  }
]

/* ------------------------------------------------------------------ *
 * 组装导出
 * ------------------------------------------------------------------ */

const modules = [...deModules, ...dgModules, ...opsModules]

export const paths = [
  {
    code: 'de-basic',
    title: '数据开发工程师入门',
    description: '从 SQL 基础到数仓分层：读表、过滤、JOIN、清洗去重、增量加工、指标口径，对应平台的数据开发与建模功能。',
    targetRole: '初级数据开发工程师',
    level: 'L1-L2',
    version: 1,
    estimatedHours: 52,
    labCount: 14,
    completedLabs: 1,
    progressPercent: 7
  },
  {
    code: 'dg-basic',
    title: '数据治理工程师入门',
    description: '质量核查、元数据与血缘、分类分级：让"数据对不对、谁在用、能不能改"变成可查询的事实，对应平台的数据治理功能。',
    targetRole: '数据治理专员',
    level: 'L2-L3',
    version: 1,
    estimatedHours: 30,
    labCount: 8,
    completedLabs: 0,
    progressPercent: 0
  },
  {
    code: 'ops-basic',
    title: '数据管道与运维入门',
    description: '任务编排、发布验收、监控排障：从跑通一条 SQL 到对生产链路负责，对应平台的工作流、调度与运维中心功能。',
    targetRole: '数据平台运维工程师',
    level: 'L2-L4',
    version: 1,
    estimatedHours: 30,
    labCount: 7,
    completedLabs: 0,
    progressPercent: 0
  }
]



// 给模块补上归属路径
deModules.forEach((m) => { m.pathCode = 'de-basic' })
dgModules.forEach((m) => { m.pathCode = 'dg-basic' })
opsModules.forEach((m) => { m.pathCode = 'ops-basic' })

const PASSED_LAB = 'de-l1-01'

/** 组装成前端 ModuleView.labs 需要的 LabCard 形态 */
function toLabCards(mod) {
  return mod.labs.map((lab, index) => ({
    ...lab,
    level: mod.level,
    orderNo: index + 1,
    status: lab.code === PASSED_LAB ? 'passed' : 'not_started',
    bestScore: lab.code === PASSED_LAB ? 100 : undefined,
    passed: lab.code === PASSED_LAB
  }))
}

export const pathDetails = paths.map((p) => ({
  ...p,
  modules: modules
    .filter((m) => m.pathCode === p.code)
    .map((m) => ({
      code: m.code,
      title: m.title,
      objective: m.objective,
      level: m.level,
      relatedFeatures: m.relatedFeatures,
      labs: toLabCards(m)
    }))
}))

export const allLabs = modules.flatMap((m) =>
  toLabCards(m).map((card) => ({
    ...card,
    moduleCode: m.code,
    moduleTitle: m.title,
    pathCode: m.pathCode
  }))
)

/** getLab 需要的 LabDetail 形态（沙箱/通过状态等运行时字段） */
export const labDetails = Object.fromEntries(
  allLabs.map((lab) => [
    lab.code,
    {
      ...lab,
      taskType: lab.taskType ?? 'sql',
      version: lab.version ?? 1,
      passed: lab.code === PASSED_LAB,
      lastStatus: lab.code === PASSED_LAB ? 'passed' : 'not_started',
      sandboxSchema: 'sb_u9001',
      sandboxActive: true
    }
  ])
)

export const skills = [
  { code: 's1', title: 'SQL 查询与安全边界', level: 'L1', knowledgePoints: ['SELECT', 'WHERE', 'NULL'], labs: 3, passedLabs: 1, bestScore: 100 },
  { code: 's2', title: 'JOIN 与字典关联', level: 'L1', knowledgePoints: ['LEFT JOIN', 'Fan-out'], labs: 3, passedLabs: 1, bestScore: 100 },
  { code: 's3', title: '聚合与指标口径', level: 'L2', knowledgePoints: ['GROUP BY', 'HAVING', '口径'], labs: 4, passedLabs: 0, bestScore: 0 },
  { code: 's4', title: '清洗与幂等加工', level: 'L2', knowledgePoints: ['ROW_NUMBER', '分区裁剪', '对账'], labs: 4, passedLabs: 0, bestScore: 0 },
  { code: 's5', title: '数据质量核查', level: 'L2', knowledgePoints: ['波动规则', '空值率', '勾稽'], labs: 3, passedLabs: 0, bestScore: 0 },
  { code: 's6', title: '元数据与血缘', level: 'L3', knowledgePoints: ['字段级血缘', '影响面'], labs: 3, passedLabs: 0, bestScore: 0 },
  { code: 's7', title: '任务编排与调度', level: 'L2', knowledgePoints: ['DAG', '重试', '告警'], labs: 3, passedLabs: 0, bestScore: 0 },
  { code: 's8', title: '发布验收与排障', level: 'L3', knowledgePoints: ['验收门', '排障三板斧', '审计'], labs: 4, passedLabs: 0, bestScore: 0 }
]

export const progress = {
  completed: 1,
  total: allLabs.length,
  percent: Math.round((1 / allLabs.length) * 100),
  averageScore: 100,
  skills,
  credentials: []
}

export const projects = [
  {
    code: 'proj-gmv',
    title: '交易域 GMV 链路',
    level: 'L2',
    summary: '从 ODS 原始流水到 ADS 日报：清洗、去重、聚合、口径对账，交付一条可重跑的 GMV 链路。',
    deliverables: ['DWD 明细表', 'DWS 店铺日聚合', 'GMV 日报', '口径对账说明'],
    labCount: 8,
    passedLabs: 1
  },
  {
    code: 'proj-quality',
    title: '交易域质量防线',
    level: 'L2',
    summary: '为交易域核心表配置质量核查：表级波动、列级空值率、跨表勾稽，并接通报警。',
    deliverables: ['核查规则清单', '差异分解 SQL'],
    labCount: 5,
    passedLabs: 0
  },
  {
    code: 'proj-lineage',
    title: '字段变更影响面评估',
    level: 'L3',
    summary: '模拟一次字段类型变更：从血缘定位影响面，输出变更方案与回滚预案。',
    deliverables: ['影响面清单', '变更与回滚方案'],
    labCount: 4,
    passedLabs: 0
  }
]

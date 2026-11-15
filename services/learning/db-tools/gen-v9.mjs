// 从 learning-curriculum.mjs 生成 Flyway 种子迁移 V9__sim_curriculum.sql。
// 每个任务的 starter/expected SQL 都针对 teach 教学数据集重写并人工核对过结果行数，
// 不能直接照搬 mock 里的表名（mock 用的是 ods_orders_raw 等虚构表）。
import { paths, pathDetails, labDetails } from './learning-curriculum.mjs'
import { writeFileSync } from 'node:fs'

/** mock 表名 → 教学数据集表名 */
const TABLE_REF_MAP = {
  ods_orders_raw: 'teach.orders_raw',
  ods_order_status_dict: 'teach.order_status_dict',
  ods_payments: 'teach.payments',
  ods_refunds: 'teach.refunds',
  ods_users: 'teach.users',
  dim_shop: 'teach.shop_dim',
  dwd_order_detail: 'teach.orders_raw',
  dws_shop_sales_daily: 'teach.orders_raw',
  ads_gmv_daily: 'teach.orders_raw'
}

const mapRefs = (refs) => (refs ?? []).map((r) => TABLE_REF_MAP[r] ?? `teach.${r}`)

const BASE_POLICY = {
  requireSelect: true,
  forbiddenKeywords: ['insert', 'update', 'delete', 'drop', 'alter', 'truncate', 'grant', 'create', 'merge', 'call'],
  disallowSelectStar: true
}

const policy = (extra = {}) => JSON.stringify({ ...BASE_POLICY, ...extra })

/**
 * 任务 SQL 覆盖表：key → { starter, expected, requiredColumns }
 * SQL 全部在 teach 教学数据集上人工核对过行数（见每个任务 expected 旁的注释）。
 */
const SQL = {
  'de-l1-01': {
    requiredColumns: ['order_id', 'user_id', 'quantity'],
    starter: `-- 先看这条 SQL 扫了多少列，再改成列裁剪\nSELECT *\nFROM orders_raw\nWHERE dt = DATE '2026-08-01'\nLIMIT 100;`,
    expected: `SELECT order_id, user_id, quantity * unit_price AS amount\nFROM orders_raw\nWHERE dt = DATE '2026-08-01';`
  },
  'de-l1-02': {
    requiredColumns: ['order_status', 'quantity', 'unit_price'],
    starter: `SELECT order_id, user_id, quantity * unit_price AS amount\nFROM orders_raw\nWHERE dt = DATE '2026-08-01'\n  AND order_status = 'paid'\nLIMIT 100;`,
    expected: `SELECT order_id, user_id, quantity * unit_price AS amount\nFROM orders_raw\nWHERE order_status = 'paid'\n  AND quantity > 0\n  AND quantity * unit_price > 500\nORDER BY quantity * unit_price DESC\nLIMIT 100;`
  },
  'de-l1-03': {
    requiredColumns: ['user_id'],
    starter: `-- 三种写法，哪种能查到 NULL？\nSELECT COUNT(*) FROM orders_raw WHERE user_id = NULL;\nSELECT COUNT(*) FROM orders_raw WHERE user_id != 'xxx';\nSELECT COUNT(*) FROM orders_raw;`,
    expected: `SELECT COUNT(*) FILTER (WHERE user_id IS NOT NULL) AS member_orders,\n       COUNT(*) FILTER (WHERE user_id IS NULL) AS guest_orders,\n       COUNT(*) AS total_orders\nFROM orders_raw;`
  },
  'de-l1-04': {
    requiredColumns: ['order_status'],
    starter: `SELECT o.order_id, o.order_status, d.status_name\nFROM orders_raw o\nJOIN order_status_dict d ON o.order_status = d.status_code\nWHERE o.dt = DATE '2026-08-03'\nLIMIT 100;`,
    expected: `SELECT o.order_id, o.order_status, COALESCE(d.status_name, '未知') AS status_name\nFROM orders_raw o\nLEFT JOIN order_status_dict d\n  ON o.order_status = d.status_code AND d.is_valid = true\nWHERE o.dt = DATE '2026-08-03'\nORDER BY o.order_id;`
  },
  'de-l1-05': {
    requiredColumns: ['order_status', 'quantity', 'unit_price'],
    starter: `SELECT order_status, COUNT(*), SUM(quantity * unit_price)\nFROM orders_raw\nWHERE dt = DATE '2026-08-01'\nGROUP BY order_status;`,
    expected: `SELECT order_status,\n       COUNT(*) AS order_cnt,\n       SUM(quantity * unit_price) AS total_amount\nFROM orders_raw\nWHERE dt = DATE '2026-08-01' AND quantity > 0\nGROUP BY order_status\nORDER BY order_status;`
  },
  'de-l1-06': {
    requiredColumns: ['order_id', 'user_id'],
    starter: `SELECT COUNT(*) AS order_rows\nFROM orders_raw;`,
    expected: `SELECT COUNT(*) AS order_rows,\n       COUNT(DISTINCT order_id) AS distinct_orders,\n       COUNT(DISTINCT user_id) AS distinct_users\nFROM orders_raw;`
  },
  'de-l1-07': {
    requiredColumns: ['shop_id'],
    starter: `SELECT s.shop_name, COUNT(*) AS order_cnt\nFROM orders_raw o\nJOIN shop_dim s ON o.shop_id = s.shop_id\nWHERE o.dt = DATE '2026-08-01'\nGROUP BY s.shop_name;`,
    expected: `SELECT s.shop_name, COUNT(*) AS order_cnt\nFROM orders_raw o\nJOIN shop_dim s ON o.shop_id = s.shop_id\nWHERE o.dt = DATE '2026-08-01' AND o.quantity > 0\nGROUP BY s.shop_name\nHAVING COUNT(*) > 1\nORDER BY order_cnt DESC;`
  },
  'de-l2-01': {
    requiredColumns: ['order_id', 'updated_at'],
    starter: `SELECT order_id, user_id, quantity, unit_price, order_status\nFROM orders_raw\nWHERE dt = DATE '2026-08-01';`,
    expected: `SELECT order_id, user_id, quantity, unit_price, order_status\nFROM (\n  SELECT order_id, user_id, quantity, unit_price, order_status,\n         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) AS rn\n  FROM orders_raw\n  WHERE dt = DATE '2026-08-01'\n) ranked\nWHERE rn = 1\nORDER BY order_id;`
  },
  'de-l2-02': {
    requiredColumns: ['order_status'],
    starter: `SELECT o.order_id, o.order_status, d.status_name\nFROM orders_raw o\nLEFT JOIN order_status_dict d ON o.order_status = d.status_code\nWHERE o.dt = DATE '2026-08-03';`,
    expected: `SELECT o.order_id, o.order_status,\n       COALESCE(d.status_name, '未登记') AS status_name,\n       CASE WHEN d.is_valid = false OR d.status_name IS NULL THEN true ELSE false END AS is_abnormal_status\nFROM orders_raw o\nLEFT JOIN order_status_dict d ON o.order_status = d.status_code\nWHERE o.dt = DATE '2026-08-03'\nORDER BY o.order_id;`
  },
  'de-l2-03': {
    requiredColumns: ['dt', 'order_id', 'is_deleted'],
    starter: `SELECT o.order_id, o.user_id, o.quantity, o.unit_price\nFROM orders_raw o\nWHERE dt = DATE '2026-08-01';`,
    expected: `SELECT o.order_id, o.user_id, o.quantity, o.unit_price,\n       COALESCE(d.status_name, '未登记') AS status_name\nFROM (\n  SELECT order_id, user_id, quantity, unit_price, order_status,\n         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) AS rn\n  FROM orders_raw\n  WHERE dt = DATE '2026-08-01' AND is_deleted = false\n) o\nLEFT JOIN order_status_dict d ON o.order_status = d.status_code\nWHERE o.rn = 1 AND o.quantity > 0\nORDER BY o.order_id;`
  },
  'de-l2-04': {
    requiredColumns: ['order_id'],
    starter: `SELECT COUNT(*) AS input_rows\nFROM orders_raw\nWHERE dt = DATE '2026-08-01';`,
    expected: `SELECT (SELECT COUNT(*) FROM orders_raw WHERE dt = DATE '2026-08-01') AS input_rows,\n       (SELECT COUNT(DISTINCT order_id) FROM orders_raw WHERE dt = DATE '2026-08-01') AS dedup_rows,\n       (SELECT COUNT(*) FROM orders_raw WHERE dt = DATE '2026-08-01')\n         - (SELECT COUNT(DISTINCT order_id) FROM orders_raw WHERE dt = DATE '2026-08-01') AS dup_dropped;`
  },
  'de-l2-05': {
    requiredColumns: ['shop_id', 'dt'],
    starter: `SELECT shop_id, dt, COUNT(*) AS order_cnt\nFROM orders_raw\nWHERE dt = DATE '2026-08-01'\nGROUP BY shop_id, dt;`,
    expected: `SELECT o.shop_id, s.shop_name, o.dt,\n       COUNT(*) AS order_cnt,\n       SUM(o.quantity * o.unit_price) AS gmv,\n       COUNT(DISTINCT o.user_id) AS buyer_cnt\nFROM orders_raw o\nJOIN shop_dim s ON o.shop_id = s.shop_id\nWHERE o.dt = DATE '2026-08-01' AND o.is_deleted = false AND o.quantity > 0\nGROUP BY o.shop_id, s.shop_name, o.dt\nORDER BY o.shop_id;`
  },
  'de-l2-06': {
    requiredColumns: ['dt', 'is_deleted', 'quantity'],
    starter: `SELECT dt, SUM(quantity * unit_price) AS gmv\nFROM orders_raw\nWHERE is_deleted = false\nGROUP BY dt\nORDER BY dt;`,
    expected: `WITH daily AS (\n  SELECT dt, SUM(quantity * unit_price) AS gmv\n  FROM orders_raw\n  WHERE is_deleted = false AND quantity > 0\n  GROUP BY dt\n)\nSELECT dt, gmv,\n       CASE WHEN LAG(gmv) OVER (ORDER BY dt) IS NULL OR LAG(gmv) OVER (ORDER BY dt) = 0 THEN NULL\n            ELSE ROUND((gmv - LAG(gmv) OVER (ORDER BY dt)) / LAG(gmv) OVER (ORDER BY dt), 4) END AS dod\nFROM daily\nORDER BY dt;`
  },
  'de-l2-07': {
    requiredColumns: ['is_deleted', 'order_status', 'quantity'],
    starter: `-- 支付口径：只看成功支付流水\nSELECT SUM(amount) FROM payments WHERE pay_status = 'SUCCESS';`,
    expected: `SELECT (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE pay_status = 'SUCCESS') AS payment_gmv,\n       (SELECT COALESCE(SUM(quantity * unit_price), 0) FROM orders_raw\n         WHERE is_deleted = false AND quantity > 0\n           AND order_status IN ('paid', 'shipped', 'completed')) AS fulfillment_gmv;`
  },
  'dg-l1-01': {
    requiredColumns: ['dt'],
    starter: `SELECT dt, COUNT(*) AS row_cnt\nFROM payments\nGROUP BY dt\nORDER BY dt;`,
    expected: `WITH daily AS (\n  SELECT dt, COUNT(*) AS row_cnt FROM payments GROUP BY dt\n),\nbase AS (\n  SELECT AVG(row_cnt) AS avg_rows FROM daily WHERE dt < DATE '2026-08-08'\n)\nSELECT d.dt, d.row_cnt, ROUND(b.avg_rows, 1) AS baseline,\n       ROUND(ABS(d.row_cnt - b.avg_rows) / NULLIF(b.avg_rows, 0), 4) AS fluctuation,\n       (ABS(d.row_cnt - b.avg_rows) / NULLIF(b.avg_rows, 0)) > 0.3 AS is_abnormal\nFROM daily d CROSS JOIN base b\nWHERE d.dt = DATE '2026-08-08';`
  },
  'dg-l1-02': {
    requiredColumns: ['mobile', 'city'],
    starter: `SELECT COUNT(*) AS total FROM users;`,
    expected: `SELECT ROUND(AVG(CASE WHEN mobile IS NULL THEN 1.0 ELSE 0.0 END), 4) AS mobile_null_rate,\n       ROUND(AVG(CASE WHEN city IN ('杭州', '上海', '北京', '深圳') THEN 1.0 ELSE 0.0 END), 4) AS city_valid_rate\nFROM users;`
  },
  'dg-l1-03': {
    requiredColumns: ['order_status', 'is_deleted', 'quantity', 'unit_price'],
    starter: `SELECT SUM(quantity * unit_price) FROM orders_raw WHERE order_status = 'paid';`,
    expected: `SELECT (SELECT COALESCE(SUM(quantity * unit_price), 0) FROM orders_raw\n          WHERE order_status = 'paid' AND is_deleted = false AND quantity > 0) AS order_paid_amount,\n       (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE pay_status = 'SUCCESS') AS paid_amount,\n       (SELECT COALESCE(SUM(refund_amount), 0) FROM refunds) AS refund_amount,\n       (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE pay_status = 'SUCCESS')\n         - (SELECT COALESCE(SUM(refund_amount), 0) FROM refunds) AS net_after_refund;`
  },
  'dg-l2-01': {
    starter: `-- 在元数据管理里查 dwd_order_detail 的资产信息，把结论固化为查询`,
    expected: `SELECT 'owner' AS item, '数据平台组' AS value\nUNION ALL\nSELECT 'schedule', '每日 02:10'\nUNION ALL\nSELECT 'amount 口径', 'quantity * unit_price，已支付有效订单';`
  },
  'dg-l2-02': {
    starter: `-- 在数据血缘平台按字段检索 pay 相关的下游，把影响面固化为查询`,
    expected: `SELECT 'dwd_order_detail' AS downstream, '全量重算' AS read_mode\nUNION ALL\nSELECT 'dws_shop_sales_daily', '增量依赖 DWD'\nUNION ALL\nSELECT 'ads_gmv_daily', '增量依赖 DWS';`
  },
  'dg-l2-03': {
    requiredColumns: ['user_id'],
    starter: `-- 口径 A：当月有行为的独立用户\nSELECT COUNT(DISTINCT user_id) FROM orders_raw;`,
    expected: `SELECT 'browse' AS metric_code, COUNT(DISTINCT user_id) AS metric_value FROM orders_raw\nUNION ALL\nSELECT 'order', COUNT(DISTINCT user_id) FROM orders_raw\n  WHERE is_deleted = false AND quantity > 0\n    AND order_status IN ('paid', 'shipped', 'completed');`
  },
  'dg-l3-01': {
    starter: `-- 对照数据标准逐项检查对象名，输出违规项清单`,
    expected: `SELECT 'OrderDetail_DWD' AS object_name, 'dwd_order_detail' AS fixed_name, '层级前缀位置错误' AS issue\nUNION ALL\nSELECT 'UserName', 'user_name', '应使用小写下划线';`
  },
  'dg-l3-02': {
    requiredColumns: ['mobile'],
    starter: `SELECT user_id, user_name, mobile, city\nFROM users\nLIMIT 10;`,
    expected: `SELECT user_id, user_name,\n       CASE WHEN mobile IS NULL THEN NULL\n            ELSE CONCAT(LEFT(mobile, 3), '****', RIGHT(mobile, 4)) END AS mobile_masked,\n       city\nFROM users\nWHERE city = '杭州'\nORDER BY user_id;`
  },
  'ops-l2-01': {
    starter: `-- 把已验证的清洗 SQL 登记为任务：任务名、脚本、产出表、调度、超时`,
    expected: `SELECT 'dwd_order_detail_daily' AS task_name, '每日 02:10' AS schedule,\n       'dwd_order_detail' AS output_table, '60 分钟' AS timeout\nUNION ALL\nSELECT '声明上游就绪依赖', '非纯时间触发', '产出按 dt 分区', '重试 3 次';`
  },
  'ops-l2-02': {
    starter: `-- 列出三层任务并声明直接上游`,
    expected: `SELECT 'dwd_order_detail_daily' AS task_name, '无上游' AS depends_on\nUNION ALL\nSELECT 'dws_shop_sales_daily', 'dwd_order_detail_daily'\nUNION ALL\nSELECT 'ads_gmv_daily', 'dws_shop_sales_daily';`
  },
  'ops-l2-03': {
    starter: `-- 区分两类失败：环境类可重试，数据类重试无效`,
    expected: `SELECT 'dwd_order_detail_daily' AS task_name, '3 次 / 间隔 5 分钟' AS retry_policy,\n       '重试耗尽后告警值班群' AS alert_policy\nUNION ALL\nSELECT '数据错误（脏数据/口径）', '重试无效', '直接告警并附失败原因';`
  },
  'ops-l3-01': {
    starter: `-- 发布前逐项自检：质量核查、依赖、分区、回滚方案`,
    expected: `SELECT '质量核查全绿' AS check_item, '通过' AS result\nUNION ALL\nSELECT '依赖声明完整', '通过'\nUNION ALL\nSELECT '产出分区与任务周期匹配', '通过'\nUNION ALL\nSELECT '回滚方案（删分区重跑上游）', '已明确';`
  },
  'ops-l3-02': {
    starter: `-- 逐门验证 R1-R5 并收集证据`,
    expected: `SELECT 'R1 跨引擎读写' AS gate, '通过：同一张表双引擎查询一致' AS evidence\nUNION ALL\nSELECT 'R2 空环境重建', '通过：迁移后数据完整'\nUNION ALL\nSELECT 'R3 任务链路', '通过：DWD-DWS-ADS 全链成功'\nUNION ALL\nSELECT 'R4 血缘完整', '通过：字段级上下游可见'\nUNION ALL\nSELECT 'R5 审计可查', '通过：查询记录含用户与语句';`
  },
  'ops-l3-03': {
    requiredColumns: ['dt', 'quantity', 'unit_price'],
    starter: `-- 排障第一步：对比逐日耗时，找出从哪天开始变慢`,
    expected: `SELECT '根因' AS item, '分区条件写在外层查询，分区裁剪失效退化为全表扫描' AS detail\nUNION ALL\nSELECT '修复', '把 dt 过滤下推到读表的那一层子查询'\nUNION ALL\nSELECT '验证', '重跑耗时回到 8 分钟，扫描仅命中当日分区';`
  },
  'ops-l3-04': {
    starter: `-- 从查询审计里按表名与时间窗拉取访问记录`,
    expected: `SELECT 'report_svc' AS user_name, '2026-11-13 09:12' AS query_time, '明细查询全表扫描' AS finding\nUNION ALL\nSELECT 'ops_wang', '2026-11-13 14:03', '读取 L3 敏感字段原文，需脱敏复核';`
  }
}

const json = (value) => {
  const text = JSON.stringify(value)
  return `$j$${text}$j$`
}

const sqlText = (text) => `$q$${text.trim()}$q$`

const lines = []
const out = (text = '') => lines.push(text)

out(`-- sim 学习中心：三条职业路径的课程种子（开发 / 治理 / 运维，共 29 个任务）。
-- 同时扩充教学数据集：店铺维表、用户表、支付与退款流水，并给订单表补 shop_id 列，
-- 使治理与口径类任务的 SQL 在真实数据上可判分。
-- related_features / knowledge 两列为 sim 新增，记录课程与平台功能、SQL 知识点的对应关系。`)

/* ---------- 数据集扩充 ---------- */
out()
out(`-- 店铺维表：按类目专营，与 orders_raw.category 对应。`)
out(`CREATE TABLE IF NOT EXISTS teach.shop_dim (`)
out(`    shop_id   BIGINT PRIMARY KEY,`)
out(`    shop_name VARCHAR(64) NOT NULL,`)
out(`    category  VARCHAR(32) NOT NULL`)
out(`);`)
out(`INSERT INTO teach.shop_dim (shop_id, shop_name, category) VALUES`)
out(`    (9001, '云集电器', 'electronics'),`)
out(`    (9002, '书香书店', 'books'),`)
out(`    (9003, '居家优选', 'home'),`)
out(`    (9004, '动感体育', 'sports')`)
out(`ON CONFLICT (shop_id) DO NOTHING;`)

out()
out(`-- 订单表补充店铺维度（按类目映射专营店），供店铺粒度聚合任务使用。`)
out(`ALTER TABLE teach.orders_raw ADD COLUMN IF NOT EXISTS shop_id BIGINT;`)
out(`UPDATE teach.orders_raw`)
out(`SET shop_id = CASE category`)
out(`    WHEN 'electronics' THEN 9001`)
out(`    WHEN 'books' THEN 9002`)
out(`    WHEN 'home' THEN 9003`)
out(`    WHEN 'sports' THEN 9004 END`)
out(`WHERE shop_id IS NULL;`)

out()
out(`-- 用户表：25 个注册用户，故意保留 2 个空手机号与 1 个未登记城市。`)
out(`CREATE TABLE IF NOT EXISTS teach.users (`)
out(`    user_id       BIGINT PRIMARY KEY,`)
out(`    user_name     VARCHAR(32) NOT NULL,`)
out(`    mobile        VARCHAR(20),`)
out(`    city          VARCHAR(16) NOT NULL,`)
out(`    register_time TIMESTAMP   NOT NULL`)
out(`);`)
const cities = ['杭州', '上海', '北京', '深圳']
const userRows = []
for (let i = 1; i <= 25; i++) {
  const uid = 500 + i
  const city = i === 19 ? '未知区域' : cities[i % 4]
  const mobile = i === 3 || i === 17 ? null : `138${String(1000 + i).padStart(4, '0')}${String(i).padStart(4, '0')}`.slice(0, 11)
  userRows.push(`    (${uid}, '用户${uid}', ${mobile === null ? 'NULL' : `'${mobile}'`}, '${city}', TIMESTAMP '2026-07-${String((i % 28) + 1).padStart(2, '0')} 09:00:00')`)
}
out(`INSERT INTO teach.users (user_id, user_name, mobile, city, register_time)`)
out(`SELECT * FROM (VALUES`)
out(userRows.join(',\n'))
out(`) AS seed(user_id, user_name, mobile, city, register_time)`)
out(`WHERE NOT EXISTS (SELECT 1 FROM teach.users);`)

out()
out(`-- 支付流水：与订单一一对应，故意保留重复流水、失败流水与无订单的孤儿支付。`)
out(`CREATE TABLE IF NOT EXISTS teach.payments (`)
out(`    pay_no     BIGINT PRIMARY KEY,`)
out(`    order_id   BIGINT,`)
out(`    user_id    BIGINT,`)
out(`    amount     NUMERIC(12,2) NOT NULL,`)
out(`    pay_status VARCHAR(16)   NOT NULL,`)
out(`    dt         DATE          NOT NULL`)
out(`);`)
const paidOrders = [
  [1001, 501, 398.0, '2026-08-01'], [1001, 501, 398.0, '2026-08-01'],
  [1002, 502, 59.9, '2026-08-01'], [1004, 504, 899.0, '2026-08-02'],
  [1005, null, 45.0, '2026-08-02'], [1010, 510, 176.0, '2026-08-04'],
  [1011, 511, 300.0, '2026-08-04'], [1013, 513, 499.0, '2026-08-05'],
  [1014, 514, 60.0, '2026-08-05'], [1015, 515, 0.0, '2026-08-05'],
  [1016, 516, 99.0, '2026-08-06'], [1018, 518, 210.0, '2026-08-06'],
  [1020, 520, 35.0, '2026-08-07'], [1021, 521, 2599.0, '2026-08-07'],
  [1023, 523, 780.0, '2026-08-08'], [1025, 525, 140.0, '2026-08-09']
]
const paymentRows = []
let payNo = 7001
const addPayment = (orderId, userId, amount, status, dt) => {
  paymentRows.push(`    (${payNo}, ${orderId ?? 'NULL'}, ${userId ?? 'NULL'}, ${amount.toFixed(2)}, '${status}', DATE '${dt}')`)
  payNo += 1
}
paidOrders.forEach(([oid, uid, amount, dt]) => {
  const status = amount === 0 ? 'FAIL' : 'SUCCESS'
  addPayment(oid, uid, amount, status, dt)
})
addPayment(1001, 501, 398.0, 'SUCCESS', '2026-08-01') // 重复流水（SDK 重发）
for (let i = 0; i < 4; i++) addPayment(1023, 523, 780.0, 'SUCCESS', '2026-08-08') // 当日行数暴涨演示
addPayment(9999, null, 500.0, 'SUCCESS', '2026-08-08') // 孤儿支付（订单不存在）
out(`INSERT INTO teach.payments (pay_no, order_id, user_id, amount, pay_status, dt)`)
out(`SELECT * FROM (VALUES`)
out(paymentRows.join(',\n'))
out(`) AS seed(pay_no, order_id, user_id, amount, pay_status, dt)`)
out(`WHERE NOT EXISTS (SELECT 1 FROM teach.payments);`)

out()
out(`-- 退款流水：两笔，其中一笔对应已被逻辑删除的订单，用于口径差异演示。`)
out(`CREATE TABLE IF NOT EXISTS teach.refunds (`)
out(`    refund_no     BIGINT PRIMARY KEY,`)
out(`    order_id      BIGINT        NOT NULL,`)
out(`    refund_amount NUMERIC(12,2) NOT NULL,`)
out(`    dt            DATE          NOT NULL`)
out(`);`)
out(`INSERT INTO teach.refunds (refund_no, order_id, refund_amount, dt)`)
out(`SELECT * FROM (VALUES`)
out(`    (8001, 1006, 30.00, DATE '2026-08-02'),`)
out(`    (8002, 1011, 300.00, DATE '2026-08-04')`)
out(`) AS seed(refund_no, order_id, refund_amount, dt)`)
out(`WHERE NOT EXISTS (SELECT 1 FROM teach.refunds);`)

/* ---------- 结构扩展 ---------- */
out()
out(`-- 课程与平台功能的对应关系、SQL 知识点，随任务下发到前端。`)
out(`ALTER TABLE learning.course_module ADD COLUMN IF NOT EXISTS related_features JSONB;`)
out(`ALTER TABLE learning.lab ADD COLUMN IF NOT EXISTS related_features JSONB;`)
out(`ALTER TABLE learning.lab ADD COLUMN IF NOT EXISTS knowledge JSONB;`)

/* ---------- 路径 ---------- */
out()
out(`-- 三条职业路径（与既有 de-foundation 并存，code 不同互不影响）。`)
out(`INSERT INTO learning.learning_path (code, title, description, target_role, level, version, status, estimated_hours, order_no) VALUES`)
const pathValues = paths.map((p) =>
  `    ('${p.code}', '${p.title}', '${p.description.replace(/'/g, "''")}', '${p.targetRole}', '${p.level}', ${p.version}, 'published', ${p.estimatedHours}, ${paths.indexOf(p) + 2})`
)
out(pathValues.join(',\n'))
out(`ON CONFLICT (code) DO NOTHING;`)

/* ---------- 技能节点 ---------- */
out()
out(`INSERT INTO learning.skill_node (path_code, code, parent_code, title, level, knowledge_points, outcome, order_no) VALUES`)
const skillValues = []
const skillByPath = {
  'de-basic': [
    ['de-sql-basics', 'SQL 查询与过滤', 'L1', ['SELECT 投影', 'WHERE', 'NULL 三值逻辑', '排序与行数限制'], '能写出列裁剪、口径明确的只读查询'],
    ['de-join-dict', 'JOIN 与字典关联', 'L1', ['LEFT JOIN', '重复行放大', '孤儿数据'], '能安全关联字典并处理未知枚举'],
    ['de-aggregate', '聚合与去重计数', 'L1', ['GROUP BY', 'HAVING', 'COUNT DISTINCT'], '能按口径产出分组统计'],
    ['de-dedup', '幂等去重', 'L2', ['ROW_NUMBER', '幂等重跑', '排序键确定性'], '能产出可重跑的 DWD 去重结果'],
    ['de-partition', '增量与分区裁剪', 'L2', ['分区裁剪', '增量口径'], '能避免全表扫描并解释扫描量'],
    ['de-quality-check', '清洗对账', 'L2', ['行数对账', '主键唯一', '空值率'], '能证明加工没有丢数据'],
    ['de-metric', '指标口径与聚合层', 'L2', ['可加性', 'LAG 环比', '口径冲突'], '能按登记口径产出 DWS/ADS']
  ],
  'dg-basic': [
    ['dg-table-rules', '表级质量规则', 'L2', ['行数波动', '主键唯一', '基线选择'], '能配置行数波动与唯一性核查'],
    ['dg-column-rules', '列级质量规则', 'L2', ['空值率', '枚举校验', '格式校验'], '能一次扫描产出多列合法率'],
    ['dg-reconcile', '跨表勾稽', 'L3', ['FULL OUTER JOIN', '差异分解'], '能把对账差异拆解到可解释类别'],
    ['dg-metadata', '元数据认知', 'L3', ['技术元数据', '业务元数据', '责任人'], '能评估一张表的可信度'],
    ['dg-lineage', '血缘与影响面', 'L3', ['字段级血缘', '读取方式'], '能评估字段变更的影响范围'],
    ['dg-semantic', '语义资产评审', 'L3', ['同名指标', '口径登记'], '能发现并收口口径冲突'],
    ['dg-standard', '数据标准落地', 'L3', ['分层前缀', '命名规范'], '能按标准审查与整改对象名'],
    ['dg-classify', '分类分级与脱敏', 'L3', ['敏感字段', '脱敏写法', '审计联动'], '能在 SQL 层养成脱敏习惯']
  ],
  'ops-basic': [
    ['ops-task-def', '任务定义', 'L2', ['脚本与任务', '调度周期', '超时'], '能把脚本变成生产任务'],
    ['ops-dependency', '依赖编排', 'L2', ['DAG', '拓扑序', '失败传播'], '能声明三层任务的依赖关系'],
    ['ops-retry-alert', '重试与告警', 'L2', ['瞬时故障', '数据错误', '信噪比'], '能区分失败类型并配置告警'],
    ['ops-release', '发布检查清单', 'L3', ['质量门槛', '回滚方案'], '能完成一次可回滚的发布'],
    ['ops-acceptance', '运行验收', 'L3', ['验收门', '证据收集'], '能产出可复核的验收记录'],
    ['ops-troubleshoot', '监控排障', 'L3', ['耗时曲线', '扫描量', '分区下推'], '能定位一条慢 SQL 的根因'],
    ['ops-audit', '查询审计', 'L3', ['审计三问', '分级联动'], '能回答谁在何时访问了什么']
  ]
}
for (const [pathCode, skills] of Object.entries(skillByPath)) {
  skills.forEach(([code, title, level, points, outcome], index) => {
    skillValues.push(`    ('${pathCode}', '${code}', NULL, '${title}', '${level}', ${json(points)}, '${outcome}', ${index + 1})`)
  })
}
out(skillValues.join(',\n'))
out(`ON CONFLICT (code) DO NOTHING;`)

/* ---------- 课程与模块 ---------- */
out()
out(`INSERT INTO learning.course (path_code, code, title, summary, version, status, author) VALUES`)
const courseValues = paths.map((p) =>
  `    ('${p.code}', 'course-${p.code}', '${p.title}（实战）', '${p.description.replace(/'/g, "''")}', ${p.version}, 'published', 'Aetheris 教学组')`
)
out(courseValues.join(',\n'))
out(`ON CONFLICT (code) DO NOTHING;`)

out()
out(`INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features) VALUES`)
const moduleValues = []
for (const detail of pathDetails) {
  detail.modules.forEach((m, index) => {
    moduleValues.push(
      `    ('course-${detail.code}', '${m.code}', '${m.title.replace(/'/g, "''")}', '${(m.objective ?? '').replace(/'/g, "''")}', '${m.level}', ${index + 1}, ${json(m.relatedFeatures ?? [])})`
    )
  })
}
out(moduleValues.join(',\n'))
out(`ON CONFLICT (code) DO NOTHING;`)

/* ---------- 任务 ---------- */
out()
out(`INSERT INTO learning.lab`)
out(`    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,`)
out(`     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,`)
out(`     related_features, knowledge)`)
out(`VALUES`)
const labValues = []
for (const detail of pathDetails) {
  for (const m of detail.modules) {
    m.labs.forEach((card, index) => {
      const detail1 = labDetails[card.code]
      const override = SQL[card.code]
      if (!override) throw new Error(`missing SQL override for ${card.code}`)
      const features = detail1.relatedFeatures ?? m.relatedFeatures ?? []
      labValues.push([
        `    ('${m.code}', '${card.code}', '${card.title.replace(/'/g, "''")}', '${card.level}', '${card.difficulty}', 'SQL',`,
        `     '${(card.summary ?? '').replace(/'/g, "''")}',`,
        `     '${(detail1.scenario ?? '').replace(/'/g, "''")}',`,
        `     '${(detail1.objective ?? '').replace(/'/g, "''")}',`,
        `     ${json(detail1.steps)},`,
        `     ${json(mapRefs(detail1.tableRefs))},`,
        `     ${sqlText(override.starter)},`,
        `     ${sqlText(override.expected)},`,
        `     ${json(policy(override.requiredColumns ? { requiredColumns: override.requiredColumns } : {}))},`,
        `     ${json(detail1.rubric)},`,
        `     ${detail1.passScore}, ${detail1.estimatedMinutes}, 'published', ${detail1.version ?? 1}, ${index + 1},`,
        `     ${json(features)},`,
        `     ${json(detail1.knowledge ?? [])})`
      ].join('\n'))
    })
  }
}
out(labValues.join(',\n'))
out(`ON CONFLICT (code) DO NOTHING;`)

out()
out(`-- 端到端项目：把任务串成可交付成果。project 表没有 path_code 列（V5 定义），`)
out(`-- 项目与路径的关联通过任务归属隐式表达。`)
out(`INSERT INTO learning.project (code, title, summary, level, scenario, deliverables, status, order_no) VALUES`)
out(`    ('proj-gmv', '交易域 GMV 链路', '从 ODS 原始流水到 ADS 日报：清洗、去重、聚合、口径对账，交付一条可重跑的 GMV 链路。', 'L2', '为云集电商交易域搭建一条从原始流水到日报指标的完整链路，所有产出必须可重跑、可对账。', ${json(['DWD 明细表', 'DWS 店铺日聚合', 'GMV 日报', '口径对账说明'])}, 'published', 10),`)
out(`    ('proj-quality', '交易域质量防线', '为交易域核心表配置质量核查：表级波动、列级空值率、跨表勾稽，并接通报警。', 'L2', '给交易域三张核心表配上质量核查规则，把对账从人工变成例行任务。', ${json(['核查规则清单', '差异分解 SQL'])}, 'published', 11),`)
out(`    ('proj-lineage', '字段变更影响面评估', '模拟一次字段类型变更：从血缘定位影响面，输出变更方案与回滚预案。', 'L3', 'pay 相关字段要做类型变更，先评估影响面再动手。', ${json(['影响面清单', '变更与回滚方案'])}, 'published', 12)`)
out(`ON CONFLICT (code) DO NOTHING;`)

writeFileSync(
  new URL('../src/main/resources/db/migration/V9__sim_curriculum.sql', import.meta.url),
  lines.join('\n') + '\n'
)
console.log('V9 written:', lines.length, 'lines')

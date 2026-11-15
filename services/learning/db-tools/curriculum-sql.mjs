// 29 个任务的起点 SQL 与标准答案，针对 teach 教学数据集编写。
// 这是 starter/expected SQL 的唯一来源：Flyway 迁移（gen-v9.mjs）和前端 mock 都引用它，
// 避免"预览看到的 SQL"与"真实判分用的 SQL"出现两套内容。
export const SQL = {
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

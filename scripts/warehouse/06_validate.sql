-- Aetheris 数仓数据校验。
--
-- 这个脚本有两个用途：
--   1. 造数后确认各层行数、分区数与脏数据比例符合预期；
--   2. 作为后续 quality 服务规则的基准 —— 每一段查询对应一条将来要落库的规则。
--
-- 全部为只读查询，可反复执行。末尾assert_true失败会让Spark SQL/beeline非零退出。

-- ---------------------------------------------------------------------------
-- 1. 各层行数总览
-- ---------------------------------------------------------------------------
SELECT 'ods.ods_user'       AS table_name, COUNT(*) AS row_cnt FROM ods.ods_user
UNION ALL SELECT 'ods.ods_product',        COUNT(*) FROM ods.ods_product
UNION ALL SELECT 'ods.ods_order',          COUNT(*) FROM ods.ods_order
UNION ALL SELECT 'ods.ods_order_item',     COUNT(*) FROM ods.ods_order_item
UNION ALL SELECT 'dwd.dwd_user',           COUNT(*) FROM dwd.dwd_user
UNION ALL SELECT 'dwd.dwd_product',        COUNT(*) FROM dwd.dwd_product
UNION ALL SELECT 'dwd.dwd_order',          COUNT(*) FROM dwd.dwd_order
UNION ALL SELECT 'dwd.dwd_order_item',     COUNT(*) FROM dwd.dwd_order_item
UNION ALL SELECT 'dws.dws_order_daily',    COUNT(*) FROM dws.dws_order_daily
UNION ALL SELECT 'dws.dws_category_daily', COUNT(*) FROM dws.dws_category_daily
UNION ALL SELECT 'dws.dws_user_daily',     COUNT(*) FROM dws.dws_user_daily
UNION ALL SELECT 'ads.ads_order_stats',    COUNT(*) FROM ads.ads_order_stats
UNION ALL SELECT 'ads.ads_category_stats', COUNT(*) FROM ads.ads_category_stats
UNION ALL SELECT 'ads.ads_user_profile',   COUNT(*) FROM ads.ads_user_profile
UNION ALL SELECT 'dim.dim_category',       COUNT(*) FROM dim.dim_category
UNION ALL SELECT 'dim.dim_date',           COUNT(*) FROM dim.dim_date
ORDER BY table_name;

-- ---------------------------------------------------------------------------
-- 2. ODS 脏数据比例（应与 02_ods.sql 注释中的设计比例吻合）
-- ---------------------------------------------------------------------------
SELECT
    '用户-性别空值'   AS check_item,
    COUNT(CASE WHEN gender IS NULL THEN 1 END)                       AS bad_cnt,
    COUNT(*)                                                         AS total_cnt,
    ROUND(COUNT(CASE WHEN gender IS NULL THEN 1 END) * 100.0 / COUNT(*), 2) AS bad_pct
FROM ods.ods_user
UNION ALL
SELECT '用户-年龄超范围',
    COUNT(CASE WHEN CAST(age AS INT) >= 120 THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN CAST(age AS INT) >= 120 THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_user
UNION ALL
SELECT '用户-邮箱非法',
    COUNT(CASE WHEN email IS NOT NULL AND email NOT LIKE '%@%' THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN email IS NOT NULL AND email NOT LIKE '%@%' THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_user
UNION ALL
SELECT '用户-会员等级非法',
    COUNT(CASE WHEN member_level NOT IN ('普通','银卡','金卡','钻石') THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN member_level NOT IN ('普通','银卡','金卡','钻石') THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_user
UNION ALL
SELECT '商品-单价为负',
    COUNT(CASE WHEN CAST(price AS DECIMAL(12,2)) < 0 THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN CAST(price AS DECIMAL(12,2)) < 0 THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_product
UNION ALL
SELECT '商品-品类无效',
    COUNT(CASE WHEN category_id IS NULL OR category_id = '99' THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN category_id IS NULL OR category_id = '99' THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_product
UNION ALL
SELECT '订单-状态非法',
    COUNT(CASE WHEN order_status IS NULL OR order_status NOT IN ('1','2','3','4','5','6') THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN order_status IS NULL OR order_status NOT IN ('1','2','3','4','5','6') THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_order
UNION ALL
SELECT '订单-金额空值',
    COUNT(CASE WHEN pay_amount IS NULL THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN pay_amount IS NULL THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_order
UNION ALL
SELECT '订单-逻辑删除',
    COUNT(CASE WHEN is_deleted = '1' THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN is_deleted = '1' THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_order
UNION ALL
SELECT '明细-数量非正',
    COUNT(CASE WHEN CAST(quantity AS INT) <= 0 THEN 1 END),
    COUNT(*),
    ROUND(COUNT(CASE WHEN CAST(quantity AS INT) <= 0 THEN 1 END) * 100.0 / COUNT(*), 2)
FROM ods.ods_order_item;

-- ---------------------------------------------------------------------------
-- 3. ODS 订单重复行（设计为约 0.3%，DWD 应已去重）
-- ---------------------------------------------------------------------------
SELECT
    'ODS 订单重复'  AS check_item,
    COUNT(*)        AS duplicated_order_ids
FROM (
    SELECT order_id FROM ods.ods_order GROUP BY order_id HAVING COUNT(*) > 1
) t;

-- DWD 订单必须零重复，这是清洗是否生效的硬指标。
SELECT
    'DWD 订单重复(应为0)' AS check_item,
    COUNT(*)              AS duplicated_order_ids
FROM (
    SELECT order_id FROM dwd.dwd_order GROUP BY order_id HAVING COUNT(*) > 1
) t;

-- ---------------------------------------------------------------------------
-- 4. 清洗效果对比：ODS 与 DWD 的行数差异应等于「重复行 + 逻辑删除行」
-- ---------------------------------------------------------------------------
SELECT
    (SELECT COUNT(*) FROM ods.ods_order)                                      AS ods_rows,
    (SELECT COUNT(*) FROM dwd.dwd_order)                                      AS dwd_rows,
    (SELECT COUNT(*) FROM ods.ods_order WHERE is_deleted = '1')               AS deleted_rows,
    (SELECT COUNT(*) - COUNT(DISTINCT order_id) FROM ods.ods_order)           AS duplicate_rows;

-- ---------------------------------------------------------------------------
-- 5. 分区覆盖情况：确认每个 dt 都有数据，且区间连续
-- ---------------------------------------------------------------------------
SELECT
    COUNT(DISTINCT dt) AS partition_cnt,
    MIN(dt)            AS min_dt,
    MAX(dt)            AS max_dt
FROM dwd.dwd_order;

-- ---------------------------------------------------------------------------
-- 6. 口径一致性：DWS 的 GMV 汇总必须等于 DWD 明细直接聚合
-- ---------------------------------------------------------------------------
-- 两边不一致说明 DWS 加工逻辑与 is_valid_order 口径脱节。
SELECT
    dws.total_gmv          AS dws_gmv,
    dwd.total_gmv          AS dwd_gmv,
    ABS(dws.total_gmv - dwd.total_gmv) < 1 AS is_consistent
FROM
    (SELECT CAST(SUM(gmv) AS DECIMAL(20,2)) AS total_gmv FROM dws.dws_order_daily) dws
CROSS JOIN
    (SELECT CAST(SUM(pay_amount) AS DECIMAL(20,2)) AS total_gmv
     FROM dwd.dwd_order WHERE is_valid_order) dwd;

-- ---------------------------------------------------------------------------
-- 7. 业务规律抽样：近 14 天 GMV 走势（周末应明显高于工作日）
-- ---------------------------------------------------------------------------
SELECT
    stat_date,
    is_weekend,
    valid_order_cnt,
    gmv,
    avg_order_amount,
    gmv_mom
FROM ads.ads_order_stats
ORDER BY stat_date DESC
LIMIT 14;

-- ---------------------------------------------------------------------------
-- 8. 品类排行抽样：最近一个月销售额 TOP 品类
-- ---------------------------------------------------------------------------
SELECT
    month_key, category_name, sale_qty, sale_amount, amount_rank, amount_share
FROM ads.ads_category_stats
WHERE month_key = (SELECT MAX(month_key) FROM ads.ads_category_stats)
ORDER BY amount_rank
LIMIT 10;

-- ---------------------------------------------------------------------------
-- 9. 金额桥：模拟数据仅含明细优惠，运费、订单级优惠均为0。
-- 实付缺失的订单不能当作0支付；其明细净额单列，避免把两个金额指标强行相等。
-- ---------------------------------------------------------------------------
SELECT
    SUM(i.net_amount) AS valid_line_net,
    SUM(CASE WHEN o.pay_amount IS NULL THEN i.net_amount ELSE 0 END) AS missing_payment_line_net,
    SUM(i.net_amount) - SUM(CASE WHEN o.pay_amount IS NULL THEN i.net_amount ELSE 0 END) AS expected_known_payment
FROM dwd.dwd_order_item i
JOIN dwd.dwd_order o ON i.order_id=o.order_id AND i.dt=o.dt
WHERE o.is_valid_order;

-- 以下均为必须通过的断言；不能只打印false继续报告成功。
-- ASSERT: dwd-order-unique
SELECT assert_true(COUNT(*)=COUNT(DISTINCT order_id), 'dwd-order-unique') FROM dwd.dwd_order;
-- ASSERT: dwd-item-unique
SELECT assert_true(COUNT(*)=COUNT(DISTINCT item_id), 'dwd-item-unique') FROM dwd.dwd_order_item;
-- ASSERT: dwd-user-unique
SELECT assert_true(COUNT(*)=COUNT(DISTINCT user_id), 'dwd-user-unique') FROM dwd.dwd_user;
-- ASSERT: dwd-product-unique
SELECT assert_true(COUNT(*)=COUNT(DISTINCT product_id), 'dwd-product-unique') FROM dwd.dwd_product;

-- ASSERT: cleaned-order-count
SELECT assert_true(ods.n=dwd.n, 'cleaned-order-count')
FROM (SELECT COUNT(DISTINCT order_id) n FROM ods.ods_order WHERE is_deleted='0') ods
CROSS JOIN (SELECT COUNT(*) n FROM dwd.dwd_order) dwd;

-- ASSERT: required-nonempty
SELECT assert_true(MIN(n)>0, 'required-nonempty') FROM (
    SELECT COUNT(*) n FROM ods.ods_user UNION ALL SELECT COUNT(*) FROM ods.ods_product
    UNION ALL SELECT COUNT(*) FROM ods.ods_order UNION ALL SELECT COUNT(*) FROM ods.ods_order_item
    UNION ALL SELECT COUNT(*) FROM dwd.dwd_user UNION ALL SELECT COUNT(*) FROM dwd.dwd_product
    UNION ALL SELECT COUNT(*) FROM dwd.dwd_order UNION ALL SELECT COUNT(*) FROM dwd.dwd_order_item
    UNION ALL SELECT COUNT(*) FROM dws.dws_order_daily UNION ALL SELECT COUNT(*) FROM dws.dws_category_daily
    UNION ALL SELECT COUNT(*) FROM dws.dws_user_daily UNION ALL SELECT COUNT(*) FROM ads.ads_order_stats
    UNION ALL SELECT COUNT(*) FROM ads.ads_category_stats UNION ALL SELECT COUNT(*) FROM ads.ads_user_profile
    UNION ALL SELECT COUNT(*) FROM dim.dim_date UNION ALL SELECT COUNT(*) FROM dim.dim_category
) required;

-- ASSERT: item-net-identity
SELECT assert_true(COUNT(*)=0, 'item-net-identity') FROM dwd.dwd_order_item
WHERE NOT (net_amount <=> item_amount-discount) OR discount<0 OR discount>item_amount;

-- ASSERT: order-line-payment
SELECT assert_true(COUNT(*)=0, 'order-line-payment') FROM dwd.dwd_order o
LEFT JOIN (SELECT order_id,dt,SUM(net_amount) net FROM dwd.dwd_order_item GROUP BY order_id,dt) i
ON o.order_id=i.order_id AND o.dt=i.dt
WHERE o.pay_amount IS NOT NULL AND NOT (o.pay_amount <=> i.net);

-- ASSERT: known-payment-bridge
SELECT assert_true(header.amount=lines.amount, 'known-payment-bridge')
FROM (SELECT COALESCE(SUM(pay_amount),0) amount FROM dwd.dwd_order WHERE is_valid_order) header
CROSS JOIN (SELECT COALESCE(SUM(CASE WHEN o.pay_amount IS NOT NULL THEN i.net_amount ELSE 0 END),0) amount
 FROM dwd.dwd_order_item i JOIN dwd.dwd_order o ON i.order_id=o.order_id AND i.dt=o.dt WHERE o.is_valid_order) lines;

-- ASSERT: dwd-dws-order-daily
SELECT assert_true(COUNT(*)=0, 'dwd-dws-order-daily') FROM dws.dws_order_daily actual
FULL OUTER JOIN (SELECT dt,COUNT(*) order_cnt,COUNT(CASE WHEN is_valid_order THEN 1 END) valid_order_cnt,
 COUNT(DISTINCT CASE WHEN is_valid_order THEN user_id END) paid_user_cnt,
 CAST(COALESCE(SUM(CASE WHEN is_valid_order THEN pay_amount END),0) AS DECIMAL(18,2)) gmv
 FROM dwd.dwd_order GROUP BY dt) expected ON actual.dt=expected.dt
WHERE actual.dt IS NULL OR expected.dt IS NULL OR NOT (actual.gmv <=> expected.gmv)
 OR NOT (actual.order_cnt <=> expected.order_cnt) OR NOT (actual.valid_order_cnt <=> expected.valid_order_cnt)
 OR NOT (actual.paid_user_cnt <=> expected.paid_user_cnt);

-- ASSERT: dwd-dws-category-daily
SELECT assert_true(COUNT(*)=0, 'dwd-dws-category-daily') FROM dws.dws_category_daily actual
FULL OUTER JOIN (SELECT i.dt,i.category_id,COALESCE(i.category_name,'未分类') category_name,
 COUNT(DISTINCT i.order_id) order_cnt,COALESCE(SUM(i.quantity),0) sale_qty,
 CAST(COALESCE(SUM(i.net_amount),0) AS DECIMAL(18,2)) sale_amount,
 CAST(COALESCE(SUM(i.discount),0) AS DECIMAL(18,2)) discount_amount
 FROM dwd.dwd_order_item i JOIN dwd.dwd_order o ON i.order_id=o.order_id AND i.dt=o.dt AND o.is_valid_order
 GROUP BY i.dt,i.category_id,COALESCE(i.category_name,'未分类')) expected
ON actual.dt=expected.dt AND actual.category_id <=> expected.category_id AND actual.category_name=expected.category_name
WHERE actual.dt IS NULL OR expected.dt IS NULL OR NOT (actual.sale_amount <=> expected.sale_amount)
 OR NOT (actual.sale_qty <=> expected.sale_qty) OR NOT (actual.discount_amount <=> expected.discount_amount)
 OR NOT (actual.order_cnt <=> expected.order_cnt);

-- ASSERT: dws-ads-order-daily
SELECT assert_true(COUNT(*)=0, 'dws-ads-order-daily') FROM ads.ads_order_stats actual
FULL OUTER JOIN dws.dws_order_daily expected ON actual.stat_date=expected.dt
WHERE actual.stat_date IS NULL OR expected.dt IS NULL OR NOT (actual.gmv <=> expected.gmv)
 OR NOT (actual.order_cnt <=> expected.order_cnt) OR NOT (actual.valid_order_cnt <=> expected.valid_order_cnt)
 OR NOT (actual.paid_user_cnt <=> expected.paid_user_cnt) OR NOT (actual.avg_order_amount <=> expected.avg_order_amount);

-- ASSERT: dws-ads-category-monthly
SELECT assert_true(COUNT(*)=0, 'dws-ads-category-monthly') FROM ads.ads_category_stats actual
FULL OUTER JOIN (SELECT SUBSTR(dt,1,7) month_key,category_id,category_name,SUM(order_cnt) order_cnt,SUM(sale_qty) sale_qty,
 CAST(SUM(sale_amount) AS DECIMAL(18,2)) sale_amount,CAST(SUM(discount_amount) AS DECIMAL(18,2)) discount_amount
 FROM dws.dws_category_daily GROUP BY SUBSTR(dt,1,7),category_id,category_name) expected
ON actual.month_key=expected.month_key AND actual.category_id <=> expected.category_id AND actual.category_name=expected.category_name
WHERE actual.month_key IS NULL OR expected.month_key IS NULL OR NOT (actual.sale_amount <=> expected.sale_amount)
 OR NOT (actual.sale_qty <=> expected.sale_qty) OR NOT (actual.order_cnt <=> expected.order_cnt)
 OR NOT (actual.discount_amount <=> expected.discount_amount);

-- ASSERT: ads-category-share
SELECT assert_true(COUNT(*)=0, 'ads-category-share') FROM (
 SELECT amount_share,sale_amount,SUM(sale_amount) OVER(PARTITION BY month_key) total,
 amount_rank,RANK() OVER(PARTITION BY month_key ORDER BY sale_amount DESC) expected_rank FROM ads.ads_category_stats
) shares WHERE NOT (amount_share <=> CASE WHEN total>0 THEN CAST(sale_amount/total AS DECIMAL(6,4)) ELSE NULL END)
 OR NOT (amount_rank <=> expected_rank);

-- ASSERT: ads-category-share-sum
SELECT assert_true(COUNT(*)=0, 'ads-category-share-sum') FROM (
 SELECT month_key,SUM(sale_amount) amount,SUM(amount_share) share,COUNT(*) n FROM ads.ads_category_stats GROUP BY month_key
) shares WHERE amount>0 AND (share IS NULL OR ABS(share-1)>n*0.00005+0.00000001);

-- ASSERT: ads-user-monthly-distinct
SELECT assert_true(COUNT(*)=0, 'ads-user-monthly-distinct') FROM ads.ads_user_profile actual
FULL OUTER JOIN (SELECT SUBSTR(o.dt,1,7) month_key,u.member_level,u.age_group,u.gender,
 COUNT(DISTINCT o.user_id) order_user_cnt,COUNT(*) order_cnt,CAST(COALESCE(SUM(o.pay_amount),0) AS DECIMAL(18,2)) gmv
 FROM dwd.dwd_order o JOIN dwd.dwd_user u ON o.user_id=u.user_id WHERE o.is_valid_order
 GROUP BY SUBSTR(o.dt,1,7),u.member_level,u.age_group,u.gender) expected
ON actual.month_key=expected.month_key AND actual.member_level <=> expected.member_level
 AND actual.age_group <=> expected.age_group AND actual.gender <=> expected.gender
WHERE actual.month_key IS NULL OR expected.month_key IS NULL OR NOT (actual.gmv <=> expected.gmv)
 OR NOT (actual.order_cnt <=> expected.order_cnt) OR NOT (actual.order_user_cnt <=> expected.order_user_cnt);

-- ASSERT: dim-date-unique-contiguous
SELECT assert_true(COUNT(*)=COUNT(DISTINCT date_key) AND COUNT(*)=DATEDIFF(MAX(date_key),MIN(date_key))+1,
 'dim-date-unique-contiguous') FROM dim.dim_date;
-- ASSERT: dim-date-covers-orders
SELECT assert_true(COUNT(*)=0, 'dim-date-covers-orders') FROM dwd.dwd_order o
LEFT JOIN dim.dim_date d ON o.dt=d.date_key WHERE d.date_key IS NULL;
-- ASSERT: ads-date-attributes
SELECT assert_true(COUNT(*)=0, 'ads-date-attributes') FROM ads.ads_order_stats
WHERE month_key IS NULL OR NOT (month_key <=> SUBSTR(stat_date,1,7))
 OR NOT (is_weekend <=> (DAYOFWEEK(CAST(stat_date AS DATE)) IN (1,7)));

-- ASSERT: dwd-pii-masked
SELECT assert_true(COUNT(*)=0, 'dwd-pii-masked') FROM dwd.dwd_user
WHERE (phone_masked IS NOT NULL AND NOT phone_masked RLIKE '^[0-9]{3}[*]{4}[0-9]{4}$')
 OR (email IS NOT NULL AND NOT email RLIKE '^[^@]{1,2}[*]{3}@[^@]+$');
-- ASSERT: dwd-user-clean-values
SELECT assert_true(COUNT(*)=0, 'dwd-user-clean-values') FROM dwd.dwd_user
WHERE gender NOT IN ('M','F','U') OR gender IS NULL OR member_level NOT IN ('普通','银卡','金卡','钻石')
 OR (age IS NOT NULL AND (age<=0 OR age>=120)) OR register_date IS NULL;

-- Aetheris 数仓数据校验。
--
-- 这个脚本有两个用途：
--   1. 造数后确认各层行数、分区数与脏数据比例符合预期；
--   2. 作为后续 quality 服务规则的基准 —— 每一段查询对应一条将来要落库的规则。
--
-- 全部为只读查询，可反复执行。

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

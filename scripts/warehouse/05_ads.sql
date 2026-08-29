-- Aetheris ADS 应用层加工。
--
-- ADS 约定：面向报表与指标的最终结果表，口径已固化，可直接对外提供。
-- 这一层的表结构就是后续语义层「指标」的物理落点，
-- 字段注释里的业务名词（GMV、客单价、复购率）是 NL2SQL 命中的关键。
--
-- 与 DWS 的区别：DWS 是可复用的中间汇总，ADS 是特定分析场景的成品，
-- 通常不再分区（数据量小、整表覆盖写）。

-- ---------------------------------------------------------------------------
-- ads.ads_order_stats 订单核心指标日报
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ads.ads_order_stats;
CREATE TABLE ads.ads_order_stats (
    stat_date        STRING        COMMENT '统计日期，格式 yyyy-MM-dd',
    month_key        STRING        COMMENT '所属月份，格式 yyyy-MM',
    is_weekend       BOOLEAN       COMMENT '是否周末',
    order_cnt        BIGINT        COMMENT '订单总数',
    valid_order_cnt  BIGINT        COMMENT '有效订单数(已付款/已发货/已完成)',
    paid_user_cnt    BIGINT        COMMENT '下单用户数(去重)',
    gmv              DECIMAL(18,2) COMMENT 'GMV，有效订单实付金额之和(元)',
    avg_order_amount DECIMAL(12,2) COMMENT '客单价 = GMV / 有效订单数(元)',
    refund_rate      DECIMAL(6,4)  COMMENT '退款率 = 退款订单数 / 订单总数',
    cancel_rate      DECIMAL(6,4)  COMMENT '取消率 = 取消订单数 / 订单总数',
    gmv_mom          DECIMAL(10,4) COMMENT 'GMV 日环比 = (今日GMV - 昨日GMV) / 昨日GMV',
    gmv_7d_avg       DECIMAL(18,2) COMMENT 'GMV 近7日移动平均(元)'
)
COMMENT 'ADS 订单核心指标日报，含 GMV、客单价、退款率与环比，口径已固化'
STORED AS PARQUET;

INSERT OVERWRITE TABLE ads.ads_order_stats
SELECT
    d.stat_date,
    dd.month_key,
    dd.is_weekend,
    d.order_cnt,
    d.valid_order_cnt,
    d.paid_user_cnt,
    d.gmv,
    d.avg_order_amount,
    CASE WHEN d.order_cnt > 0
         THEN CAST(d.refund_order_cnt / d.order_cnt AS DECIMAL(6,4))
         ELSE NULL
    END                                                               AS refund_rate,
    CASE WHEN d.order_cnt > 0
         THEN CAST(d.cancel_order_cnt / d.order_cnt AS DECIMAL(6,4))
         ELSE NULL
    END                                                               AS cancel_rate,
    -- 环比：首日无前值，且前值为 0 时不计算，避免除零。
    CASE WHEN d.prev_gmv IS NOT NULL AND d.prev_gmv > 0
         THEN CAST((d.gmv - d.prev_gmv) / d.prev_gmv AS DECIMAL(10,4))
         ELSE NULL
    END                                                               AS gmv_mom,
    CAST(d.gmv_7d_avg AS DECIMAL(18,2))                               AS gmv_7d_avg
FROM (
    SELECT
        dt AS stat_date,
        order_cnt, valid_order_cnt, paid_user_cnt, gmv, avg_order_amount,
        refund_order_cnt, cancel_order_cnt,
        LAG(gmv) OVER (ORDER BY dt)                                   AS prev_gmv,
        AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS gmv_7d_avg
    FROM dws.dws_order_daily
) d
LEFT JOIN dim.dim_date dd ON d.stat_date = dd.date_key;

-- ---------------------------------------------------------------------------
-- ads.ads_category_stats 品类销售排行
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ads.ads_category_stats;
CREATE TABLE ads.ads_category_stats (
    month_key       STRING        COMMENT '统计月份，格式 yyyy-MM',
    category_id     INT           COMMENT '品类ID',
    category_name   STRING        COMMENT '品类名称',
    order_cnt       BIGINT        COMMENT '订单数',
    sale_qty        BIGINT        COMMENT '销售件数',
    sale_amount     DECIMAL(18,2) COMMENT '销售额(元)',
    discount_amount DECIMAL(18,2) COMMENT '优惠金额(元)',
    avg_price       DECIMAL(12,2) COMMENT '件均价(元)',
    amount_rank     INT           COMMENT '当月销售额排名，1 为最高',
    amount_share    DECIMAL(6,4)  COMMENT '销售额占当月全品类比例'
)
COMMENT 'ADS 品类月度销售排行，含排名与占比，用于品类分析看板'
STORED AS PARQUET;

INSERT OVERWRITE TABLE ads.ads_category_stats
SELECT
    m.month_key,
    m.category_id,
    m.category_name,
    m.order_cnt,
    m.sale_qty,
    m.sale_amount,
    m.discount_amount,
    CASE WHEN m.sale_qty > 0
         THEN CAST(m.sale_amount / m.sale_qty AS DECIMAL(12,2))
         ELSE NULL
    END                                                               AS avg_price,
    CAST(RANK() OVER (PARTITION BY m.month_key ORDER BY m.sale_amount DESC) AS INT) AS amount_rank,
    CASE WHEN SUM(m.sale_amount) OVER (PARTITION BY m.month_key) > 0
         THEN CAST(m.sale_amount / SUM(m.sale_amount) OVER (PARTITION BY m.month_key) AS DECIMAL(6,4))
         ELSE NULL
    END                                                               AS amount_share
FROM (
    SELECT
        SUBSTR(dt, 1, 7)                                              AS month_key,
        category_id,
        category_name,
        SUM(order_cnt)                                                AS order_cnt,
        SUM(sale_qty)                                                 AS sale_qty,
        CAST(SUM(sale_amount) AS DECIMAL(18,2))                       AS sale_amount,
        CAST(SUM(discount_amount) AS DECIMAL(18,2))                   AS discount_amount
    FROM dws.dws_category_daily
    GROUP BY SUBSTR(dt, 1, 7), category_id, category_name
) m;

-- ---------------------------------------------------------------------------
-- ads.ads_user_profile 用户群体画像
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ads.ads_user_profile;
CREATE TABLE ads.ads_user_profile (
    month_key        STRING        COMMENT '统计月份，格式 yyyy-MM',
    member_level     STRING        COMMENT '会员等级：普通/银卡/金卡/钻石',
    age_group        STRING        COMMENT '年龄段',
    gender           STRING        COMMENT '性别：M=男，F=女，U=未知',
    order_user_cnt   BIGINT        COMMENT '下单用户数(去重)',
    order_cnt        BIGINT        COMMENT '有效订单数',
    gmv              DECIMAL(18,2) COMMENT 'GMV(元)',
    avg_order_amount DECIMAL(12,2) COMMENT '客单价(元)',
    orders_per_user  DECIMAL(10,2) COMMENT '人均下单次数 = 订单数 / 下单用户数',
    gmv_per_user     DECIMAL(12,2) COMMENT '人均消费金额 = GMV / 下单用户数(元)'
)
COMMENT 'ADS 用户群体画像月报，按会员等级/年龄段/性别交叉分析'
STORED AS PARQUET;

-- 注意：用户数不能从 dws_user_daily 累加得到（同一用户可能多日下单，
-- SUM(order_user_cnt) 会重复计数），因此这里回到 DWD 明细按月重新去重。
INSERT OVERWRITE TABLE ads.ads_user_profile
SELECT
    t.month_key,
    t.member_level,
    t.age_group,
    t.gender,
    t.order_user_cnt,
    t.order_cnt,
    t.gmv,
    CASE WHEN t.order_cnt > 0
         THEN CAST(t.gmv / t.order_cnt AS DECIMAL(12,2))
         ELSE NULL
    END                                                               AS avg_order_amount,
    CASE WHEN t.order_user_cnt > 0
         THEN CAST(t.order_cnt / t.order_user_cnt AS DECIMAL(10,2))
         ELSE NULL
    END                                                               AS orders_per_user,
    CASE WHEN t.order_user_cnt > 0
         THEN CAST(t.gmv / t.order_user_cnt AS DECIMAL(12,2))
         ELSE NULL
    END                                                               AS gmv_per_user
FROM (
    SELECT
        SUBSTR(o.dt, 1, 7)                                            AS month_key,
        u.member_level,
        u.age_group,
        u.gender,
        COUNT(DISTINCT o.user_id)                                     AS order_user_cnt,
        COUNT(*)                                                      AS order_cnt,
        CAST(COALESCE(SUM(o.pay_amount), 0) AS DECIMAL(18,2))         AS gmv
    FROM dwd.dwd_order o
    JOIN dwd.dwd_user u ON o.user_id = u.user_id
    WHERE o.is_valid_order
    GROUP BY SUBSTR(o.dt, 1, 7), u.member_level, u.age_group, u.gender
) t;

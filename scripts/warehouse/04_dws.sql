-- Aetheris DWS 汇总层加工。
--
-- DWS 约定：按主题轻度聚合，粒度为「维度 + 日期」，不做最终口径固化。
-- 这一层的作用是把 DWD 的行级明细收敛成可复用的宽表，
-- 让 ADS 与后续语义层的指标定义不必反复扫描大表。
--
-- 口径统一说明（后续语义层的指标定义以此为唯一来源）：
--   有效订单 = 状态 IN (2,3,4)，即已付款/已发货/已完成，
--              排除待付款(1)、已取消(5)、已退款(6)与非法状态；
--   GMV      = 有效订单的 pay_amount 之和；
--   客单价    = GMV / 有效订单数。

SET hive.exec.dynamic.partition = true;
SET hive.exec.dynamic.partition.mode = nonstrict;

-- ---------------------------------------------------------------------------
-- dws.dws_order_daily 每日订单汇总（按 dt 分区）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dws.dws_order_daily;
CREATE TABLE dws.dws_order_daily (
    order_cnt        BIGINT        COMMENT '订单总数，含所有状态',
    valid_order_cnt  BIGINT        COMMENT '有效订单数，状态为已付款/已发货/已完成',
    paid_user_cnt    BIGINT        COMMENT '下单用户数(去重)，仅统计有效订单',
    gmv              DECIMAL(18,2) COMMENT 'GMV，有效订单实付金额之和(元)',
    avg_order_amount DECIMAL(12,2) COMMENT '客单价 = GMV / 有效订单数(元)',
    refund_order_cnt BIGINT        COMMENT '退款订单数，状态为已退款',
    refund_amount    DECIMAL(18,2) COMMENT '退款金额之和(元)',
    cancel_order_cnt BIGINT        COMMENT '取消订单数，状态为已取消'
)
COMMENT 'DWS 每日订单汇总，按 dt 分区，粒度为「日」'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE dws.dws_order_daily PARTITION (dt)
SELECT
    COUNT(*)                                                          AS order_cnt,
    COUNT(CASE WHEN is_valid_order THEN 1 END)                        AS valid_order_cnt,
    COUNT(DISTINCT CASE WHEN is_valid_order THEN user_id END)          AS paid_user_cnt,
    CAST(COALESCE(SUM(CASE WHEN is_valid_order THEN pay_amount END), 0) AS DECIMAL(18,2)) AS gmv,
    -- 客单价：有效订单为 0 时置 NULL 而非 0，避免下游把「无数据」误读为「客单价为零」。
    CASE WHEN COUNT(CASE WHEN is_valid_order THEN 1 END) > 0
         THEN CAST(SUM(CASE WHEN is_valid_order THEN pay_amount END)
                   / COUNT(CASE WHEN is_valid_order THEN 1 END) AS DECIMAL(12,2))
         ELSE NULL
    END                                                               AS avg_order_amount,
    COUNT(CASE WHEN order_status = 6 THEN 1 END)                      AS refund_order_cnt,
    CAST(COALESCE(SUM(CASE WHEN order_status = 6 THEN pay_amount END), 0) AS DECIMAL(18,2)) AS refund_amount,
    COUNT(CASE WHEN order_status = 5 THEN 1 END)                      AS cancel_order_cnt,
    dt
FROM dwd.dwd_order
GROUP BY dt;

-- ---------------------------------------------------------------------------
-- dws.dws_category_daily 每日品类汇总（按 dt 分区）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dws.dws_category_daily;
CREATE TABLE dws.dws_category_daily (
    category_id     INT           COMMENT '品类ID',
    category_name   STRING        COMMENT '品类名称，未匹配为「未分类」',
    order_cnt       BIGINT        COMMENT '该品类涉及的订单数(去重)',
    sale_qty        BIGINT        COMMENT '销售件数之和',
    sale_amount     DECIMAL(18,2) COMMENT '销售额，明细净额(扣优惠后)之和(元)',
    discount_amount DECIMAL(18,2) COMMENT '优惠金额之和(元)',
    avg_price       DECIMAL(12,2) COMMENT '件均价 = 销售额 / 销售件数(元)'
)
COMMENT 'DWS 每日品类销售汇总，按 dt 分区，粒度为「品类 + 日」'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE dws.dws_category_daily PARTITION (dt)
SELECT
    i.category_id,
    COALESCE(i.category_name, '未分类')                                AS category_name,
    COUNT(DISTINCT i.order_id)                                        AS order_cnt,
    COALESCE(SUM(i.quantity), 0)                                      AS sale_qty,
    CAST(COALESCE(SUM(i.net_amount), 0) AS DECIMAL(18,2))             AS sale_amount,
    CAST(COALESCE(SUM(i.discount), 0) AS DECIMAL(18,2))               AS discount_amount,
    CASE WHEN COALESCE(SUM(i.quantity), 0) > 0
         THEN CAST(SUM(i.net_amount) / SUM(i.quantity) AS DECIMAL(12,2))
         ELSE NULL
    END                                                               AS avg_price,
    i.dt
FROM dwd.dwd_order_item i
-- 只统计有效订单的明细，与 dws_order_daily 的 GMV 口径保持一致。
JOIN dwd.dwd_order o
    ON i.order_id = o.order_id AND i.dt = o.dt AND o.is_valid_order
GROUP BY i.dt, i.category_id, COALESCE(i.category_name, '未分类');

-- ---------------------------------------------------------------------------
-- dws.dws_user_daily 每日用户维度汇总（按 dt 分区）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dws.dws_user_daily;
CREATE TABLE dws.dws_user_daily (
    member_level     STRING        COMMENT '会员等级：普通/银卡/金卡/钻石',
    age_group        STRING        COMMENT '年龄段：18-24/25-34/35-44/45-54/55+/未知',
    gender           STRING        COMMENT '性别：M=男，F=女，U=未知',
    city             STRING        COMMENT '用户所在城市',
    order_user_cnt   BIGINT        COMMENT '该群体下单用户数(去重)',
    order_cnt        BIGINT        COMMENT '该群体有效订单数',
    gmv              DECIMAL(18,2) COMMENT '该群体 GMV(元)',
    avg_order_amount DECIMAL(12,2) COMMENT '该群体客单价(元)'
)
COMMENT 'DWS 每日用户群体汇总，按 dt 分区，粒度为「会员等级+年龄段+性别+城市+日」'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE dws.dws_user_daily PARTITION (dt)
SELECT
    u.member_level,
    u.age_group,
    u.gender,
    u.city,
    COUNT(DISTINCT o.user_id)                                         AS order_user_cnt,
    COUNT(*)                                                          AS order_cnt,
    CAST(COALESCE(SUM(o.pay_amount), 0) AS DECIMAL(18,2))             AS gmv,
    CASE WHEN COUNT(*) > 0
         THEN CAST(SUM(o.pay_amount) / COUNT(*) AS DECIMAL(12,2))
         ELSE NULL
    END                                                               AS avg_order_amount,
    o.dt
FROM dwd.dwd_order o
JOIN dwd.dwd_user u ON o.user_id = u.user_id
WHERE o.is_valid_order
GROUP BY o.dt, u.member_level, u.age_group, u.gender, u.city;

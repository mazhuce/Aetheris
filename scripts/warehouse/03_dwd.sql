-- Aetheris DWD 明细层加工。
--
-- 清洗规则（这里的每一条都对应 ODS 中刻意注入的一类脏数据，
-- 后续 quality 服务的规则和 DataAgent 的数据质量判断都以此为基准）：
--   1. 去重：ODS 订单含约 0.3% 重复行，按 order_id 取一条；
--   2. 过滤：剔除 is_deleted='1' 的逻辑删除订单；
--   3. 类型转换：STRING 转 INT / DECIMAL / TIMESTAMP，转换失败置 NULL 而非报错；
--   4. 枚举标准化：性别统一为 M/F/U，会员等级非法值归入「普通」；
--   5. 非法值处理：年龄超范围置 NULL，负价格与非正数量置 NULL；
--   6. 时间格式归一：ODS 混用 yyyy-MM-dd 与 yyyy/MM/dd 两种格式。
--
-- 占位符：@ORDER_START_DATE@ @ORDER_DAYS@

SET hive.exec.dynamic.partition = true;
SET hive.exec.dynamic.partition.mode = nonstrict;

-- ---------------------------------------------------------------------------
-- dwd.dwd_user 用户明细
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dwd.dwd_user;
CREATE TABLE dwd.dwd_user (
    user_id       BIGINT    COMMENT '用户ID',
    user_name     STRING    COMMENT '用户名',
    gender        STRING    COMMENT '性别标准值：M=男，F=女，U=未知',
    age           INT       COMMENT '年龄，已剔除超范围值(>=120 或 <=0 置空)',
    age_group     STRING    COMMENT '年龄段：18-24/25-34/35-44/45-54/55+/未知',
    city          STRING    COMMENT '所在城市',
    register_date DATE      COMMENT '注册日期，已统一两种源格式',
    phone_masked  STRING    COMMENT '手机号脱敏值，保留前3后4位',
    email         STRING    COMMENT '邮箱，非法格式(缺少@)已置空',
    member_level  STRING    COMMENT '会员等级：普通/银卡/金卡/钻石，非法值归入普通',
    is_valid_user BOOLEAN   COMMENT '是否有效用户：手机号与邮箱至少有一个可用'
)
COMMENT 'DWD 用户明细，已完成去重、类型转换、枚举标准化与 PII 脱敏'
STORED AS PARQUET;

INSERT OVERWRITE TABLE dwd.dwd_user
SELECT
    CAST(user_id AS BIGINT)                                    AS user_id,
    user_name,
    -- 性别标准化：M/男 -> M，F/女 -> F，其余（含空值与「未知」）-> U。
    CASE
        WHEN gender IN ('M', '男') THEN 'M'
        WHEN gender IN ('F', '女') THEN 'F'
        ELSE 'U'
    END                                                        AS gender,
    age_int                                                    AS age,
    CASE
        WHEN age_int IS NULL           THEN '未知'
        WHEN age_int BETWEEN 18 AND 24 THEN '18-24'
        WHEN age_int BETWEEN 25 AND 34 THEN '25-34'
        WHEN age_int BETWEEN 35 AND 44 THEN '35-44'
        WHEN age_int BETWEEN 45 AND 54 THEN '45-54'
        ELSE '55+'
    END                                                        AS age_group,
    city,
    -- 注册时间两种格式归一：先按标准格式解析，失败再按斜杠格式解析。
    COALESCE(
        TO_DATE(register_time, 'yyyy-MM-dd HH:mm:ss'),
        TO_DATE(register_time, 'yyyy/MM/dd HH:mm:ss')
    )                                                          AS register_date,
    -- PII 脱敏：保留前 3 后 4 位，中间固定掩码。
    CASE WHEN LENGTH(phone) = 11
         THEN CONCAT(SUBSTR(phone, 1, 3), '****', SUBSTR(phone, 8, 4))
         ELSE NULL
    END                                                        AS phone_masked,
    CASE WHEN email LIKE '%@%' THEN email ELSE NULL END        AS email,
    CASE WHEN member_level IN ('普通','银卡','金卡','钻石')
         THEN member_level ELSE '普通'
    END                                                        AS member_level,
    (LENGTH(phone) = 11 OR email LIKE '%@%')                   AS is_valid_user
FROM (
    SELECT
        user_id, user_name, gender, city, register_time, phone, email, member_level,
        -- 年龄：非数字或超出 [1, 119] 视为无效，置 NULL。
        CASE WHEN CAST(age AS INT) BETWEEN 1 AND 119
             THEN CAST(age AS INT) ELSE NULL
        END AS age_int
    FROM ods.ods_user
) t;

-- ---------------------------------------------------------------------------
-- dwd.dwd_product 商品明细
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dwd.dwd_product;
CREATE TABLE dwd.dwd_product (
    product_id    BIGINT        COMMENT '商品ID',
    product_name  STRING        COMMENT '商品名称',
    category_id   INT           COMMENT '品类ID，无效品类(空值或不存在)置空',
    category_name STRING        COMMENT '品类名称，关联 dim_category 得到，未匹配为「未分类」',
    price         DECIMAL(12,2) COMMENT '商品单价(元)，负数已置空',
    cost          DECIMAL(12,2) COMMENT '商品成本(元)',
    gross_margin  DECIMAL(6,4)  COMMENT '毛利率 = (单价-成本)/单价，单价无效时为空',
    brand         STRING        COMMENT '品牌名称',
    is_on_shelf   BOOLEAN       COMMENT '是否在售，空值按下架处理'
)
COMMENT 'DWD 商品明细，已完成类型转换、品类关联与非法价格处理'
STORED AS PARQUET;

INSERT OVERWRITE TABLE dwd.dwd_product
SELECT
    CAST(p.product_id AS BIGINT)                               AS product_id,
    p.product_name,
    c.category_id                                              AS category_id,
    COALESCE(c.category_name, '未分类')                         AS category_name,
    p.price_dec                                                AS price,
    CAST(p.cost AS DECIMAL(12,2))                              AS cost,
    -- 毛利率仅在单价有效且大于 0 时计算。
    CASE WHEN p.price_dec > 0
         THEN CAST((p.price_dec - CAST(p.cost AS DECIMAL(12,2))) / p.price_dec AS DECIMAL(6,4))
         ELSE NULL
    END                                                        AS gross_margin,
    p.brand,
    COALESCE(p.shelf_status = 'on', false)                     AS is_on_shelf
FROM (
    SELECT
        product_id, product_name, category_id, cost, brand, shelf_status,
        -- 单价：负数视为非法，置 NULL。
        CASE WHEN CAST(price AS DECIMAL(12,2)) >= 0
             THEN CAST(price AS DECIMAL(12,2)) ELSE NULL
        END AS price_dec
    FROM ods.ods_product
) p
-- 左连接而非内连接：品类无效的商品要保留，只是 category_name 落为「未分类」，
-- 这样才能在 quality 规则里统计出「品类缺失商品数」这个指标。
LEFT JOIN dim.dim_category c
    ON CAST(p.category_id AS INT) = c.category_id;

-- ---------------------------------------------------------------------------
-- dwd.dwd_order 订单明细（按 dt 分区）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dwd.dwd_order;
CREATE TABLE dwd.dwd_order (
    order_id      STRING        COMMENT '订单ID',
    user_id       BIGINT        COMMENT '下单用户ID',
    order_status  INT           COMMENT '订单状态码：1待付款,2已付款,3已发货,4已完成,5已取消,6已退款',
    status_name   STRING        COMMENT '订单状态中文名，非法状态归入「未知」',
    pay_amount    DECIMAL(12,2) COMMENT '订单实付金额(元)',
    pay_type      STRING        COMMENT '支付方式：alipay/wechat/card/balance',
    order_time    TIMESTAMP     COMMENT '下单时间',
    order_hour    INT           COMMENT '下单小时,0-23，用于时段分析',
    province      STRING        COMMENT '收货省份',
    is_valid_order BOOLEAN      COMMENT '是否有效订单：状态在 2/3/4 之列(已付款/已发货/已完成)，GMV 口径以此为准',
    is_finished   BOOLEAN       COMMENT '是否已完成：状态为 4',
    is_refunded   BOOLEAN       COMMENT '是否已退款：状态为 6'
)
COMMENT 'DWD 订单明细，已按 order_id 去重、过滤逻辑删除、标准化状态枚举'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt)
SELECT
    order_id,
    CAST(user_id AS BIGINT)                                    AS user_id,
    status_int                                                 AS order_status,
    CASE status_int
        WHEN 1 THEN '待付款' WHEN 2 THEN '已付款' WHEN 3 THEN '已发货'
        WHEN 4 THEN '已完成' WHEN 5 THEN '已取消' WHEN 6 THEN '已退款'
        ELSE '未知'
    END                                                        AS status_name,
    CAST(pay_amount AS DECIMAL(12,2))                          AS pay_amount,
    pay_type,
    CAST(order_time AS TIMESTAMP)                              AS order_time,
    HOUR(CAST(order_time AS TIMESTAMP))                        AS order_hour,
    province,
    COALESCE(status_int IN (2, 3, 4), false)                   AS is_valid_order,
    COALESCE(status_int = 4, false)                            AS is_finished,
    COALESCE(status_int = 6, false)                            AS is_refunded,
    dt
FROM (
    SELECT
        order_id, user_id, pay_amount, pay_type, order_time, province, dt,
        -- 只保留合法状态码，非法值(9)与空值置 NULL 后归入「未知」。
        CASE WHEN CAST(order_status AS INT) BETWEEN 1 AND 6
             THEN CAST(order_status AS INT) ELSE NULL
        END AS status_int,
        -- 去重：同一 order_id 保留一条。ODS 的重复行是完全相同的副本，
        -- 因此按 order_id 排序取第一条即可，结果稳定。
        ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY order_time) AS rn
    FROM ods.ods_order
    WHERE is_deleted = '0'
) t
WHERE rn = 1
-- 上面的窗口函数按 order_id 重分布，与分区键 dt 无关，因此写入前需再按 dt
-- 重分布；否则单个 task 会同时持有全部 dt 分区的 Parquet writer，把堆打爆。
DISTRIBUTE BY dt;

-- ---------------------------------------------------------------------------
-- dwd.dwd_order_item 订单明细行（按 dt 分区）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dwd.dwd_order_item;
CREATE TABLE dwd.dwd_order_item (
    item_id       STRING        COMMENT '订单明细ID',
    order_id      STRING        COMMENT '订单ID',
    user_id       BIGINT        COMMENT '下单用户ID，由订单表带入，便于免 join 分析',
    product_id    BIGINT        COMMENT '商品ID',
    category_id   INT           COMMENT '品类ID，由商品表带入',
    category_name STRING        COMMENT '品类名称，由商品表带入',
    brand         STRING        COMMENT '品牌，由商品表带入',
    quantity      INT           COMMENT '购买数量，非正数已置空',
    item_amount   DECIMAL(12,2) COMMENT '该明细行金额(元)',
    discount      DECIMAL(12,2) COMMENT '优惠金额(元)，空值补 0',
    net_amount    DECIMAL(12,2) COMMENT '实收金额 = 明细金额 - 优惠金额',
    province      STRING        COMMENT '收货省份，由订单表带入'
)
COMMENT 'DWD 订单明细行宽表，已关联商品与订单维度，供 DWS 直接聚合'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE dwd.dwd_order_item PARTITION (dt)
SELECT
    i.item_id,
    i.order_id,
    o.user_id,
    CAST(i.product_id AS BIGINT)                               AS product_id,
    p.category_id,
    p.category_name,
    p.brand,
    -- 数量：0 与负数视为非法，置 NULL。
    CASE WHEN CAST(i.quantity AS INT) > 0
         THEN CAST(i.quantity AS INT) ELSE NULL
    END                                                        AS quantity,
    CAST(i.item_amount AS DECIMAL(12,2))                       AS item_amount,
    COALESCE(CAST(i.discount AS DECIMAL(12,2)), 0)             AS discount,
    CAST(i.item_amount AS DECIMAL(12,2))
        - COALESCE(CAST(i.discount AS DECIMAL(12,2)), 0)        AS net_amount,
    o.province,
    i.dt
FROM ods.ods_order_item i
-- 内连接订单：明细必须挂在一个有效订单上，孤儿明细直接丢弃。
JOIN dwd.dwd_order o
    ON i.order_id = o.order_id AND i.dt = o.dt
-- 左连接商品：商品缺失时保留明细，维度落空，便于统计关联缺失率。
LEFT JOIN dwd.dwd_product p
    ON CAST(i.product_id AS BIGINT) = p.product_id
-- join 的重分布键是 order_id / product_id，与分区键 dt 无关，写入前按 dt 重分布。
DISTRIBUTE BY i.dt;

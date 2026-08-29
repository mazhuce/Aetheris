-- Aetheris ODS 贴源层造数。
--
-- 设计要点（这三个特征决定了后续 DataAgent 能否练出真本事）：
--   1. 大事实表按 dt 分区，使分区裁剪成为真实的查询优化问题；
--   2. 刻意注入脏数据（空值、重复、非法枚举、超范围值、格式不一致），
--      让 DWD 清洗与质量规则有实际作用对象；
--   3. 所有字段带中文注释，这是 NL2SQL 准确率最重要的杠杆。
--
-- ODS 约定：字段类型贴合源系统抽取结果，多为 STRING，不做任何清洗。
--
-- 规模占位符由 gen-warehouse-data.sh 用 sed 替换（不用 ${hiveconf:}，
-- 因为 Hive 与 Spark SQL 的变量替换语法不一致）：
--   @USER_ROWS@ @PRODUCT_ROWS@ @ORDER_START_DATE@ @ORDER_DAYS@ @ORDERS_PER_DAY@

SET hive.exec.dynamic.partition = true;
SET hive.exec.dynamic.partition.mode = nonstrict;

-- ---------------------------------------------------------------------------
-- ods.ods_user 用户全量快照
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ods.ods_user;
CREATE TABLE ods.ods_user (
    user_id       STRING COMMENT '用户ID',
    user_name     STRING COMMENT '用户名',
    gender        STRING COMMENT '性别原始值，脏数据：混用 M/F/男/女/未知/空',
    age           STRING COMMENT '年龄原始值，脏数据：含空值与超范围值(999)',
    city          STRING COMMENT '所在城市',
    register_time STRING COMMENT '注册时间，脏数据：混用 yyyy-MM-dd 与 yyyy/MM/dd 两种格式',
    phone         STRING COMMENT '手机号，敏感字段(PII)，脏数据：含空值',
    email         STRING COMMENT '邮箱，敏感字段(PII)，脏数据：含空值与缺少@的非法值',
    member_level  STRING COMMENT '会员等级原始值：普通/银卡/金卡/钻石，脏数据：含非法枚举 UNKNOWN'
)
COMMENT 'ODS 用户全量快照，未做任何清洗，含脏数据'
STORED AS PARQUET;

INSERT OVERWRITE TABLE ods.ods_user
SELECT
    CAST(id + 1 AS STRING)                                AS user_id,
    CONCAT('用户', CAST(id + 1 AS STRING))                 AS user_name,
    -- 性别：约 6% 空值，其余在标准值与非标准写法之间混用。
    CASE PMOD(id, 17)
        WHEN 0 THEN NULL
        WHEN 1 THEN '男'
        WHEN 2 THEN '女'
        WHEN 3 THEN 'M'
        WHEN 4 THEN 'F'
        WHEN 5 THEN '未知'
        ELSE CASE WHEN PMOD(id, 2) = 0 THEN 'M' ELSE 'F' END
    END                                                   AS gender,
    -- 年龄：约 4% 空值，约 2% 超范围值。
    CASE
        WHEN PMOD(id, 23) = 0 THEN NULL
        WHEN PMOD(id, 47) = 0 THEN '999'
        ELSE CAST(18 + PMOD(id, 47) AS STRING)
    END                                                   AS age,
    ELEMENT_AT(ARRAY('北京','上海','广州','深圳','杭州','成都','武汉','西安','南京','重庆',
                     '苏州','天津','长沙','郑州','青岛'),
               CAST(PMOD(id, 15) + 1 AS INT))             AS city,
    -- 注册时间：两种格式混用，模拟多源系统抽取。
    CASE WHEN PMOD(id, 5) = 0
        THEN FROM_UNIXTIME(UNIX_TIMESTAMP('2025-01-01 00:00:00') + PMOD(id * 977, 31536000), 'yyyy/MM/dd HH:mm:ss')
        ELSE FROM_UNIXTIME(UNIX_TIMESTAMP('2025-01-01 00:00:00') + PMOD(id * 977, 31536000), 'yyyy-MM-dd HH:mm:ss')
    END                                                   AS register_time,
    -- 手机号：约 8% 空值。
    CASE WHEN PMOD(id, 13) = 0 THEN NULL
         ELSE CONCAT('138', LPAD(CAST(PMOD(id * 7, 100000000) AS STRING), 8, '0'))
    END                                                   AS phone,
    -- 邮箱：约 5% 空值，约 3% 缺少 @ 的非法值。
    CASE
        WHEN PMOD(id, 19) = 0 THEN NULL
        WHEN PMOD(id, 31) = 0 THEN CONCAT('user', CAST(id AS STRING), '.example.com')
        ELSE CONCAT('user', CAST(id AS STRING), '@example.com')
    END                                                   AS email,
    -- 会员等级：约 3% 为非法枚举值。
    CASE WHEN PMOD(id, 29) = 0 THEN 'UNKNOWN'
         ELSE ELEMENT_AT(ARRAY('普通','银卡','金卡','钻石'), CAST(PMOD(id, 4) + 1 AS INT))
    END                                                   AS member_level
FROM range(0, @USER_ROWS@);

-- ---------------------------------------------------------------------------
-- ods.ods_product 商品全量快照
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ods.ods_product;
CREATE TABLE ods.ods_product (
    product_id   STRING COMMENT '商品ID',
    product_name STRING COMMENT '商品名称',
    category_id  STRING COMMENT '品类ID，关联 dim.dim_category，脏数据：含空值与不存在的品类99',
    price        STRING COMMENT '商品单价(元)，脏数据：含空值与负数',
    cost         STRING COMMENT '商品成本(元)，用于计算毛利',
    brand        STRING COMMENT '品牌名称',
    shelf_status STRING COMMENT '上架状态：on=在售，off=下架，脏数据：含空值'
)
COMMENT 'ODS 商品全量快照，未做任何清洗，含脏数据'
STORED AS PARQUET;

INSERT OVERWRITE TABLE ods.ods_product
SELECT
    CAST(id + 1 AS STRING)                                AS product_id,
    CONCAT('商品', CAST(id + 1 AS STRING))                 AS product_name,
    -- 品类：约 2% 空值，约 1.5% 指向不存在的品类，用于验证关联完整性规则。
    CASE
        WHEN PMOD(id, 53) = 0 THEN NULL
        WHEN PMOD(id, 67) = 0 THEN '99'
        ELSE CAST(PMOD(id, 8) + 1 AS STRING)
    END                                                   AS category_id,
    -- 单价：约 1.4% 空值，约 1% 为负数（非法值）。
    CASE
        WHEN PMOD(id, 71) = 0 THEN NULL
        WHEN PMOD(id, 97) = 0 THEN '-9.90'
        ELSE CAST(ROUND(9.9 + PMOD(id * 13, 499000) / 100.0, 2) AS STRING)
    END                                                   AS price,
    CAST(ROUND((9.9 + PMOD(id * 13, 499000) / 100.0) * 0.62, 2) AS STRING) AS cost,
    ELEMENT_AT(ARRAY('华为','小米','苹果','美的','格力','海尔','安踏','李宁',
                     '优衣库','雅诗兰黛'), CAST(PMOD(id, 10) + 1 AS INT))   AS brand,
    CASE WHEN PMOD(id, 41) = 0 THEN NULL
         WHEN PMOD(id, 7) = 0 THEN 'off'
         ELSE 'on'
    END                                                   AS shelf_status
FROM range(0, @PRODUCT_ROWS@);

-- ---------------------------------------------------------------------------
-- ods.ods_order 订单主表（按 dt 分区）
-- ---------------------------------------------------------------------------
-- 订单量按星期波动（周末约为工作日的 1.6 倍）并带轻微增长趋势，
-- 使「趋势」「环比」类问题有真实规律可循，而不是均匀噪声。
DROP TABLE IF EXISTS ods.ods_order;
CREATE TABLE ods.ods_order (
    order_id     STRING COMMENT '订单ID',
    user_id      STRING COMMENT '下单用户ID，关联 ods_user.user_id',
    order_status STRING COMMENT '订单状态：1=待付款,2=已付款,3=已发货,4=已完成,5=已取消,6=已退款，脏数据：含空值与非法值9',
    pay_amount   STRING COMMENT '订单实付金额(元)，脏数据：含空值',
    pay_type     STRING COMMENT '支付方式：alipay/wechat/card/balance',
    order_time   STRING COMMENT '下单时间，格式 yyyy-MM-dd HH:mm:ss',
    province     STRING COMMENT '收货省份',
    is_deleted   STRING COMMENT '逻辑删除标记：0=正常,1=已删除，DWD 需过滤掉已删除'
)
COMMENT 'ODS 订单主表，按 dt 分区，未做清洗，含脏数据与重复行'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE ods.ods_order PARTITION (dt)
SELECT
    o.order_id,
    o.user_id,
    o.order_status,
    o.pay_amount,
    o.pay_type,
    o.order_time,
    o.province,
    o.is_deleted,
    o.dt
FROM (
    SELECT
        -- 订单号：日期 + 当日序号，保证全局唯一（重复行在下一段单独追加）。
        CONCAT(DATE_FORMAT(d.dt, 'yyyyMMdd'), LPAD(CAST(s.seq AS STRING), 7, '0')) AS order_id,
        CAST(PMOD(s.seq * 2654435761 + UNIX_TIMESTAMP(d.dt), @USER_ROWS@) + 1 AS STRING) AS user_id,
        -- 状态：约 1.2% 空值，约 0.8% 非法值 9；其余按真实业务分布倾斜到已完成。
        CASE
            WHEN PMOD(s.seq, 83) = 0 THEN NULL
            WHEN PMOD(s.seq, 127) = 0 THEN '9'
            ELSE ELEMENT_AT(ARRAY('4','4','4','4','4','2','2','3','3','1','5','6'),
                            CAST(PMOD(s.seq * 7, 12) + 1 AS INT))
        END                                                       AS order_status,
        -- 金额：约 1.1% 空值；金额分布右偏（多数小额，少数大额）。
        CASE WHEN PMOD(s.seq, 89) = 0 THEN NULL
             ELSE CAST(ROUND(19.9 + POWER(PMOD(s.seq * 31, 1000) / 1000.0, 3) * 4800, 2) AS STRING)
        END                                                       AS pay_amount,
        ELEMENT_AT(ARRAY('alipay','alipay','wechat','wechat','wechat','card','balance'),
                   CAST(PMOD(s.seq * 11, 7) + 1 AS INT))          AS pay_type,
        -- 下单时刻集中在 10-22 点，符合电商作息。
        FROM_UNIXTIME(
            UNIX_TIMESTAMP(d.dt) + 36000 + PMOD(s.seq * 5173, 43200),
            'yyyy-MM-dd HH:mm:ss')                                AS order_time,
        ELEMENT_AT(ARRAY('广东','浙江','江苏','北京','上海','山东','四川','河南',
                         '湖北','福建','湖南','陕西'),
                   CAST(PMOD(s.seq * 13, 12) + 1 AS INT))         AS province,
        -- 约 1.5% 逻辑删除。
        CASE WHEN PMOD(s.seq, 67) = 0 THEN '1' ELSE '0' END       AS is_deleted,
        CAST(d.dt AS STRING)                                      AS dt
    FROM (
        SELECT
            DATE_ADD(DATE '@ORDER_START_DATE@', CAST(id AS INT)) AS dt,
            -- 周末 1.6 倍；并叠加随天数缓慢增长的趋势与 ±8% 抖动。
            CAST(@ORDERS_PER_DAY@
                 * (CASE WHEN DAYOFWEEK(DATE_ADD(DATE '@ORDER_START_DATE@', CAST(id AS INT))) IN (1, 7)
                         THEN 1.6 ELSE 1.0 END)
                 * (1.0 + CAST(id AS DOUBLE) / (@ORDER_DAYS@ * 4.0))
                 * (0.92 + PMOD(id * 37, 17) / 100.0)
                 AS INT)                                          AS day_orders
        FROM range(0, @ORDER_DAYS@)
    ) d
    LATERAL VIEW EXPLODE(SEQUENCE(1, d.day_orders)) s AS seq
    -- 约 0.3% 的订单原样重复一次，让 DWD 的 ROW_NUMBER 去重和
    -- quality 的唯一性规则有真实作用对象。在同一次插入里展开副本，
    -- 避免 INSERT INTO 自读自写在 Spark 下的不确定行为。
    LATERAL VIEW EXPLODE(
        SEQUENCE(1, CASE WHEN PMOD(s.seq, 331) = 0 THEN 2 ELSE 1 END)
    ) c AS copy_no
) o
-- 按分区键重分布：动态分区写入时，若不做 shuffle，单个 task 会同时持有
-- 全部 dt 分区的 Parquet writer，每个 writer 各自缓冲一个 row group，
-- 在单机 driver 上会直接把堆打爆。DISTRIBUTE BY 让每个 task 只写少数分区。
DISTRIBUTE BY o.dt;

-- ---------------------------------------------------------------------------
-- ods.ods_order_item 订单明细表（按 dt 分区）
-- ---------------------------------------------------------------------------
-- 每单 1-4 个商品行，由订单主表派生，因此天然与订单量同分布。
DROP TABLE IF EXISTS ods.ods_order_item;
CREATE TABLE ods.ods_order_item (
    item_id      STRING COMMENT '订单明细ID',
    order_id     STRING COMMENT '订单ID，关联 ods_order.order_id',
    product_id   STRING COMMENT '商品ID，关联 ods_product.product_id',
    quantity     STRING COMMENT '购买数量，脏数据：含 0 与负数',
    item_amount  STRING COMMENT '该明细行金额(元)',
    discount     STRING COMMENT '优惠金额(元)，脏数据：含空值'
)
COMMENT 'ODS 订单明细表，按 dt 分区，未做清洗，含脏数据'
PARTITIONED BY (dt STRING COMMENT '数据日期分区，格式 yyyy-MM-dd')
STORED AS PARQUET;

INSERT OVERWRITE TABLE ods.ods_order_item PARTITION (dt)
SELECT
    CONCAT(o.order_id, '-', CAST(i.idx AS STRING))            AS item_id,
    o.order_id,
    CAST(PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) * 7919 + i.idx, @PRODUCT_ROWS@) + 1 AS STRING) AS product_id,
    -- 数量：约 0.7% 为 0，约 0.4% 为负数。
    CASE
        WHEN PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) + i.idx, 149) = 0 THEN '0'
        WHEN PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) + i.idx, 251) = 0 THEN '-1'
        ELSE CAST(PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) + i.idx, 3) + 1 AS STRING)
    END                                                       AS quantity,
    CAST(ROUND(9.9 + PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) * 17 + i.idx * 131, 200000) / 100.0, 2) AS STRING) AS item_amount,
    -- 优惠金额：约 3% 空值，多数订单无优惠。
    CASE
        WHEN PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) + i.idx, 37) = 0 THEN NULL
        WHEN PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) + i.idx, 4) = 0
            THEN CAST(ROUND(PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) * 3 + i.idx, 3000) / 100.0, 2) AS STRING)
        ELSE '0.00'
    END                                                       AS discount,
    o.dt
FROM (
    -- 订单主表含刻意注入的重复行，这里先按 order_id 去重，
    -- 否则明细会连带产生重复 item_id，掩盖掉主表那个更有代表性的重复问题。
    SELECT order_id, dt
    FROM ods.ods_order
    WHERE is_deleted = '0'
    GROUP BY order_id, dt
) o
LATERAL VIEW EXPLODE(
    SEQUENCE(1, CAST(PMOD(CAST(SUBSTR(o.order_id, 9) AS BIGINT) * 3, 4) + 1 AS INT))
) i AS idx
DISTRIBUTE BY o.dt;

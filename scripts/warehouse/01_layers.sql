-- Aetheris 数仓分层：创建五个分层库与公共维度表。
-- 分层约定：
--   ods 贴源层：结构与源系统一致，字段多为 STRING，保留原始脏数据，不做任何清洗。
--   dwd 明细层：去重、类型转换、枚举标准化、过滤非法值后的事实明细。
--   dws 汇总层：按主题轻度聚合的宽表，粒度通常为「维度 + 日期」。
--   ads 应用层：面向报表与指标的最终结果表，口径已固化。
--   dim 维度层：公共维表，供各层关联使用。
-- 大事实表按 dt（数据日期）分区；用户表与商品表为全量快照，不分区。

CREATE DATABASE IF NOT EXISTS ods COMMENT 'ODS 贴源层：与源系统结构一致，保留原始脏数据';
CREATE DATABASE IF NOT EXISTS dwd COMMENT 'DWD 明细层：清洗、去重、标准化后的事实明细';
CREATE DATABASE IF NOT EXISTS dws COMMENT 'DWS 汇总层：按主题轻度聚合的宽表';
CREATE DATABASE IF NOT EXISTS ads COMMENT 'ADS 应用层：面向报表与指标的最终结果表';
CREATE DATABASE IF NOT EXISTS dim COMMENT 'DIM 维度层：缓慢变化维与公共维表';

-- ---------------------------------------------------------------------------
-- dim.dim_category 商品品类维度
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dim.dim_category;
CREATE TABLE dim.dim_category (
    category_id    INT     COMMENT '品类ID',
    category_name  STRING  COMMENT '品类名称',
    category_level INT     COMMENT '品类层级，当前均为一级品类',
    is_virtual     BOOLEAN COMMENT '是否虚拟品类，虚拟品类不参与销售统计'
)
COMMENT '商品品类维度表，一级品类共 8 个'
STORED AS PARQUET;

INSERT OVERWRITE TABLE dim.dim_category VALUES
    (1, '手机数码', 1, false),
    (2, '家用电器', 1, false),
    (3, '服饰鞋包', 1, false),
    (4, '食品生鲜', 1, false),
    (5, '美妆个护', 1, false),
    (6, '家居家装', 1, false),
    (7, '母婴玩具', 1, false),
    (8, '运动户外', 1, false);

-- ---------------------------------------------------------------------------
-- dim.dim_date 日期维度
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS dim.dim_date;
CREATE TABLE dim.dim_date (
    date_key    STRING COMMENT '日期，格式 yyyy-MM-dd',
    year_num    INT    COMMENT '年份',
    month_num   INT    COMMENT '月份，1-12',_
    day_num     INT    COMMENT '日，1-31',
    week_of_year INT   COMMENT '一年中的第几周',
    day_of_week INT    COMMENT '星期几，1=周一，7=周日',
    is_weekend  BOOLEAN COMMENT '是否周末',
    month_key   STRING COMMENT '所属月份，格式 yyyy-MM',
    quarter_num INT    COMMENT '季度，1-4'
)
COMMENT '日期维度表，覆盖订单区间并前后各留出余量'
STORED AS PARQUET;

INSERT OVERWRITE TABLE dim.dim_date
SELECT
    CAST(d AS STRING)                                   AS date_key,
    YEAR(d)                                             AS year_num,
    MONTH(d)                                            AS month_num,
    DAY(d)                                              AS day_num,
    WEEKOFYEAR(d)                                       AS week_of_year,
    -- Spark 的 DAYOFWEEK 以周日为 1，这里换算成 ISO 的周一为 1。
    CASE WHEN DAYOFWEEK(d) = 1 THEN 7 ELSE DAYOFWEEK(d) - 1 END AS day_of_week,
    DAYOFWEEK(d) IN (1, 7)                              AS is_weekend,
    DATE_FORMAT(d, 'yyyy-MM')                           AS month_key,
    QUARTER(d)                                          AS quarter_num
FROM (
    SELECT DATE_ADD(DATE '@DIM_DATE_START@', CAST(id AS INT)) AS d
    FROM range(0, @DIM_DATE_DAYS@)
) t;

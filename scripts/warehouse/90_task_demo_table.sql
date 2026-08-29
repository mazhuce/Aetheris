-- 阶段 2 任务执行验证用的目标表。
--
-- 刻意与已验证的数仓表分开：任务执行会反复覆盖分区，
-- 不应该影响阶段 0/1 已验收的 ods/dwd/dws/ads 数据。
--
-- 先 DROP 再建：这张表只用于验证，允许重建以保证结构与本文件一致。
-- 生产加工表不应这样做，任务执行路径也禁止 DROP（见 SqlWriteGuard）。
DROP TABLE IF EXISTS ads.ads_task_demo;

CREATE TABLE ads.ads_task_demo (
    order_cnt BIGINT        COMMENT '有效订单数',
    gmv       DECIMAL(18,2) COMMENT 'GMV(元)',
    user_cnt  BIGINT        COMMENT '下单用户数(去重)'
)
COMMENT '任务执行验证表，由 workflow 的 SQL 任务写入'
PARTITIONED BY (dt STRING COMMENT '业务日期，对应任务的 bizDate')
STORED AS PARQUET;

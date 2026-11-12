-- 教学数据集：与生产数仓隔离的合成电商订单数据。
-- 刻意保留 6 类脏数据：重复订单、空用户、负数量、非法状态、逻辑删除、零价/未来时间。
-- 该 schema 只允许教学服务写入；学员沙箱通过 CREATE TABLE AS 复制，不直接改这里。

CREATE SCHEMA IF NOT EXISTS teach;

CREATE TABLE IF NOT EXISTS teach.orders_raw (
    order_id     BIGINT        NOT NULL,
    user_id      BIGINT,
    sku_id       BIGINT        NOT NULL,
    category     VARCHAR(32)   NOT NULL,
    quantity     INTEGER       NOT NULL,
    unit_price   NUMERIC(12,2) NOT NULL,
    order_status VARCHAR(16)   NOT NULL,
    is_deleted   BOOLEAN       NOT NULL DEFAULT false,
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL,
    dt           DATE          NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_teach_orders_dt ON teach.orders_raw (dt);

INSERT INTO teach.orders_raw
    (order_id, user_id, sku_id, category, quantity, unit_price, order_status, is_deleted, created_at, updated_at, dt)
SELECT * FROM (VALUES
    (1001, 501, 9001, 'electronics', 2,  199.00, 'paid',      false, TIMESTAMP '2026-08-01 10:00:00', TIMESTAMP '2026-08-01 10:05:00', DATE '2026-08-01'),
    (1001, 501, 9001, 'electronics', 2,  199.00, 'paid',      false, TIMESTAMP '2026-08-01 10:00:00', TIMESTAMP '2026-08-01 10:09:00', DATE '2026-08-01'),
    (1002, 502, 9002, 'books',       1,   59.90, 'shipped',   false, TIMESTAMP '2026-08-01 11:00:00', TIMESTAMP '2026-08-01 11:10:00', DATE '2026-08-01'),
    (1003, 503, 9003, 'home',        3,   25.50, 'completed', false, TIMESTAMP '2026-08-01 12:00:00', TIMESTAMP '2026-08-01 12:30:00', DATE '2026-08-01'),
    (1004, 504, 9001, 'electronics', 1,  899.00, 'paid',      false, TIMESTAMP '2026-08-02 09:00:00', TIMESTAMP '2026-08-02 09:10:00', DATE '2026-08-02'),
    (1005, NULL, 9002, 'books',      1,   45.00, 'paid',      false, TIMESTAMP '2026-08-02 10:00:00', TIMESTAMP '2026-08-02 10:00:00', DATE '2026-08-02'),
    (1006, 506, 9003, 'home',        2,   15.00, 'refunded',  false, TIMESTAMP '2026-08-02 11:00:00', TIMESTAMP '2026-08-02 11:30:00', DATE '2026-08-02'),
    (1007, 507, 9004, 'sports',     -1,  120.00, 'paid',      false, TIMESTAMP '2026-08-03 08:00:00', TIMESTAMP '2026-08-03 08:05:00', DATE '2026-08-03'),
    (1008, 508, 9002, 'books',       4,   30.00, 'completed', false, TIMESTAMP '2026-08-03 09:00:00', TIMESTAMP '2026-08-03 09:20:00', DATE '2026-08-03'),
    (1009, 509, 9001, 'electronics', 1, 1299.00, 'unknown',   false, TIMESTAMP '2026-08-03 10:00:00', TIMESTAMP '2026-08-03 10:00:00', DATE '2026-08-03'),
    (1010, 510, 9003, 'home',        2,   88.00, 'paid',      false, TIMESTAMP '2026-08-04 09:00:00', TIMESTAMP '2026-08-04 09:15:00', DATE '2026-08-04'),
    (1011, 511, 9004, 'sports',      1,  300.00, 'paid',      true,  TIMESTAMP '2026-08-04 10:00:00', TIMESTAMP '2026-08-04 10:30:00', DATE '2026-08-04'),
    (1012, 512, 9002, 'books',       2,   25.00, 'shipped',   false, TIMESTAMP '2026-08-04 11:00:00', TIMESTAMP '2026-08-04 11:10:00', DATE '2026-08-04'),
    (1013, 513, 9001, 'electronics', 1,  499.00, 'paid',      false, TIMESTAMP '2027-01-01 00:00:00', TIMESTAMP '2027-01-01 00:00:00', DATE '2026-08-05'),
    (1014, 514, 9003, 'home',        5,   12.00, 'paid',      false, TIMESTAMP '2026-08-05 09:00:00', TIMESTAMP '2026-08-05 09:05:00', DATE '2026-08-05'),
    (1015, 515, 9004, 'sports',      1,    0.00, 'paid',      false, TIMESTAMP '2026-08-05 10:00:00', TIMESTAMP '2026-08-05 10:00:00', DATE '2026-08-05'),
    (1016, 516, 9002, 'books',       1,   99.00, 'paid',      false, TIMESTAMP '2026-08-06 09:00:00', TIMESTAMP '2026-08-06 09:10:00', DATE '2026-08-06'),
    (1017, 517, 9001, 'electronics', 2,  150.00, 'completed', false, TIMESTAMP '2026-08-06 10:00:00', TIMESTAMP '2026-08-06 10:20:00', DATE '2026-08-06'),
    (1018, 518, 9003, 'home',        1,  210.00, 'paid',      false, TIMESTAMP '2026-08-06 11:00:00', TIMESTAMP '2026-08-06 11:05:00', DATE '2026-08-06'),
    (1019, 519, 9004, 'sports',      3,   45.00, 'shipped',   false, TIMESTAMP '2026-08-07 09:00:00', TIMESTAMP '2026-08-07 09:15:00', DATE '2026-08-07'),
    (1020, 520, 9002, 'books',       1,   35.00, 'paid',      false, TIMESTAMP '2026-08-07 10:00:00', TIMESTAMP '2026-08-07 10:00:00', DATE '2026-08-07'),
    (1021, 521, 9001, 'electronics', 1, 2599.00, 'paid',      false, TIMESTAMP '2026-08-07 11:00:00', TIMESTAMP '2026-08-07 11:05:00', DATE '2026-08-07'),
    (1022, 522, 9003, 'home',        2,   65.00, 'completed', false, TIMESTAMP '2026-08-08 09:00:00', TIMESTAMP '2026-08-08 09:30:00', DATE '2026-08-08'),
    (1023, 523, 9004, 'sports',      1,  780.00, 'paid',      false, TIMESTAMP '2026-08-08 10:00:00', TIMESTAMP '2026-08-08 10:00:00', DATE '2026-08-08'),
    (1024, 524, 9002, 'books',       1,   20.00, 'cancelled', false, TIMESTAMP '2026-08-08 11:00:00', TIMESTAMP '2026-08-08 11:00:00', DATE '2026-08-08'),
    (1025, 525, 9003, 'home',        1,  140.00, 'paid',      false, TIMESTAMP '2026-08-09 09:00:00', TIMESTAMP '2026-08-09 09:10:00', DATE '2026-08-09')
) AS seed(order_id, user_id, sku_id, category, quantity, unit_price, order_status, is_deleted, created_at, updated_at, dt)
WHERE NOT EXISTS (SELECT 1 FROM teach.orders_raw);

-- 教学字典：状态枚举，供信息性查询与后续课程使用。
CREATE TABLE IF NOT EXISTS teach.order_status_dict (
    status_code VARCHAR(16) PRIMARY KEY,
    status_name VARCHAR(32) NOT NULL,
    is_valid    BOOLEAN NOT NULL
);
INSERT INTO teach.order_status_dict (status_code, status_name, is_valid) VALUES
    ('paid', '已支付', true),
    ('shipped', '已发货', true),
    ('completed', '已完成', true),
    ('refunded', '已退款', true),
    ('cancelled', '已取消', true),
    ('unknown', '未知状态', false)
ON CONFLICT (status_code) DO NOTHING;

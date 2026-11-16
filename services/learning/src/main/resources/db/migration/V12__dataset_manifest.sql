-- 学习者样例数据集清单（第一条真实扩展点）。
-- 清单记录版本、行数、内容校验和、许可与 PII 复核状态；任务是消费方。
-- 当前只登记合成教学集；后续真实脱敏样例按同一结构增加新版本，不覆盖旧版本。

CREATE TABLE IF NOT EXISTS learning.dataset_manifest (
    code         VARCHAR(64) PRIMARY KEY,
    version      INT          NOT NULL,
    table_name   VARCHAR(128) NOT NULL,
    row_count    INT          NOT NULL,
    checksum     VARCHAR(64)  NOT NULL,
    license      VARCHAR(64)  NOT NULL,
    source       VARCHAR(128) NOT NULL,
    pii_reviewed BOOLEAN      NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'published',
    note         TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.orders_raw', 1, 'teach.orders_raw', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '合成电商订单脏数据教学集'
FROM teach.orders_raw t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.order_status_dict', 1, 'teach.order_status_dict', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '订单状态字典'
FROM teach.order_status_dict t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.shop_dim', 1, 'teach.shop_dim', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '店铺维表'
FROM teach.shop_dim t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.users', 1, 'teach.users', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '虚构用户与手机号，仅用于教学'
FROM teach.users t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.payments', 1, 'teach.payments', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '支付流水（含失败与重复）'
FROM teach.payments t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.refunds', 1, 'teach.refunds', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '退款流水（一单可多次退款）'
FROM teach.refunds t
ON CONFLICT (code) DO NOTHING;

-- 任务绑定：只有单一数据表的任务才自动带 datasetRef；多表任务留待后续多引用设计。
ALTER TABLE learning.lab ADD COLUMN IF NOT EXISTS dataset_ref VARCHAR(128);

UPDATE learning.lab
SET dataset_ref = (table_refs ->> 0) || '@1'
WHERE jsonb_typeof(table_refs) = 'array'
  AND jsonb_array_length(table_refs) = 1
  AND table_refs ->> 0 IN (
      'teach.orders_raw', 'teach.order_status_dict', 'teach.shop_dim',
      'teach.users', 'teach.payments', 'teach.refunds');
-- 多表任务的样例数据绑定：dataset_refs 保存任务涉及的全部数据集引用。
-- 先补齐治理类教学表的清单，确保所有已发布任务的表都在清单内。

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.metric_dict', 1, 'teach.metric_dict', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '指标口径字典（治理教学）'
FROM teach.metric_dict t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.daily_arrival', 1, 'teach.daily_arrival', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '每日到达数据（治理教学）'
FROM teach.daily_arrival t
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.table_asset', 1, 'teach.table_asset', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '表资产登记（治理教学）'
FROM teach.table_asset t
ON CONFLICT (code) DO NOTHING;

ALTER TABLE learning.lab ADD COLUMN IF NOT EXISTS dataset_refs JSONB;

-- 只有任务引用的全部表都在清单内时才绑定，避免给出不完整的数据来源。
UPDATE learning.lab l
SET dataset_refs = sub.refs
FROM (
    SELECT lab.code,
           jsonb_agg((item.ref) || '@1' ORDER BY item.ord) AS refs
    FROM learning.lab lab
    CROSS JOIN LATERAL jsonb_array_elements_text(lab.table_refs)
        WITH ORDINALITY AS item(ref, ord)
    WHERE jsonb_typeof(lab.table_refs) = 'array'
      AND jsonb_array_length(lab.table_refs) > 0
    GROUP BY lab.code
    HAVING bool_and(item.ref IN (SELECT m.table_name FROM learning.dataset_manifest m))
) sub
WHERE l.code = sub.code;

ALTER TABLE learning.lab DROP COLUMN IF EXISTS dataset_ref;
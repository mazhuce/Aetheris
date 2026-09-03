-- 分析数据集支持两类稳定来源：受控 SQL 与已发布语义指标。
ALTER TABLE bi_dataset
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'sql',
    ADD COLUMN metric_code VARCHAR(64),
    ADD COLUMN metric_query_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE bi_dataset ALTER COLUMN datasource_id DROP NOT NULL;
ALTER TABLE bi_dataset ALTER COLUMN query_sql DROP NOT NULL;
ALTER TABLE bi_dataset ADD CONSTRAINT chk_bi_dataset_source_type CHECK (source_type IN ('sql', 'metric'));
ALTER TABLE bi_dataset ADD CONSTRAINT chk_bi_dataset_source_payload CHECK (
    (source_type = 'sql' AND datasource_id IS NOT NULL AND query_sql IS NOT NULL)
    OR (source_type = 'metric' AND metric_code IS NOT NULL)
);
CREATE INDEX idx_bi_dataset_metric_code ON bi_dataset (metric_code) WHERE metric_code IS NOT NULL;

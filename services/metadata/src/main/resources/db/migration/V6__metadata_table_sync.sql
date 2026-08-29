-- 元数据自动采集：区分手工登记与自动同步，并支持按数据源+库+表定位唯一记录。
ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS datasource_id  BIGINT,
    ADD COLUMN IF NOT EXISTS table_type     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS column_count   INT,
    ADD COLUMN IF NOT EXISTS source         VARCHAR(16) NOT NULL DEFAULT 'manual',
    ADD COLUMN IF NOT EXISTS synced_at      TIMESTAMPTZ;

ALTER TABLE metadata_table
    ADD CONSTRAINT chk_metadata_table_source CHECK (source IN ('manual', 'auto'));

-- 采集按 (数据源, schema, 表名) 做 upsert，schema 为空时用空串参与唯一约束。
UPDATE metadata_table SET schema_name = '' WHERE schema_name IS NULL;

ALTER TABLE metadata_table
    ALTER COLUMN schema_name SET DEFAULT '',
    ALTER COLUMN schema_name SET NOT NULL;

-- 历史手工登记可能存在重复，建唯一索引前保留 id 最小的一条。
DELETE FROM metadata_table t
WHERE EXISTS (
    SELECT 1 FROM metadata_table k
    WHERE k.datasource_name = t.datasource_name
      AND k.schema_name = t.schema_name
      AND k.table_name = t.table_name
      AND k.id < t.id
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_metadata_table_identity
    ON metadata_table (datasource_name, schema_name, table_name);

CREATE INDEX IF NOT EXISTS idx_metadata_table_datasource
    ON metadata_table (datasource_id);

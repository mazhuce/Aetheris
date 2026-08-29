-- 数仓分层元数据：为采集到的表补充层级与业务域，支撑数仓地图与分层筛选。
--
-- layer  取值 ods/dwd/dws/ads/dim/other，采集时按库名推断，可人工修正。
-- domain 业务域（如 electronic_commerce），当前按数据源级别配置，先留空由人工维护。
-- layer_source 记录 layer 的来源：auto=按库名推断，manual=人工指定；
--              人工指定后重复采集不会覆盖，与既有 source 字段的取舍逻辑保持一致。

ALTER TABLE metadata_table
    ADD COLUMN IF NOT EXISTS layer        VARCHAR(16),
    ADD COLUMN IF NOT EXISTS domain       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS layer_source VARCHAR(16) NOT NULL DEFAULT 'auto';

ALTER TABLE metadata_table
    ADD CONSTRAINT chk_metadata_table_layer
        CHECK (layer IS NULL OR layer IN ('ods', 'dwd', 'dws', 'ads', 'dim', 'other'));

ALTER TABLE metadata_table
    ADD CONSTRAINT chk_metadata_table_layer_source
        CHECK (layer_source IN ('auto', 'manual'));

-- 回填既有记录：按 schema 名推断层级。历史数据多为手工登记的示例表，
-- 匹配不上的统一落为 other，避免 layer 为空导致前端筛选出现空洞。
UPDATE metadata_table
SET layer = CASE
        WHEN lower(schema_name) = 'ods' OR lower(schema_name) LIKE 'ods%' THEN 'ods'
        WHEN lower(schema_name) = 'dwd' OR lower(schema_name) LIKE 'dwd%' THEN 'dwd'
        WHEN lower(schema_name) = 'dws' OR lower(schema_name) LIKE 'dws%' THEN 'dws'
        WHEN lower(schema_name) = 'ads' OR lower(schema_name) LIKE 'ads%' THEN 'ads'
        WHEN lower(schema_name) = 'dim' OR lower(schema_name) LIKE 'dim%' THEN 'dim'
        -- 库名推断不出时退回表名前缀，覆盖 ods_orders 这类平铺在 default 库的历史表。
        WHEN lower(table_name) LIKE 'ods%' THEN 'ods'
        WHEN lower(table_name) LIKE 'dwd%' THEN 'dwd'
        WHEN lower(table_name) LIKE 'dws%' THEN 'dws'
        WHEN lower(table_name) LIKE 'ads%' THEN 'ads'
        WHEN lower(table_name) LIKE 'dim%' THEN 'dim'
        ELSE 'other'
    END
WHERE layer IS NULL;

CREATE INDEX IF NOT EXISTS idx_metadata_table_layer  ON metadata_table (layer);
CREATE INDEX IF NOT EXISTS idx_metadata_table_domain ON metadata_table (domain);

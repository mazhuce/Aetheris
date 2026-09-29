-- R5 湖仓表控制面：保存 Iceberg Catalog/Namespace/Table 的受管登记和快照证据。
-- 该表不替代 Iceberg metadata.json；实际提交仍由 Flink/Spark/维护任务完成。
CREATE TABLE lakehouse_table (
    id                   BIGSERIAL PRIMARY KEY,
    catalog_name         VARCHAR(128) NOT NULL,
    namespace_name       VARCHAR(255) NOT NULL,
    table_name            VARCHAR(128) NOT NULL,
    location             VARCHAR(1024),
    format_version       SMALLINT NOT NULL DEFAULT 2 CHECK (format_version IN (1, 2)),
    layer                VARCHAR(16) NOT NULL DEFAULT 'bronze'
        CHECK (layer IN ('bronze', 'silver', 'gold', 'other')),
    owner                VARCHAR(128),
    status               VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'paused', 'deprecated')),
    current_snapshot_id  VARCHAR(128),
    current_schema_version INTEGER NOT NULL DEFAULT 1 CHECK (current_schema_version > 0),
    retention_days       INTEGER NOT NULL DEFAULT 30 CHECK (retention_days > 0),
    properties_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_lakehouse_table_identifier UNIQUE (catalog_name, namespace_name, table_name)
);

CREATE INDEX idx_lakehouse_table_status ON lakehouse_table (status);
CREATE INDEX idx_lakehouse_table_namespace ON lakehouse_table (catalog_name, namespace_name);

CREATE TABLE lakehouse_table_snapshot (
    id                 BIGSERIAL PRIMARY KEY,
    lakehouse_table_id BIGINT NOT NULL REFERENCES lakehouse_table(id) ON DELETE CASCADE,
    snapshot_id        VARCHAR(128) NOT NULL,
    parent_snapshot_id VARCHAR(128),
    operation          VARCHAR(24) NOT NULL DEFAULT 'append'
        CHECK (operation IN ('append', 'overwrite', 'replace', 'compact', 'rewrite', 'rollback')),
    committed_at       TIMESTAMPTZ NOT NULL,
    row_count          BIGINT CHECK (row_count IS NULL OR row_count >= 0),
    file_count         BIGINT CHECK (file_count IS NULL OR file_count >= 0),
    total_bytes        BIGINT CHECK (total_bytes IS NULL OR total_bytes >= 0),
    schema_version     INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    manifest_location  VARCHAR(1024),
    metadata_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by         VARCHAR(128) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (lakehouse_table_id, snapshot_id)
);

CREATE INDEX idx_lakehouse_snapshot_table_time
    ON lakehouse_table_snapshot (lakehouse_table_id, committed_at DESC);

ALTER TABLE lakehouse_table
    ADD CONSTRAINT fk_lakehouse_current_snapshot
    FOREIGN KEY (id, current_snapshot_id)
    REFERENCES lakehouse_table_snapshot (lakehouse_table_id, snapshot_id)
    DEFERRABLE INITIALLY DEFERRED;

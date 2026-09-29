CREATE TABLE lakehouse_maintenance_job (
    id                   BIGSERIAL PRIMARY KEY,
    lakehouse_table_id   BIGINT NOT NULL REFERENCES lakehouse_table(id) ON DELETE RESTRICT,
    operation            VARCHAR(32) NOT NULL
        CHECK (operation IN ('expire_snapshots', 'rewrite_data_files', 'remove_orphan_files', 'storage_estimate', 'time_travel', 'rollback')),
    idempotency_key      VARCHAR(160) NOT NULL,
    requested_snapshot_id VARCHAR(128),
    status               VARCHAR(16) NOT NULL DEFAULT 'requested'
        CHECK (status IN ('requested', 'running', 'succeeded', 'failed', 'cancelled')),
    request_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_json          JSONB,
    requested_by         VARCHAR(128) NOT NULL,
    last_error           VARCHAR(2048),
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_lakehouse_maintenance_idempotency UNIQUE (lakehouse_table_id, operation, idempotency_key)
);

CREATE INDEX idx_lakehouse_maintenance_table_time
    ON lakehouse_maintenance_job (lakehouse_table_id, created_at DESC);
CREATE INDEX idx_lakehouse_maintenance_queue
    ON lakehouse_maintenance_job (status, created_at);

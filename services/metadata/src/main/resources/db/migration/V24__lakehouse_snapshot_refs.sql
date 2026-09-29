CREATE TABLE lakehouse_snapshot_ref (
    id                   BIGSERIAL PRIMARY KEY,
    lakehouse_table_id   BIGINT NOT NULL REFERENCES lakehouse_table(id) ON DELETE CASCADE,
    ref_name             VARCHAR(128) NOT NULL,
    ref_type             VARCHAR(16) NOT NULL CHECK (ref_type IN ('branch', 'tag')),
    snapshot_id          VARCHAR(128) NOT NULL,
    protected            BOOLEAN NOT NULL DEFAULT FALSE,
    created_by           VARCHAR(128) NOT NULL,
    updated_by           VARCHAR(128) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_lakehouse_snapshot_ref UNIQUE (lakehouse_table_id, ref_type, ref_name)
);

CREATE INDEX idx_lakehouse_snapshot_ref_table
    ON lakehouse_snapshot_ref (lakehouse_table_id, ref_type, ref_name);

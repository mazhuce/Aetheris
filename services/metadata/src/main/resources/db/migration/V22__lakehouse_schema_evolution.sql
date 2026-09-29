CREATE TABLE lakehouse_schema_change (
    id                   BIGSERIAL PRIMARY KEY,
    lakehouse_table_id   BIGINT NOT NULL REFERENCES lakehouse_table(id) ON DELETE RESTRICT,
    operation            VARCHAR(24) NOT NULL
        CHECK (operation IN ('add_column', 'rename_column', 'drop_column', 'widen_type', 'narrow_type', 'comment')),
    column_name          VARCHAR(128) NOT NULL,
    new_column_name      VARCHAR(128),
    from_type            VARCHAR(128),
    to_type              VARCHAR(128),
    decision             VARCHAR(24) NOT NULL
        CHECK (decision IN ('auto_apply', 'approval_required', 'isolate')),
    status               VARCHAR(16) NOT NULL DEFAULT 'proposed'
        CHECK (status IN ('proposed', 'approved', 'rejected', 'isolated', 'applied')),
    reason               VARCHAR(1024),
    requested_by         VARCHAR(128) NOT NULL,
    reviewed_by          VARCHAR(128),
    reviewed_at          TIMESTAMPTZ,
    applied_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lakehouse_schema_change_table
    ON lakehouse_schema_change (lakehouse_table_id, created_at DESC);
CREATE INDEX idx_lakehouse_schema_change_status
    ON lakehouse_schema_change (status, decision);

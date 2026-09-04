CREATE TABLE semantic_query_audit (
    id BIGSERIAL PRIMARY KEY,
    query_kind VARCHAR(32) NOT NULL,
    asset_code VARCHAR(128),
    asset_id BIGINT,
    datasource_id BIGINT,
    username VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    row_count BIGINT,
    duration_ms BIGINT,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    error_message VARCHAR(2000),
    query_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_semantic_query_audit_user_time ON semantic_query_audit(username, created_at DESC);
CREATE INDEX idx_semantic_query_audit_asset_time ON semantic_query_audit(asset_code, created_at DESC);
CREATE INDEX idx_semantic_query_audit_status_time ON semantic_query_audit(status, created_at DESC);

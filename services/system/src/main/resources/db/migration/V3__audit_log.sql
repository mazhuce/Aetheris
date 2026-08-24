CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    user_role   VARCHAR(16)  NOT NULL,
    method      VARCHAR(12)  NOT NULL,
    path        VARCHAR(512) NOT NULL,
    status_code INT          NOT NULL,
    duration_ms BIGINT       NOT NULL,
    client_ip   VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at
    ON audit_log (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_username
    ON audit_log (username, created_at DESC);

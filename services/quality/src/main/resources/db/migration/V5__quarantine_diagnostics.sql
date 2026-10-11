ALTER TABLE quality_check_result
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(64);

CREATE TABLE IF NOT EXISTS quality_quarantine_record (
    id              BIGSERIAL PRIMARY KEY,
    result_id       BIGINT NOT NULL REFERENCES quality_check_result(id) ON DELETE CASCADE,
    rule_id         BIGINT NOT NULL REFERENCES quality_rule(id) ON DELETE CASCADE,
    target_table    VARCHAR(256) NOT NULL,
    violation_count BIGINT NOT NULL,
    threshold_value BIGINT NOT NULL,
    reason          VARCHAR(512),
    status          VARCHAR(16) NOT NULL DEFAULT 'open',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at     TIMESTAMPTZ,
    released_by     VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_quality_quarantine_status_created
    ON quality_quarantine_record(status, created_at DESC);

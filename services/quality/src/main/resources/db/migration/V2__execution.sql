ALTER TABLE quality_rule
    ADD COLUMN IF NOT EXISTS datasource_id BIGINT,
    ADD COLUMN IF NOT EXISTS last_check_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS last_violation_count BIGINT,
    ADD COLUMN IF NOT EXISTS last_checked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(512);

CREATE TABLE IF NOT EXISTS quality_check_result (
    id              BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT NOT NULL REFERENCES quality_rule(id) ON DELETE CASCADE,
    status          VARCHAR(16) NOT NULL,
    violation_count BIGINT,
    threshold_value BIGINT,
    duration_ms     BIGINT,
    message         VARCHAR(512),
    triggered_by    VARCHAR(128),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_quality_result_rule_started
    ON quality_check_result(rule_id, started_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_quality_result_active_rule
    ON quality_check_result(rule_id) WHERE status = 'running';

CREATE TABLE lakehouse_quality_observation (
    id                   BIGSERIAL PRIMARY KEY,
    lakehouse_table_id   BIGINT NOT NULL REFERENCES lakehouse_table(id) ON DELETE CASCADE,
    layer                VARCHAR(16) NOT NULL CHECK (layer IN ('bronze', 'silver', 'gold', 'other')),
    rule_key             VARCHAR(160) NOT NULL,
    status               VARCHAR(16) NOT NULL CHECK (status IN ('passed', 'failed', 'unknown')),
    violation_count      BIGINT NOT NULL DEFAULT 0 CHECK (violation_count >= 0),
    late_data_count      BIGINT NOT NULL DEFAULT 0 CHECK (late_data_count >= 0),
    dead_letter_count    BIGINT NOT NULL DEFAULT 0 CHECK (dead_letter_count >= 0),
    watermark_at         TIMESTAMPTZ,
    blocks_gold          BOOLEAN NOT NULL DEFAULT TRUE,
    run_id               VARCHAR(160),
    details_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    observed_at          TIMESTAMPTZ NOT NULL,
    observed_by          VARCHAR(128) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lakehouse_quality_latest
    ON lakehouse_quality_observation (lakehouse_table_id, layer, rule_key, observed_at DESC);

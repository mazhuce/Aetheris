CREATE TABLE IF NOT EXISTS serving.service_api_usage (
    id              BIGSERIAL PRIMARY KEY,
    asset_code      VARCHAR(64) NOT NULL,
    principal_hash  VARCHAR(64) NOT NULL,
    auth_type       VARCHAR(16) NOT NULL CHECK (auth_type IN ('identity','api_key')),
    status_code     SMALLINT NOT NULL CHECK (status_code BETWEEN 100 AND 599),
    duration_ms     INTEGER NOT NULL CHECK (duration_ms >= 0),
    error_code      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_service_api_usage_asset_time
    ON serving.service_api_usage (asset_code, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_service_api_usage_time
    ON serving.service_api_usage (created_at DESC);

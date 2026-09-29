CREATE TABLE IF NOT EXISTS serving.service_api_rate_window (
    subject_hash  VARCHAR(64) NOT NULL,
    asset_code    VARCHAR(64) NOT NULL,
    window_start  TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (subject_hash, asset_code)
);

CREATE INDEX IF NOT EXISTS idx_service_api_rate_window_updated
    ON serving.service_api_rate_window (updated_at);

CREATE TABLE IF NOT EXISTS serving.service_api_key (
    id                  BIGSERIAL PRIMARY KEY,
    key_prefix          VARCHAR(16) NOT NULL UNIQUE,
    key_hash            VARCHAR(64) NOT NULL,
    key_name            VARCHAR(128) NOT NULL,
    owner_username      VARCHAR(128) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','revoked')),
    quota_per_minute    INTEGER NOT NULL DEFAULT 60 CHECK (quota_per_minute BETWEEN 1 AND 10000),
    quota_window_start  TIMESTAMPTZ,
    quota_used          INTEGER NOT NULL DEFAULT 0 CHECK (quota_used >= 0),
    expires_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    created_by          VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_service_api_key_lookup
    ON serving.service_api_key (key_prefix, status);

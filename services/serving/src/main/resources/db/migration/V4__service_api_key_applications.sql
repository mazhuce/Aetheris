CREATE TABLE IF NOT EXISTS serving.service_api_key_application (
    id               BIGSERIAL PRIMARY KEY,
    application_name VARCHAR(128) NOT NULL,
    requester        VARCHAR(128) NOT NULL,
    justification    VARCHAR(1000) NOT NULL,
    quota_per_minute INTEGER NOT NULL DEFAULT 60 CHECK (quota_per_minute BETWEEN 1 AND 10000),
    expires_at       TIMESTAMPTZ,
    status           VARCHAR(16) NOT NULL DEFAULT 'requested' CHECK (status IN ('requested','approved','rejected')),
    reviewed_by      VARCHAR(64),
    reviewed_at      TIMESTAMPTZ,
    review_comment   VARCHAR(1000),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_service_api_key_application_requester
    ON serving.service_api_key_application (requester, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_service_api_key_application_status
    ON serving.service_api_key_application (status, created_at ASC);

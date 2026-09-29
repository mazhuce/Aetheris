CREATE TABLE IF NOT EXISTS serving.service_api_sla_policy (
    asset_code           VARCHAR(64) PRIMARY KEY,
    availability_target  NUMERIC(7,6) NOT NULL CHECK (availability_target >= 0.95 AND availability_target <= 0.999999),
    p95_target_ms        INTEGER NOT NULL CHECK (p95_target_ms BETWEEN 1 AND 300000),
    window_days          INTEGER NOT NULL DEFAULT 30 CHECK (window_days BETWEEN 1 AND 365),
    status               VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','paused')),
    created_by           VARCHAR(64) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           VARCHAR(64) NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

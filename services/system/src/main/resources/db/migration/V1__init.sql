CREATE TABLE IF NOT EXISTS system_config (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL UNIQUE,
    config_value TEXT         NOT NULL,
    description  VARCHAR(512),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO system_config (config_key, config_value, description)
VALUES
    ('platform.name', 'Aetheris', '平台名称'),
    ('platform.version', '0.1.0', '平台版本'),
    ('platform.environment', 'local', '运行环境')
ON CONFLICT (config_key) DO NOTHING;

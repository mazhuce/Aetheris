ALTER TABLE data_source
    ADD COLUMN IF NOT EXISTS managed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS jdbc_url TEXT,
    ADD COLUMN IF NOT EXISTS connection_params TEXT,
    ADD COLUMN IF NOT EXISTS description VARCHAR(512);

UPDATE data_source
SET managed = true,
    description = 'Aetheris 平台内部 PostgreSQL，由系统自动维护连接配置'
WHERE name = '本地 PostgreSQL'
  AND type = 'postgresql';

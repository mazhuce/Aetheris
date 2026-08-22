CREATE TABLE IF NOT EXISTS data_source (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    host          VARCHAR(255) NOT NULL,
    port          INT,
    database_name VARCHAR(128),
    username      VARCHAR(128),
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO data_source (name, type, host, port, database_name, username, status)
VALUES
    ('本地 PostgreSQL', 'postgresql', 'postgres', 5432, 'aetheris', 'aetheris', 'active'),
    ('示例 MySQL', 'mysql', '127.0.0.1', 3306, 'demo', 'root', 'inactive')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS metadata_table (
    id              BIGSERIAL PRIMARY KEY,
    datasource_name VARCHAR(128) NOT NULL,
    schema_name     VARCHAR(128),
    table_name      VARCHAR(128) NOT NULL,
    table_comment   VARCHAR(512),
    owner           VARCHAR(64),
    row_count       BIGINT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO metadata_table (datasource_name, schema_name, table_name, table_comment, owner, row_count)
VALUES
    ('本地 PostgreSQL', 'public', 'orders', '订单表', 'admin', 128000),
    ('本地 PostgreSQL', 'public', 'users', '用户表', 'admin', 8600)
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE,
    nickname   VARCHAR(64),
    email      VARCHAR(128),
    phone      VARCHAR(32),
    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO sys_user (username, nickname, email, phone, status)
VALUES
    ('admin', '系统管理员', 'admin@aetheris.local', '13800000000', 'active'),
    ('analyst', '数据分析师', 'analyst@aetheris.local', '13900000000', 'active')
ON CONFLICT (username) DO NOTHING;

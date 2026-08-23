CREATE TABLE IF NOT EXISTS quality_rule (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    rule_type    VARCHAR(32)  NOT NULL,
    target_table VARCHAR(128) NOT NULL,
    expression   TEXT,
    threshold    INT,
    severity     VARCHAR(16)  NOT NULL DEFAULT 'medium',
    status       VARCHAR(16)  NOT NULL DEFAULT 'enabled',
    description  VARCHAR(512),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO quality_rule (name, rule_type, target_table, expression, threshold, severity, status, description)
VALUES
    ('订单表空值检查', 'not_null', 'orders', 'order_id IS NOT NULL', NULL, 'high', 'enabled', '校验订单号非空'),
    ('用户表重复检查', 'duplicate', 'users', 'username', 0, 'medium', 'enabled', '校验用户名唯一')
ON CONFLICT DO NOTHING;

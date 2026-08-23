CREATE TABLE IF NOT EXISTS governance_policy (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    policy_type     VARCHAR(32)  NOT NULL,
    target_table    VARCHAR(128) NOT NULL,
    rule_expression TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'enabled',
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO governance_policy (name, policy_type, target_table, rule_expression, status, description)
VALUES
    ('订单手机号脱敏', 'masking', 'ods_orders', 'phone', 'enabled', '对手机号字段做脱敏处理'),
    ('用户表高敏感分级', 'classification', 'users', 'L3', 'enabled', '标记为高敏感级别')
ON CONFLICT DO NOTHING;

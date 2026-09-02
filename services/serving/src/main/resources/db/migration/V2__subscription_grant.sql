CREATE TABLE IF NOT EXISTS data_subscription (
    id                BIGSERIAL PRIMARY KEY,
    subscription_name VARCHAR(128) NOT NULL,
    subscriber        VARCHAR(128) NOT NULL,
    target_type       VARCHAR(16) NOT NULL DEFAULT 'table' CHECK (target_type IN ('table','api','product','dataset')),
    target_name       VARCHAR(256) NOT NULL,
    deliver_type      VARCHAR(16) NOT NULL DEFAULT 'api' CHECK (deliver_type IN ('api','file','stream','email')),
    frequency         VARCHAR(32),
    config_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','paused','cancelled')),
    created_by        VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS service_grant (
    id          BIGSERIAL PRIMARY KEY,
    api_path    VARCHAR(256) NOT NULL,
    grantee     VARCHAR(128) NOT NULL,
    grant_type  VARCHAR(16) NOT NULL DEFAULT 'user' CHECK (grant_type IN ('user','role')),
    scope       VARCHAR(16) NOT NULL DEFAULT 'read' CHECK (scope IN ('read','write')),
    expires_at  TIMESTAMPTZ,
    status      VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','revoked')),
    granted_by  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO data_subscription
    (subscription_name, subscriber, target_type, target_name, deliver_type, frequency, config_json, status, created_by)
VALUES
('核心交易明细日推送', '数据科学团队', 'table', 'dwd.orders_detail', 'api', '每日 08:00',
 '{"format":"json","channel":"/push/orders-daily"}'::jsonb, 'active', 'admin'),
('实时风控特征流', '风控引擎组', 'dataset', 'risk_feature_dataset', 'stream', '实时',
 '{"format":"avro","topic":"risk-features"}'::jsonb, 'active', 'admin'),
('经营月报邮件订阅', '管理层办公室', 'product', 'monthly_business_report', 'email', '每月 1 日 09:00',
 '{"format":"pdf","recipients":["exec@aetheris.io"]}'::jsonb, 'paused', 'admin');

INSERT INTO service_grant
    (api_path, grantee, grant_type, scope, expires_at, status, granted_by)
VALUES
('/api/serving/datasets/{id}/query', '数据分析师', 'role', 'read', now() + INTERVAL '180 days', 'active', 'admin'),
('/api/serving/dashboards', '张伟', 'user', 'write', now() + INTERVAL '90 days', 'active', 'admin'),
('/api/metadata/tables', '外部合作伙伴', 'role', 'read', now() + INTERVAL '30 days', 'revoked', 'admin');

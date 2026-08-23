CREATE TABLE IF NOT EXISTS ingestion_task (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    source_type    VARCHAR(32)  NOT NULL,
    target_table   VARCHAR(128) NOT NULL,
    schedule_cron  VARCHAR(64),
    source_config  TEXT,
    status         VARCHAR(16)  NOT NULL DEFAULT 'enabled',
    last_run_status VARCHAR(16),
    last_run_at    TIMESTAMPTZ,
    description    VARCHAR(512),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO ingestion_task (name, source_type, target_table, schedule_cron, status, description)
VALUES
    ('订单数据每日同步', 'mysql', 'ods_orders', '0 0 2 * * ?', 'enabled', '每日凌晨同步订单表'),
    ('用户日志实时接入', 'api', 'ods_user_log', NULL, 'enabled', '通过 API 实时写入日志')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS lineage_relation (
    id            BIGSERIAL PRIMARY KEY,
    source_table  VARCHAR(128) NOT NULL,
    target_table  VARCHAR(128) NOT NULL,
    relation_type VARCHAR(32),
    description   VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO lineage_relation (source_table, target_table, relation_type, description)
VALUES
    ('ods_orders', 'dwd_orders', 'etl', '订单明细加工'),
    ('dwd_orders', 'ads_order_stats', 'aggregate', '订单统计汇总')
ON CONFLICT DO NOTHING;

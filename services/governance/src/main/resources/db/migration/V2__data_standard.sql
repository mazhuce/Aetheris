CREATE TABLE IF NOT EXISTS data_standard (
    id              BIGSERIAL PRIMARY KEY,
    standard_code   VARCHAR(64)  NOT NULL UNIQUE,
    standard_name   VARCHAR(128) NOT NULL,
    standard_type   VARCHAR(32)  NOT NULL DEFAULT 'naming' CHECK (standard_type IN ('naming','encoding','value','format')),
    apply_scope     VARCHAR(128),
    definition      VARCHAR(512),
    rule_expression TEXT,
    reference_doc   VARCHAR(256),
    status          VARCHAR(16)  NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','deprecated')),
    version         INTEGER      NOT NULL DEFAULT 1,
    owner           VARCHAR(64),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO data_standard (standard_code, standard_name, standard_type, apply_scope, definition, rule_expression, reference_doc, status, version, owner, created_by)
VALUES
    ('DS-NAME-001', '字段命名规范-下划线小写', 'naming', '全平台', '所有表字段统一使用小写字母加下划线命名，禁止大写、驼峰与特殊字符', '^[a-z][a-z0-9_]{0,62}$', NULL, 'published', 1, '数据治理组', '数据治理组'),
    ('DS-ENC-001', '性别编码标准 GB/T 2261.1', 'encoding', '全平台', '性别代码采用国家标准 GB/T 2261.1：0 未知、1 男、2 女、9 未说明', '^(0|1|2|9)$', 'https://openstd.samr.gov.cn/bzgk/gb/newGbInfo?hcno=E9D949A3C6D2E1D0B4C7ABDBB5CBF2E5', 'published', 1, '数据治理组', '数据治理组'),
    ('DS-FMT-001', '日期格式标准 YYYY-MM-DD', 'format', 'ods 层', '日期字段统一使用 ISO 8601 的 YYYY-MM-DD 格式，时间字段使用 YYYY-MM-DD HH24:MI:SS', '^\\d{4}-\\d{2}-\\d{2}$', NULL, 'published', 2, '数据仓库组', '数据仓库组'),
    ('DS-VAL-001', '订单状态取值标准', 'value', 'dwd 层', '订单状态仅允许以下枚举值：pending 已创建、paid 已支付、shipped 已发货、completed 已完成、cancelled 已取消', '^(pending|paid|shipped|completed|cancelled)$', NULL, 'draft', 1, '业务中台组', '业务中台组')
ON CONFLICT DO NOTHING;

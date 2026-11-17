-- 治理/运维任务第二批改造：新增最小血缘教学表，并恢复 4 个数据驱动任务。
CREATE TABLE IF NOT EXISTS teach.lineage_edge (
    upstream_table   VARCHAR(64) NOT NULL,
    downstream_table VARCHAR(64) NOT NULL,
    read_mode        VARCHAR(16) NOT NULL,
    owner            VARCHAR(32) NOT NULL,
    PRIMARY KEY (upstream_table, downstream_table)
);

INSERT INTO teach.lineage_edge (upstream_table, downstream_table, read_mode, owner) VALUES
    ('ods_orders_raw', 'dwd_order_valid', 'incremental', '交易组'),
    ('dwd_order_valid', 'dws_metric_snapshot', 'incremental', '数据组'),
    ('dws_metric_snapshot', 'ads_gmv_daily', 'full', '交易组'),
    ('ods_payments', 'dwd_payment_valid', 'incremental', '交易组'),
    ('dwd_payment_valid', 'dws_metric_snapshot', 'incremental', '数据组')
ON CONFLICT (upstream_table, downstream_table) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.lineage_edge', 1, 'teach.lineage_edge', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '数仓血缘边（治理教学）'
FROM teach.lineage_edge t
ON CONFLICT (code, version) DO NOTHING;

UPDATE learning.lab
SET objective = '输出 ods_orders_raw 的直接下游表与读取方式，按下游表名升序。',
    status = 'published',
    expected_sql = $q$SELECT downstream_table, read_mode
FROM lineage_edge
WHERE upstream_table = 'ods_orders_raw'
ORDER BY downstream_table;$q$,
    starter_sql = $q$SELECT * FROM lineage_edge LIMIT 20;$q$,
    table_refs = '["teach.lineage_edge"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["downstream_table","read_mode"]}'::jsonb,
    steps = $j$[{"title":"查看血缘边","detail":"查询 lineage_edge，确认字段含义。"},{"title":"过滤直接下游","detail":"只保留 upstream_table = ''ods_orders_raw'' 的行。"},{"title":"排序提交","detail":"按下游表名升序输出，核对行数后提交。"}]$j$,
    dataset_refs = '["teach.lineage_edge@1"]'::jsonb,
    updated_at = now()
WHERE code = 'dg-l2-02';

UPDATE learning.lab
SET objective = '输出 dws_metric_snapshot 的直接上游表与读取方式，按上游表名升序。',
    status = 'published',
    expected_sql = $q$SELECT upstream_table, read_mode
FROM lineage_edge
WHERE downstream_table = 'dws_metric_snapshot'
ORDER BY upstream_table;$q$,
    starter_sql = $q$SELECT * FROM lineage_edge LIMIT 20;$q$,
    table_refs = '["teach.lineage_edge"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["upstream_table","read_mode"]}'::jsonb,
    steps = $j$[{"title":"锁定下游表","detail":"以 dws_metric_snapshot 为下游，反查上游。"},{"title":"过滤上游","detail":"只保留 downstream_table = ''dws_metric_snapshot'' 的行。"},{"title":"排序提交","detail":"按上游表名升序输出并提交。"}]$j$,
    dataset_refs = '["teach.lineage_edge@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l2-02';

UPDATE learning.lab
SET title = '到数监控：延迟与缺口',
    objective = '列出延迟到达或行数不足的日期：日期、到达行数、期望行数、行数差、延迟分钟，按日期升序。',
    status = 'published',
    expected_sql = $q$SELECT dt, rows_arrived, expected_rows,
       rows_arrived - expected_rows AS row_diff,
       ROUND(EXTRACT(EPOCH FROM (arrived_at - expected_at)) / 60) AS late_minutes
FROM daily_arrival
WHERE arrived_at > expected_at OR rows_arrived < expected_rows
ORDER BY dt;$q$,
    starter_sql = $q$SELECT dt, rows_arrived, expected_rows FROM daily_arrival ORDER BY dt;$q$,
    table_refs = '["teach.daily_arrival"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["row_diff","late_minutes"]}'::jsonb,
    steps = $j$[{"title":"看懂到达表","detail":"确认 arrived_at、expected_at、行数字段含义。"},{"title":"组合异常条件","detail":"延迟或行数不足任一条件满足都要列出。"},{"title":"计算差值","detail":"输出行数差与延迟分钟，按日期升序提交。"}]$j$,
    dataset_refs = '["teach.daily_arrival@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l2-03';

UPDATE learning.lab
SET title = '发布前复核：指标字典巡检',
    objective = '列出待复核指标（非 active 或 2026-08-01 前复核）：编码、名称、责任人、状态、复核日期，按复核日期、编码升序。',
    status = 'published',
    expected_sql = $q$SELECT metric_code, metric_name, owner, status, last_reviewed
FROM metric_dict
WHERE status <> 'active' OR last_reviewed < DATE '2026-08-01'
ORDER BY last_reviewed, metric_code;$q$,
    starter_sql = $q$SELECT metric_code, status, last_reviewed FROM metric_dict ORDER BY metric_code;$q$,
    table_refs = '["teach.metric_dict"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["status","last_reviewed"]}'::jsonb,
    steps = $j$[{"title":"查看指标字典","detail":"确认状态与最近复核日期字段。"},{"title":"组合复核条件","detail":"非 active 或复核日期早于 2026-08-01 都要列出。"},{"title":"排序提交","detail":"按复核日期、编码升序输出并提交。"}]$j$,
    dataset_refs = '["teach.metric_dict@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l3-01';

-- 运维路径已有可判分任务，恢复发布。
UPDATE learning.learning_path SET status = 'published' WHERE code = 'ops-basic';

-- 运维任务收尾改造：新增任务定义、验收门、运行日志、查询审计四张教学表，
-- 把最后 4 个固定文案任务改为数据驱动并恢复发布。
CREATE TABLE IF NOT EXISTS teach.job_definition (
    job_code        VARCHAR(64) NOT NULL PRIMARY KEY,
    job_name        VARCHAR(120) NOT NULL,
    script_ref      VARCHAR(120) NOT NULL,
    output_table    VARCHAR(64) NOT NULL,
    partition_col   VARCHAR(32) NOT NULL,
    schedule_cron   VARCHAR(32) NOT NULL,
    timeout_minutes INTEGER,
    upstream_deps   VARCHAR(200),
    retry_times     INTEGER NOT NULL DEFAULT 0,
    owner           VARCHAR(32) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO teach.job_definition (job_code, job_name, script_ref, output_table, partition_col, schedule_cron, timeout_minutes, upstream_deps, retry_times, owner, enabled) VALUES
    ('dwd_order_detail_daily', 'DWD 订单明细日更', 'sql/dwd_order_detail.sql', 'dwd_order_detail', 'dt', '10 2 * * *', 60, 'ods_orders_raw', 3, '交易组', TRUE),
    ('dws_metric_snapshot_1d', 'DWS 指标快照日更', 'sql/dws_metric.sql', 'dws_metric_snapshot', 'dt', '30 2 * * *', 45, 'dwd_order_valid', 2, '数据组', TRUE),
    ('ads_gmv_daily', 'ADS GMV 日报', 'sql/ads_gmv.sql', 'ads_gmv_daily', 'dt', '0 3 * * *', NULL, 'dws_metric_snapshot', 1, '交易组', TRUE),
    ('dwd_payment_valid_daily', 'DWD 支付明细日更', 'sql/dwd_payment.sql', 'dwd_payment_valid', 'dt', '20 2 * * *', 30, NULL, 0, '财务组', TRUE),
    ('ods_users_snapshot', '用户快照装载', 'sql/ods_users.sql', 'users', 'none', '0 1 * * *', 90, 'ods_users_cdc', 3, '用户组', TRUE),
    ('ads_channel_daily', '渠道转化日报', 'sql/ads_channel.sql', 'ads_channel_daily', 'dt', '0 4 * * *', NULL, 'dws_metric_snapshot', 2, '增长组', FALSE)
ON CONFLICT (job_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS teach.acceptance_gate (
    gate_code   VARCHAR(16) NOT NULL PRIMARY KEY,
    gate_name   VARCHAR(120) NOT NULL,
    owner       VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    evidence    VARCHAR(200) NOT NULL,
    checked_at  TIMESTAMP NOT NULL
);

INSERT INTO teach.acceptance_gate (gate_code, gate_name, owner, status, evidence, checked_at) VALUES
    ('R1', '跨引擎读写一致', '交易组', 'passed', 'DuckDB 与 PostgreSQL 双引擎查询结果一致', TIMESTAMP '2026-11-16 10:00:00'),
    ('R2', '空环境重建', '数据组', 'passed', '迁移全量重建后数据完整', TIMESTAMP '2026-11-16 10:20:00'),
    ('R3', '任务链路跑通', '调度组', 'failed', 'DWS 节点等待上游超时', TIMESTAMP '2026-11-16 11:05:00'),
    ('R4', '血缘完整', '治理组', 'blocked', '上游改造未完成，字段级血缘缺失', TIMESTAMP '2026-11-16 11:30:00'),
    ('R5', '审计可查', '安全组', 'passed', '查询记录含用户与语句', TIMESTAMP '2026-11-16 11:45:00')
ON CONFLICT (gate_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS teach.task_run_log (
    run_id             BIGINT NOT NULL PRIMARY KEY,
    job_code           VARCHAR(64) NOT NULL,
    run_date           DATE NOT NULL,
    duration_minutes   INTEGER NOT NULL,
    scanned_partitions INTEGER NOT NULL,
    total_partitions   INTEGER NOT NULL,
    output_rows        BIGINT NOT NULL
);

INSERT INTO teach.task_run_log (run_id, job_code, run_date, duration_minutes, scanned_partitions, total_partitions, output_rows) VALUES
    (1001, 'dws_metric_snapshot_1d', DATE '2026-11-14', 8, 1, 30, 28410),
    (1002, 'dws_metric_snapshot_1d', DATE '2026-11-15', 8, 1, 30, 28177),
    (1003, 'dws_metric_snapshot_1d', DATE '2026-11-16', 65, 30, 30, 27980),
    (1004, 'dws_metric_snapshot_1d', DATE '2026-11-17', 8, 1, 30, 29003),
    (1005, 'dwd_order_detail_daily', DATE '2026-11-15', 21, 2, 60, 151990),
    (1006, 'dwd_order_detail_daily', DATE '2026-11-16', 22, 2, 60, 154320),
    (1007, 'dwd_payment_valid_daily', DATE '2026-11-16', 30, 12, 60, 88764),
    (1008, 'ads_gmv_daily', DATE '2026-11-16', 9, 4, 20, 1260)
ON CONFLICT (run_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS teach.audit_log (
    id               BIGINT NOT NULL PRIMARY KEY,
    user_name        VARCHAR(64) NOT NULL,
    query_time       TIMESTAMP NOT NULL,
    table_name       VARCHAR(64) NOT NULL,
    scan_rows        BIGINT NOT NULL,
    statement_digest VARCHAR(200) NOT NULL
);

INSERT INTO teach.audit_log (id, user_name, query_time, table_name, scan_rows, statement_digest) VALUES
    (1, 'report_svc', TIMESTAMP '2026-11-15 09:12:00', 'users', 12000, '用户画像明细导出'),
    (2, 'report_svc', TIMESTAMP '2026-11-16 09:12:00', 'users', 15000, '用户画像明细导出'),
    (3, 'ops_wang', TIMESTAMP '2026-11-16 10:40:00', 'users', 48000, '敏感字段原文查询'),
    (4, 'ops_wang', TIMESTAMP '2026-11-16 11:02:00', 'users', 23000, '手机号段核对'),
    (5, 'analyst_li', TIMESTAMP '2026-11-16 14:03:00', 'payments', 8000, '支付流水抽样'),
    (6, 'ops_wang', TIMESTAMP '2026-11-15 14:03:00', 'users', 30000, '历史问题复查'),
    (7, 'etl_svc', TIMESTAMP '2026-11-16 02:10:00', 'users', 500, '增量装载校验')
ON CONFLICT (id) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.job_definition', 1, 'teach.job_definition', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '任务定义元数据（运维教学）'
FROM teach.job_definition t
ON CONFLICT (code, version) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.acceptance_gate', 1, 'teach.acceptance_gate', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '运行验收门（运维教学）'
FROM teach.acceptance_gate t
ON CONFLICT (code, version) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.task_run_log', 1, 'teach.task_run_log', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '任务运行日志（运维教学）'
FROM teach.task_run_log t
ON CONFLICT (code, version) DO NOTHING;

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.audit_log', 1, 'teach.audit_log', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '查询审计日志（运维教学）'
FROM teach.audit_log t
ON CONFLICT (code, version) DO NOTHING;

UPDATE learning.lab
SET objective = '上线前检查任务清单：列出已启用但未声明超时或超时超过 60 分钟的任务（编码、名称、调度、超时分钟），按编码升序。',
    status = 'published',
    expected_sql = $q$SELECT job_code, job_name, schedule_cron, timeout_minutes
FROM job_definition
WHERE enabled AND (timeout_minutes IS NULL OR timeout_minutes > 60)
ORDER BY job_code;$q$,
    starter_sql = $q$SELECT job_code, job_name, enabled, timeout_minutes FROM job_definition ORDER BY job_code;$q$,
    table_refs = '["teach.job_definition"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["job_code","timeout_minutes"]}'::jsonb,
    steps = $j$[{"title":"查看任务清单","detail":"确认每条任务的四要素：脚本、产出表、调度、超时。"},{"title":"过滤未达标项","detail":"只看 enabled，且超时未声明或超过 60 分钟的任务。"},{"title":"排序提交","detail":"按任务编码升序输出并提交。"}]$j$,
    dataset_refs = '["teach.job_definition@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l2-01';

UPDATE learning.lab
SET objective = '发布验收：列出未通过的门（编码、名称、状态、责任人），按门编码升序。',
    status = 'published',
    expected_sql = $q$SELECT gate_code, gate_name, status, owner
FROM acceptance_gate
WHERE status <> 'passed'
ORDER BY gate_code;$q$,
    starter_sql = $q$SELECT gate_code, gate_name, status, owner, evidence FROM acceptance_gate ORDER BY gate_code;$q$,
    table_refs = '["teach.acceptance_gate"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["gate_code","status"]}'::jsonb,
    steps = $j$[{"title":"查看五道门","detail":"确认每道门的结论 status 与证据 evidence。"},{"title":"筛出未通过","detail":"status 不是 passed 的门都要列入。"},{"title":"排序提交","detail":"按门编码升序输出并提交。"}]$j$,
    dataset_refs = '["teach.acceptance_gate@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l3-02';

UPDATE learning.lab
SET objective = '输出 2026-11-16 各任务的运行概览：任务编码、耗时分钟、扫描分区占比（整数百分比）、结论（超时/正常），按编码升序。',
    status = 'published',
    expected_sql = $q$SELECT job_code, duration_minutes,
       scanned_partitions * 100 / total_partitions AS scan_pct,
       CASE WHEN duration_minutes > 60 THEN 'timeout' ELSE 'ok' END AS verdict
FROM task_run_log
WHERE run_date = DATE '2026-11-16'
ORDER BY job_code;$q$,
    starter_sql = $q$SELECT job_code, run_date, duration_minutes, scanned_partitions, total_partitions FROM task_run_log ORDER BY run_date, job_code;$q$,
    table_refs = '["teach.task_run_log"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["scan_pct","verdict"]}'::jsonb,
    steps = $j$[{"title":"锁定当天运行","detail":"只看 2026-11-16 的运行记录。"},{"title":"算扫描占比","detail":"扫描分区数除以总分区的整数百分比。"},{"title":"标注结论","detail":"耗时超过 60 分钟标 timeout，否则 ok，按编码升序提交。"}]$j$,
    dataset_refs = '["teach.task_run_log@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l3-03';

UPDATE learning.lab
SET objective = '审计 2026-11-16 访问 users 的查询：按用户输出查询次数、扫描行数合计、结论（合计不少于 30000 标 review，否则 ok），按扫描行数降序。',
    status = 'published',
    expected_sql = $q$SELECT user_name, COUNT(*) AS queries,
       SUM(scan_rows) AS total_scan_rows,
       CASE WHEN SUM(scan_rows) >= 30000 THEN 'review' ELSE 'ok' END AS verdict
FROM audit_log
WHERE table_name = 'users'
  AND query_time >= TIMESTAMP '2026-11-16 00:00:00'
  AND query_time < TIMESTAMP '2026-11-17 00:00:00'
GROUP BY user_name
ORDER BY total_scan_rows DESC;$q$,
    starter_sql = $q$SELECT user_name, query_time, table_name, scan_rows FROM audit_log ORDER BY query_time;$q$,
    table_refs = '["teach.audit_log"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["queries","total_scan_rows","verdict"]}'::jsonb,
    steps = $j$[{"title":"锁定审计窗口","detail":"只看 2026-11-16 全天且 table_name 为 users 的记录。"},{"title":"按用户聚合","detail":"统计查询次数与扫描行数合计。"},{"title":"标注结论","detail":"合计不少于 30000 行标 review，其余 ok，按扫描行数降序提交。"}]$j$,
    dataset_refs = '["teach.audit_log@1"]'::jsonb,
    updated_at = now()
WHERE code = 'ops-l3-04';
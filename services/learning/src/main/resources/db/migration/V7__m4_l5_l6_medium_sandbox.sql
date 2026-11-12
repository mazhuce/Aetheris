-- M4 补齐：沙箱档位（Light/Medium）+ L5 数据资产 + L6 生产工程内容。

ALTER TABLE learning.sandbox_instance
    ADD COLUMN IF NOT EXISTS tier VARCHAR(16) NOT NULL DEFAULT 'light';
ALTER TABLE learning.lab
    ADD COLUMN IF NOT EXISTS sandbox_tier VARCHAR(16) NOT NULL DEFAULT 'light';
CREATE INDEX IF NOT EXISTS idx_sandbox_tier ON learning.sandbox_instance (status, tier);

-- ---------------------------------------------------------------- L5 教学数据

CREATE TABLE IF NOT EXISTS teach.metric_dict (
    metric_code    VARCHAR(64) PRIMARY KEY,
    metric_name    VARCHAR(200) NOT NULL,
    owner          VARCHAR(60) NOT NULL,
    status         VARCHAR(24) NOT NULL,
    definition_note TEXT,
    last_reviewed  DATE NOT NULL
);
INSERT INTO teach.metric_dict (metric_code, metric_name, owner, status, definition_note, last_reviewed) VALUES
    ('gmv_daily', '日 GMV', '交易组', 'active', '有效订单金额合计，去重后口径', DATE '2026-08-05'),
    ('order_cnt_daily', '日订单量', '交易组', 'active', '有效订单数，含退款前', DATE '2026-08-01'),
    ('paid_rate', '支付转化率', '交易组', 'active', '支付订单 / 下单订单', DATE '2026-04-20'),
    ('new_user_cnt', '新用户数', '增长组', 'active', '首次下单用户数', DATE '2026-07-28'),
    ('retention_d1', '次日留存率', '增长组', 'active', '次日回访用户 / 首日活跃', DATE '2026-03-15'),
    ('churn_rate', '流失率', '增长组', 'deprecated', '30 天未活跃用户占比，已由 ret7 替代', DATE '2026-06-30'),
    ('avg_order_value', '客单价', '交易组', 'active', 'GMV / 有效订单数', DATE '2026-05-10'),
    ('asset_coverage', '资产覆盖率', '数据组', 'active', '有负责人且有血缘的表占比', DATE '2026-08-08'),
    ('quality_score_avg', '质量分均值', '数据组', 'active', '全部在线表质量分平均', DATE '2026-07-20'),
    ('sla_on_time_rate', 'SLA 达标率', '数据组', 'draft', '按日到达准时占比，口径评审中', DATE '2026-08-09')
ON CONFLICT (metric_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS teach.table_asset (
    table_name     VARCHAR(120) PRIMARY KEY,
    layer          VARCHAR(16) NOT NULL,
    owner          VARCHAR(60) NOT NULL,
    status         VARCHAR(24) NOT NULL,
    quality_score  NUMERIC(5,2) NOT NULL,
    row_count      BIGINT NOT NULL,
    last_refresh   DATE NOT NULL
);
INSERT INTO teach.table_asset (table_name, layer, owner, status, quality_score, row_count, last_refresh) VALUES
    ('ods_orders_raw', 'ods', '数据组', 'online', 96, 1300000, DATE '2026-08-10'),
    ('ods_payments', 'ods', '数据组', 'online', 91, 980000, DATE '2026-08-10'),
    ('dwd_order_valid', 'dwd', '交易组', 'online', 98, 1210000, DATE '2026-08-10'),
    ('dwd_payment_valid', 'dwd', '交易组', 'online', 95, 960000, DATE '2026-08-10'),
    ('dws_user_daily', 'dws', '增长组', 'online', 93, 480000, DATE '2026-08-09'),
    ('ads_user_segments', 'ads', '增长组', 'draft', 88, 52000, DATE '2026-08-08'),
    ('ads_gmv_daily', 'ads', '交易组', 'online', 97, 31, DATE '2026-08-10'),
    ('dim_user', 'dim', '数据组', 'offline', 100, 210000, DATE '2026-08-01'),
    ('dim_sku', 'dim', '数据组', 'online', 99, 8600, DATE '2026-08-10'),
    ('dws_metric_snapshot', 'dws', '数据组', 'online', 90, 12000, DATE '2026-08-07')
ON CONFLICT (table_name) DO NOTHING;

-- ---------------------------------------------------------------- L6 教学数据

CREATE TABLE IF NOT EXISTS teach.daily_arrival (
    dt             DATE PRIMARY KEY,
    arrived_at     TIMESTAMPTZ NOT NULL,
    expected_at    TIMESTAMPTZ NOT NULL,
    rows_arrived   INTEGER NOT NULL,
    expected_rows  INTEGER NOT NULL
);
INSERT INTO teach.daily_arrival (dt, arrived_at, expected_at, rows_arrived, expected_rows) VALUES
    (DATE '2026-08-02', TIMESTAMPTZ '2026-08-03 02:10:00+08', TIMESTAMPTZ '2026-08-03 02:30:00+08', 4200, 4200),
    (DATE '2026-08-03', TIMESTAMPTZ '2026-08-04 02:45:00+08', TIMESTAMPTZ '2026-08-04 02:30:00+08', 5100, 5100),
    (DATE '2026-08-04', TIMESTAMPTZ '2026-08-05 02:20:00+08', TIMESTAMPTZ '2026-08-05 02:30:00+08', 4800, 4800),
    (DATE '2026-08-05', TIMESTAMPTZ '2026-08-06 03:10:00+08', TIMESTAMPTZ '2026-08-06 02:30:00+08', 5300, 5300),
    (DATE '2026-08-06', TIMESTAMPTZ '2026-08-07 02:00:00+08', TIMESTAMPTZ '2026-08-07 02:30:00+08', 4600, 4700),
    (DATE '2026-08-07', TIMESTAMPTZ '2026-08-08 02:25:00+08', TIMESTAMPTZ '2026-08-08 02:30:00+08', 5000, 5000),
    (DATE '2026-08-08', TIMESTAMPTZ '2026-08-09 02:40:00+08', TIMESTAMPTZ '2026-08-09 02:30:00+08', 5400, 5400),
    (DATE '2026-08-09', TIMESTAMPTZ '2026-08-10 02:15:00+08', TIMESTAMPTZ '2026-08-10 02:30:00+08', 5200, 5200),
    (DATE '2026-08-10', TIMESTAMPTZ '2026-08-11 02:05:00+08', TIMESTAMPTZ '2026-08-11 02:30:00+08', 4900, 4950)
ON CONFLICT (dt) DO NOTHING;

-- ---------------------------------------------------------------- L5/L6 内容

INSERT INTO learning.project (code, title, summary, level, scenario, deliverables, status, order_no) VALUES
    ('proj-asset-operations', '数据资产与生产运维项目', '盘点资产健康度、清理过期指标口径，并建立 SLA 与告警阈值，形成一份数据运维周报。', 'L5-L6',
     '你是平台的数据资产负责人。季度盘点在即：哪些指标口径太久没人复核？哪些表质量分偏低？上周哪些分区迟到了、该设多大的告警阈值？',
     $j$["资产健康度盘点表","待复核指标清单","SLA 达标日报","补数计划","告警阈值建议"]$j$, 'published', 3)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.skill_node (path_code, code, parent_code, title, level, knowledge_points, outcome, order_no) VALUES
    ('de-foundation', 'asset-management', NULL, '数据资产管理', 'L5',
     $j$["资产盘点","指标生命周期","负责人机制"]$j$, '能产出资产健康度报表并识别过期口径', 11),
    ('de-foundation', 'production-monitoring', NULL, '生产监控与运维', 'L6',
     $j$["SLA 口径","补数计划","P95 阈值"]$j$, '能基于到达数据制定补数与告警策略', 12)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no) VALUES
    ('course-de-foundation', 'm-l5-assets', 'L5 数据资产', '盘点表与指标资产，识别过期口径与低质量资产。', 'L5', 5),
    ('course-de-foundation', 'm-l6-ops', 'L6 生产工程', '基于到达数据做 SLA 监控、补数计划与告警阈值。', 'L6', 6)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     project_code, author, review_status, published_at, sandbox_tier)
VALUES
    ('m-l5-assets', 'l5-asset-inventory', '资产健康度盘点', 'L5', 'medium', 'SQL',
     '按负责人汇总在线表数量、平均质量分与活跃指标数，输出资产盘点表。',
     '季度盘点需要一张按负责人的资产健康度表：每个团队有多少在线表、质量分如何、维护着多少活跃指标。',
     '输出 owner、table_cnt、avg_quality、active_metrics 四列，按 owner 升序。',
     $j$[{"title":"先聚合表资产","detail":"只统计 status=online 的表，按 owner 求表数与平均质量分。"},{"title":"再聚合指标","detail":"只统计 status=active 的指标，按 owner 求活跃指标数。"},{"title":"关联两份汇总","detail":"按 owner JOIN，避免行交叉导致均值被放大。"},{"title":"排序自检","detail":"每个负责人一行，按 owner 升序。"}]$j$,
     $j$["teach.table_asset","teach.metric_dict"]$j$,
     $q$SELECT owner, COUNT(*) AS table_cnt FROM table_asset GROUP BY owner$q$,
     $q$WITH tables AS (
  SELECT owner, COUNT(*) AS table_cnt, ROUND(AVG(quality_score), 2) AS avg_quality
    FROM table_asset WHERE status = 'online' GROUP BY owner
), metrics AS (
  SELECT owner, COUNT(*) AS active_metrics
    FROM metric_dict WHERE status = 'active' GROUP BY owner
)
SELECT t.owner, t.table_cnt, t.avg_quality, m.active_metrics
  FROM tables t JOIN metrics m ON m.owner = t.owner
 ORDER BY t.owner$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["group\\s+by","join"],"requiredColumns":["status"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"先聚合再关联","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 40, 'published', 1, 1, 'proj-asset-operations', 'Aetheris 教学组', 'approved', now(), 'light'),

    ('m-l5-assets', 'l5-metric-review', '过期指标口径复核', 'L5', 'medium', 'SQL',
     '找出活跃但超过 90 天未复核的指标，输出复核清单。',
     '口径治理要求活跃指标每季度复核一次。以 2026-08-10 为观察日，列出需要人工复核的指标。',
     '输出 metric_code、metric_name、owner、days_since_review 四列，按复核间隔降序、编码升序。',
     $j$[{"title":"限定范围","detail":"只看 status=active 的指标。"},{"title":"计算间隔","detail":"观察日 2026-08-10 减 last_reviewed。"},{"title":"过滤 90 天","detail":"间隔大于等于 90 天进入清单。"},{"title":"排序","detail":"间隔降序，再按编码升序。"}]$j$,
     $j$["teach.metric_dict"]$j$,
     $q$SELECT metric_code, metric_name, owner FROM metric_dict$q$,
     $q$SELECT metric_code, metric_name, owner,
       DATE '2026-08-10' - last_reviewed AS days_since_review
  FROM metric_dict
 WHERE status = 'active' AND DATE '2026-08-10' - last_reviewed >= 90
 ORDER BY days_since_review DESC, metric_code$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredColumns":["status","last_reviewed"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"口径与阈值","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 30, 'published', 1, 2, 'proj-asset-operations', 'Aetheris 教学组', 'approved', now(), 'light'),

    ('m-l6-ops', 'l6-sla-monitor', '分区到达 SLA 监控', 'L6', 'hard', 'SQL',
     '输出每日分区的完整率与 SLA 状态，这是运维日报的核心表。',
     '调度平台每天 02:30 前应完成前一交易日分区。请基于到达记录输出完整率与是否迟到。',
     '输出 dt、rows_arrived、expected_rows、completeness、sla_status 五列，按 dt 升序；completeness 保留一位小数。',
     $j$[{"title":"计算完整率","detail":"rows_arrived / expected_rows，用 NULLIF 防除零，保留一位小数。"},{"title":"判定迟到","detail":"arrived_at 晚于 expected_at 即为 late。"},{"title":"输出状态","detail":"CASE WHEN 输出 late / on_time。"},{"title":"排序","detail":"按 dt 升序。"}]$j$,
     $j$["teach.daily_arrival"]$j$,
     $q$SELECT dt, rows_arrived, expected_rows FROM daily_arrival ORDER BY dt$q$,
     $q$SELECT dt,
       rows_arrived,
       expected_rows,
       ROUND(100.0 * rows_arrived / NULLIF(expected_rows, 0), 1) AS completeness,
       CASE WHEN arrived_at > expected_at THEN 'late' ELSE 'on_time' END AS sla_status
  FROM daily_arrival
 ORDER BY dt$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["case\\s+when"],"requiredColumns":["arrived_at","expected_at"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"SLA 口径","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 40, 'published', 1, 1, 'proj-asset-operations', 'Aetheris 教学组', 'approved', now(), 'medium'),

    ('m-l6-ops', 'l6-backfill-plan', '补数计划', 'L6', 'hard', 'SQL',
     '列出需要重跑的分区、原因与缺失行数，形成可执行的补数清单。',
     '昨天有分区迟到、也有分区行数缺口。请输出哪些日期需要补数、原因是什么、缺多少行。',
     '输出 dt、reason、missing_rows 三列，按 dt 升序；reason 取 SLA_DELAY 或 ROW_GAP。',
     $j$[{"title":"判定条件","detail":"行数有缺口或到达迟到，任一命中即需补数。"},{"title":"区分原因","detail":"迟到记 SLA_DELAY，否则记 ROW_GAP。"},{"title":"缺失行数","detail":"expected_rows - rows_arrived。"},{"title":"排序","detail":"按 dt 升序。"}]$j$,
     $j$["teach.daily_arrival"]$j$,
     $q$SELECT dt, expected_rows - rows_arrived AS missing_rows FROM daily_arrival$q$,
     $q$SELECT dt,
       CASE WHEN arrived_at > expected_at THEN 'SLA_DELAY' ELSE 'ROW_GAP' END AS reason,
       expected_rows - rows_arrived AS missing_rows
  FROM daily_arrival
 WHERE rows_arrived < expected_rows OR arrived_at > expected_at
 ORDER BY dt$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["case\\s+when"],"requiredColumns":["rows_arrived","expected_rows"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"补数口径","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 40, 'published', 1, 2, 'proj-asset-operations', 'Aetheris 教学组', 'approved', now(), 'medium'),

    ('m-l6-ops', 'l6-alert-threshold', '延迟告警阈值', 'L6', 'hard', 'SQL',
     '用历史到达延迟的 P95 计算告警阈值，避免拍脑袋定值。',
     '告警太敏感会被报警淹没，太迟钝会漏掉事故。请基于 9 天历史延迟给出 P95 阈值建议。',
     '输出 total_days、late_days、p95_delay_minutes 三列，单行结果；P95 保留一位小数。',
     $j$[{"title":"计算延迟分钟","detail":"arrived_at 与 expected_at 的时差换算成分钟，负数按 0 计。"},{"title":"P95","detail":"percentile_cont(0.95) WITHIN GROUP。"},{"title":"迟到天数","detail":"FILTER 统计延迟大于 0 的天数。"},{"title":"精度","detail":"P95 保留一位小数。"}]$j$,
     $j$["teach.daily_arrival"]$j$,
     $q$SELECT COUNT(*) AS total_days FROM daily_arrival$q$,
     $q$WITH delays AS (
  SELECT GREATEST(EXTRACT(EPOCH FROM (arrived_at - expected_at)) / 60, 0) AS delay_minutes
    FROM daily_arrival
)
SELECT COUNT(*) AS total_days,
       COUNT(*) FILTER (WHERE delay_minutes > 0) AS late_days,
       ROUND(percentile_cont(0.95) WITHIN GROUP (ORDER BY delay_minutes), 1) AS p95_delay_minutes
  FROM delays$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["percentile_cont","filter\\s*\\("],"requiredColumns":["extract"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"统计口径","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 45, 'published', 1, 3, 'proj-asset-operations', 'Aetheris 教学组', 'approved', now(), 'medium')
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('l5-asset-inventory', 'asset-management', 100),
    ('l5-metric-review', 'asset-management', 100),
    ('l6-sla-monitor', 'production-monitoring', 100),
    ('l6-backfill-plan', 'production-monitoring', 100),
    ('l6-alert-threshold', 'production-monitoring', 100)
ON CONFLICT (lab_code, skill_code) DO NOTHING;

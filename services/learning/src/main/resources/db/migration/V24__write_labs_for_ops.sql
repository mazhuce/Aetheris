-- 写任务扩容：新增门店 GMV 快照空表，补两个数据驱动写任务（INSERT SELECT / UPDATE）。
-- 空表没有可计算的摘要，清单列允许为空；漂移校验对 null 与 null 判定一致。
ALTER TABLE learning.dataset_manifest ALTER COLUMN checksum DROP NOT NULL;

CREATE TABLE IF NOT EXISTS teach.daily_shop_gmv (
    dt        DATE NOT NULL,
    shop_id   BIGINT NOT NULL,
    gmv       NUMERIC(14,2) NOT NULL,
    order_cnt BIGINT NOT NULL,
    PRIMARY KEY (dt, shop_id)
);

INSERT INTO learning.dataset_manifest (code, version, table_name, row_count, checksum, license, source, pii_reviewed, note)
SELECT 'teach.daily_shop_gmv', 1, 'teach.daily_shop_gmv', count(*),
       md5(string_agg(t::text, '|' ORDER BY t::text)),
       'internal-training', 'synthetic', true, '门店 GMV 快照（写任务目标空表）'
FROM teach.daily_shop_gmv t
ON CONFLICT (code, version) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge, dataset_refs)
VALUES
    ('ops-m1', 'ops-l2-04', '补数落表：INSERT SELECT 生成门店 GMV', 'L2', 'medium', 'SQL_WRITE',
     '从订单明细聚合每日门店 GMV 并写入快照表；练习 INSERT SELECT 的过滤与分组口径。',
     '门店日报表 daily_shop_gmv 缺少 2026-08-05 的数据，需要从订单明细补录：按门店聚合 GMV 与订单数，已删除订单不计入。写任务只允许在沙箱副本上执行，判分依据是目标表的最终状态。',
     '把 2026-08-05 未删除订单按门店聚合写入 daily_shop_gmv：gmv 为 quantity*unit_price 之和，order_cnt 为订单数；不得写入其他日期。',
     $j$[{"title":"核对明细口径","detail":"先查 2026-08-05 未删除订单的门店分布与金额。"},{"title":"写 INSERT SELECT","detail":"按门店聚合后写入 daily_shop_gmv，只保留目标日期。"},{"title":"提交比对","detail":"提交后比对目标表最终状态，并自动重置为教学初始数据。"}]$j$,
     $j$["teach.orders_raw","teach.daily_shop_gmv"]$j$,
     $q$SELECT dt, shop_id, SUM(quantity * unit_price) AS gmv, COUNT(*) AS order_cnt
FROM orders_raw
WHERE is_deleted = false
  AND dt = DATE '2026-08-05'
GROUP BY dt, shop_id
ORDER BY shop_id;$q$,
     $q$INSERT INTO daily_shop_gmv
SELECT dt, shop_id, SUM(quantity * unit_price), COUNT(*)
FROM orders_raw
WHERE is_deleted = false
  AND dt = DATE '2026-08-05'
GROUP BY dt, shop_id;$q$,
     $pol$ {"allowWrite":true,"targetTable":"daily_shop_gmv","stateQuery":"SELECT dt, shop_id, gmv, order_cnt FROM daily_shop_gmv ORDER BY dt, shop_id","requiredPatterns":["\\binsert\\s+into\\s+daily_shop_gmv\\b","\\bselect\\b","is_deleted\\s*=\\s*false"],"forbiddenKeywords":["update","delete","create","drop","alter","truncate","grant","copy","merge","call"]}$pol$::jsonb,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"目标表最终状态与标准答案一致"},{"key":"filters","name":"过滤口径","weight":40,"passed":true,"detail":"只聚合目标日期且排除已删除订单"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"单条 INSERT SELECT，不写 schema 前缀"}]$j$,
     60, 25, 'published', 1, 4,
     $j$["SQL开发","数据处理"]$j$,
     $j$[{"title":"INSERT SELECT 落表","detail":"补数任务要同时控制来源过滤与目标日期，写错 WHERE 会把脏数据带进报表。"},{"title":"最终状态判分","detail":"写任务按目标表最终状态比对，执行后自动重置，可反复练习。"}]$j$,
     $j$["teach.orders_raw@1","teach.daily_shop_gmv@1"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge, dataset_refs)
VALUES
    ('ops-m1', 'ops-l2-05', '修正任务定义：给 GMV 日报补超时', 'L2', 'easy', 'SQL_WRITE',
     '用 UPDATE 精确修正一条任务定义的超时；练习只改目标行、不误伤其他任务。',
     'ads_gmv_daily 的 timeout_minutes 为空，任务挂死时无法触发告警。DBA 要求补成 60 分钟，且只改这一条任务定义。写任务只允许在沙箱副本上执行，判分依据是任务定义表的最终状态。',
     '把 job_definition 中 job_code 为 ads_gmv_daily 的任务超时改为 60 分钟，其他任务保持不变。',
     $j$[{"title":"定位任务","detail":"先查 ads_gmv_daily 当前的超时声明。"},{"title":"写 UPDATE","detail":"只更新这一条任务定义，保留其他任务不动。"},{"title":"提交比对","detail":"提交后比对任务定义表最终状态，并自动重置。"}]$j$,
     $j$["teach.job_definition"]$j$,
     $q$SELECT job_code, job_name, timeout_minutes
FROM job_definition
WHERE job_code = 'ads_gmv_daily';$q$,
     $q$UPDATE job_definition
SET timeout_minutes = 60
WHERE job_code = 'ads_gmv_daily';$q$,
     $pol$ {"allowWrite":true,"targetTable":"job_definition","stateQuery":"SELECT job_code, timeout_minutes FROM job_definition ORDER BY job_code","requiredPatterns":["\\bupdate\\s+job_definition\\b","\\bwhere\\b","timeout_minutes\\s*=\\s*60"],"forbiddenKeywords":["insert","delete","create","drop","alter","truncate","grant","copy","merge","call"]}$pol$::jsonb,
     $j$[{"key":"rows","name":"影响行数","weight":40,"passed":true,"detail":"目标表最终状态与标准答案一致"},{"key":"filters","name":"WHERE 边界","weight":40,"passed":true,"detail":"只改 ads_gmv_daily 一条任务"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"单条 UPDATE，不写 schema 前缀"}]$j$,
     60, 15, 'published', 1, 5,
     $j$["任务调度","配置管理"]$j$,
     $j$[{"title":"任务定义的超时要素","detail":"没有超时声明的任务挂死时不会告警；修正时要用主键精确限定影响行数。"},{"title":"写任务边界","detail":"提交后服务端比对最终状态并重置目标表，不会影响教学活动数据。"}]$j$,
     $j$["teach.job_definition@1"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('ops-l2-04', 'ops-task-def', 1.0),
    ('ops-l2-05', 'ops-retry-alert', 1.0)
ON CONFLICT (lab_code, skill_code) DO NOTHING;
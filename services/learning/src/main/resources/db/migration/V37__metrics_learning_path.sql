-- 第 5 条岗位路径：数据产品与指标运营（3 模块 / 7 题），并补齐 3 个空项目的任务绑定。
-- 全部任务只依赖现有教学表（metric_dict / table_asset / lineage_edge / orders_raw），不引入新数据。

INSERT INTO learning.learning_path
    (code, title, description, target_role, level, version, status, estimated_hours, order_no)
VALUES
    ('metrics-basic', '数据产品与指标运营',
     '面向指标运营与数据产品助理：从指标字典、资产与血缘到经营周报，建立可信口径与可交付报表。',
     '指标运营 / 数据产品助理', 'L1-L2', 1, 'published', 8, 6)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course (path_code, code, title, summary, version, status)
SELECT 'metrics-basic', 'course-metrics-basic', '数据产品与指标运营（实战）',
       '先建立可信指标与资产视图，再把口径落成一份可复述的经营周报。', 1, 'published'
  FROM learning.learning_path WHERE code = 'metrics-basic'
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features)
SELECT 'course-metrics-basic', 'metrics-m1', 'L1 · 指标字典与口径',
       '判断一个指标是否可信：状态、复核时间与责任人。', 'L1', 1, '["指标运营","口径治理"]'::jsonb
  FROM learning.course WHERE code = 'course-metrics-basic'
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features)
SELECT 'course-metrics-basic', 'metrics-m2', 'L2 · 资产、依赖与风险',
       '从表资产到指标依赖，评估变更影响面与离线风险。', 'L2', 2, '["数据资产","影响分析"]'::jsonb
  FROM learning.course WHERE code = 'course-metrics-basic'
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features)
SELECT 'course-metrics-basic', 'metrics-m3', 'L2 · 经营周报实战',
       '把指标口径落成一份可复述、可交付的经营周报。', 'L2', 3, '["经营分析","指标口径"]'::jsonb
  FROM learning.course WHERE code = 'course-metrics-basic'
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge, dataset_refs)
VALUES
    ('metrics-m1', 'metrics-l1-01', '指标字典体检：过期与停用口径', 'L1', 'medium', 'SQL',
     '用一条过滤条件找出停用、草稿和长期未复核的指标，练习 OR 与日期条件的组合。',
     '数据产品经理接手指标字典，第一件事是把不可信的口径挑出来：状态不是 active，或者超过三个月没有复核。',
     '输出 metric_code、metric_name、owner、status、last_reviewed；筛选 status 不为 active 或 last_reviewed 早于 2026-06-01 的指标，按 status、last_reviewed 升序。',
     $j$[{"title":"先看状态","detail":"deprecated 与 draft 都不应继续进入报表。"},{"title":"再看复核时间","detail":"用 DATE 字面量比较 last_reviewed，避免字符串比较。"},{"title":"组合条件","detail":"两个条件之间是 OR，注意括号与优先级。"}]$j$,
     $j$["teach.metric_dict"]$j$,
     $q$SELECT metric_code, metric_name, owner, status, last_reviewed
FROM metric_dict
ORDER BY last_reviewed;$q$,
     $q$SELECT metric_code, metric_name, owner, status, last_reviewed
FROM metric_dict
WHERE status <> 'active'
   OR last_reviewed < DATE '2026-06-01'
ORDER BY status, last_reviewed;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bor\\b"],"requiredColumns":["last_reviewed"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"结果范围","weight":40,"passed":true,"detail":"停用/草稿/过期指标完整列出"},{"key":"filters","name":"条件口径","weight":40,"passed":true,"detail":"状态与复核时间条件均正确"},{"key":"style","name":"排序规范","weight":20,"passed":true,"detail":"按 status、last_reviewed 升序"}]$j$,
     70, 15, 'published', 1, 1,
     $j$["指标运营","口径治理"]$j$,
     $j$[{"title":"日期比较","detail":"DATE '2026-06-01' 是明确的日期字面量，比字符串比较可靠。"},{"title":"OR 的边界","detail":"状态与时间是两个独立风险信号，任一满足就应进入体检清单。"}]$j$,
     $j$["teach.metric_dict@1"]$j$),

    ('metrics-m1', 'metrics-l1-02', '指标状态盘点：active、deprecated 与 draft', 'L1', 'medium', 'SQL',
     '按责任人统计不同状态的指标数量，练习条件聚合（FILTER）与状态盘点。',
     '指标运营要开一次口径治理会：先看每个责任人名下分别有多少 active、deprecated 和 draft 指标。',
     '输出 owner、active_cnt、deprecated_cnt、draft_cnt；按 deprecated_cnt 降序、owner 升序。',
     $j$[{"title":"条件计数","detail":"COUNT(*) FILTER (WHERE ...) 可以在一次分组里统计多个状态。"},{"title":"覆盖全部状态","detail":"三列分别对应 active、deprecated、draft，不要漏项。"},{"title":"排序","detail":"先按停用数量降序，再按 owner 升序。"}]$j$,
     $j$["teach.metric_dict"]$j$,
     $q$SELECT owner, COUNT(*) AS total
FROM metric_dict
GROUP BY owner
ORDER BY total DESC;$q$,
     $q$SELECT owner,
       COUNT(*) FILTER (WHERE status = 'active') AS active_cnt,
       COUNT(*) FILTER (WHERE status = 'deprecated') AS deprecated_cnt,
       COUNT(*) FILTER (WHERE status = 'draft') AS draft_cnt
FROM metric_dict
GROUP BY owner
ORDER BY deprecated_cnt DESC, owner;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bfilter\\s*\\("],"requiredColumns":["active_cnt","deprecated_cnt","draft_cnt"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"分组结果","weight":40,"passed":true,"detail":"每个责任人一行"},{"key":"metrics","name":"状态口径","weight":40,"passed":true,"detail":"三种状态计数准确"},{"key":"style","name":"聚合规范","weight":20,"passed":true,"detail":"使用 FILTER 完成条件计数"}]$j$,
     70, 15, 'published', 1, 2,
     $j$["指标运营","条件聚合"]$j$,
     $j$[{"title":"FILTER 聚合","detail":"COUNT(*) FILTER 只统计满足条件的行，比多层 CASE 更直观。"},{"title":"责任人视角","detail":"按 owner 分组能直接暴露谁名下的历史包袱最多。"}]$j$,
     $j$["teach.metric_dict@1"]$j$),

    ('metrics-m2', 'metrics-l2-01', '资产健康盘点：分层在线率与质量分', 'L2', 'medium', 'SQL',
     '按数仓分层统计在线表数量、平均质量分与在线行数，练习条件聚合与 HAVING。',
     '资产运营要做周度健康盘点：只看在线表，按分层汇总数量、平均质量分和总行数，并筛出在线表不少于 2 张的分层。',
     '输出 layer、online_cnt、avg_quality（在线表平均质量分，保留两位小数）、online_rows（在线表行数合计）；只保留 online_cnt >= 2 的分层，按 avg_quality 降序、layer 升序。',
     $j$[{"title":"先筛在线","detail":"质量分与行数只统计 status = online 的表。"},{"title":"条件聚合","detail":"FILTER 可以同时得到数量、均值与求和。"},{"title":"分组后过滤","detail":"样本量太小的分层用 HAVING 排除。"}]$j$,
     $j$["teach.table_asset"]$j$,
     $q$SELECT layer, COUNT(*) AS total
FROM table_asset
GROUP BY layer
ORDER BY total DESC;$q$,
     $q$SELECT layer,
       COUNT(*) FILTER (WHERE status = 'online') AS online_cnt,
       ROUND(AVG(quality_score) FILTER (WHERE status = 'online'), 2) AS avg_quality,
       SUM(row_count) FILTER (WHERE status = 'online') AS online_rows
FROM table_asset
GROUP BY layer
HAVING COUNT(*) FILTER (WHERE status = 'online') >= 2
ORDER BY avg_quality DESC, layer;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bfilter\\s*\\(","\\bhaving\\b"],"requiredColumns":["online_cnt","avg_quality","online_rows"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"分层结果","weight":40,"passed":true,"detail":"仅保留在线表不少于 2 张的分层"},{"key":"metrics","name":"指标口径","weight":40,"passed":true,"detail":"数量、均值与行数均只统计在线表"},{"key":"style","name":"聚合规范","weight":20,"passed":true,"detail":"使用 FILTER 与 HAVING"}]$j$,
     70, 20, 'published', 1, 1,
     $j$["数据资产","健康盘点"]$j$,
     $j$[{"title":"条件聚合","detail":"FILTER 只影响当前聚合，不改变分组本身。"},{"title":"HAVING 与 WHERE","detail":"WHERE 过滤行，HAVING 过滤分组；这里按在线表数量筛分层。"}]$j$,
     $j$["teach.table_asset@1"]$j$),

    ('metrics-m2', 'metrics-l2-02', '指标依赖影响面：递归 CTE 追下游', 'L2', 'medium', 'SQL',
     '从源表出发递归追踪所有下游表，输出层级；练习 WITH RECURSIVE。',
     '上游订单表要改字段，数据产品需要一份完整影响面：从 ods_orders_raw 出发，所有下游表分别在第几层。',
     '输出 table_name、level（直接下游为 1）；从 ods_orders_raw 出发递归追踪全部下游，按 level、table_name 升序。',
     $j$[{"title":"第一层","detail":"直接下游用一条普通 SELECT 得到，层级为 1。"},{"title":"递归扩展","detail":"用 UNION ALL 把下游表再次连接到血缘边。"},{"title":"终止条件","detail":"本数据没有环；递归会在没有新下游时自然结束。"}]$j$,
     $j$["teach.lineage_edge"]$j$,
     $q$SELECT downstream_table AS table_name, 1 AS level
FROM lineage_edge
WHERE upstream_table = 'ods_orders_raw';$q$,
     $q$WITH RECURSIVE downstream AS (
    SELECT downstream_table AS table_name, 1 AS level
      FROM lineage_edge
     WHERE upstream_table = 'ods_orders_raw'
    UNION ALL
    SELECT e.downstream_table, d.level + 1
      FROM lineage_edge e
      JOIN downstream d ON e.upstream_table = d.table_name
)
SELECT table_name, level
FROM downstream
ORDER BY level, table_name;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bwith\\s+recursive\\b","\\bunion\\s+all\\b"],"requiredColumns":["table_name","level"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"影响面完整性","weight":40,"passed":true,"detail":"所有层级的下游表完整列出"},{"key":"metrics","name":"层级口径","weight":40,"passed":true,"detail":"直接下游为 1，逐层递增"},{"key":"style","name":"递归规范","weight":20,"passed":true,"detail":"使用 WITH RECURSIVE 与 UNION ALL"}]$j$,
     70, 25, 'published', 1, 2,
     $j$["数据血缘","影响分析"]$j$,
     $j$[{"title":"递归 CTE","detail":"WITH RECURSIVE 先给出种子行，再不断把结果与血缘边连接。"},{"title":"层级计算","detail":"每扩展一次层级 +1，最终得到 1/2/3 层的完整视图。"}]$j$,
     $j$["teach.lineage_edge@1"]$j$),

    ('metrics-m2', 'metrics-l2-03', '离线风险清单：状态与质量分双筛', 'L2', 'medium', 'SQL',
     '把非在线或质量分低于 95 的表挑出来，按风险排序；练习 OR 与阈值条件。',
     '资产运营每天要看风险清单：不在线的表要跟进恢复，质量分低于 95 的表要安排治理。',
     '输出 table_name、layer、status、quality_score；筛选 status 不为 online 或 quality_score 小于 95 的表，按 quality_score、table_name 升序。',
     $j$[{"title":"两条风险线","detail":"非在线状态与低质量分是两种不同风险，用 OR 合并。"},{"title":"阈值","detail":"质量分严格小于 95 才进入清单。"},{"title":"排序","detail":"先按质量分升序，再按表名升序。"}]$j$,
     $j$["teach.table_asset"]$j$,
     $q$SELECT table_name, layer, status, quality_score
FROM table_asset
WHERE status <> 'online'
ORDER BY quality_score;$q$,
     $q$SELECT table_name, layer, status, quality_score
FROM table_asset
WHERE status <> 'online'
   OR quality_score < 95
ORDER BY quality_score, table_name;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bor\\b"],"requiredColumns":["quality_score"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"风险范围","weight":40,"passed":true,"detail":"非在线与低质量表完整列出"},{"key":"filters","name":"阈值口径","weight":40,"passed":true,"detail":"质量分阈值与状态条件正确"},{"key":"style","name":"排序规范","weight":20,"passed":true,"detail":"按质量分、表名升序"}]$j$,
     70, 15, 'published', 1, 3,
     $j$["数据资产","风险治理"]$j$,
     $j$[{"title":"双条件清单","detail":"状态与质量分是两个独立信号，任一满足都要进入清单。"},{"title":"阈值边界","detail":"小于 95 与小于等于 95 会产生不同清单，口径要写清楚。"}]$j$,
     $j$["teach.table_asset@1"]$j$),

    ('metrics-m3', 'metrics-l2-04', '经营周报总览：GMV、支付与退款一屏口径', 'L2', 'medium', 'SQL',
     '用一条查询输出周报三个核心指标，练习一次扫描内的多口径聚合。',
     '周五经营周报要一屏三个数：本周 GMV、支付订单数、退款率。统计区间为 2026-08-01 到 2026-08-09。',
     '输出 gmv（有效行金额合计，保留两位小数）、paid_orders（支付订单数，按 order_id 去重）、refund_rate（退款行占比百分比，保留两位小数）；统计 dt 在 2026-08-01 到 2026-08-09 之间的数据，只返回一行。',
     $j$[{"title":"金额口径","detail":"GMV 只统计未删除且数量为正的行。"},{"title":"订单去重","detail":"支付订单数用 COUNT(DISTINCT order_id) FILTER 统计。"},{"title":"退款率","detail":"退款行数除以区间总行数，乘 100 并保留两位小数。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT COUNT(*) AS orders
FROM orders_raw
WHERE dt BETWEEN DATE '2026-08-01' AND DATE '2026-08-09';$q$,
     $q$SELECT ROUND(SUM(quantity * unit_price) FILTER (WHERE is_deleted = false AND quantity > 0), 2) AS gmv,
       COUNT(DISTINCT order_id) FILTER (WHERE order_status = 'paid') AS paid_orders,
       ROUND(COUNT(*) FILTER (WHERE order_status = 'refunded') * 100.0 / NULLIF(COUNT(*), 0), 2) AS refund_rate
FROM orders_raw
WHERE dt BETWEEN DATE '2026-08-01' AND DATE '2026-08-09';$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bfilter\\s*\\("],"requiredColumns":["gmv","paid_orders","refund_rate"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"周报行","weight":40,"passed":true,"detail":"只返回一行汇总"},{"key":"metrics","name":"三口径","weight":40,"passed":true,"detail":"GMV、支付订单与退款率均正确"},{"key":"style","name":"聚合规范","weight":20,"passed":true,"detail":"使用 FILTER 与去重计数"}]$j$,
     70, 20, 'published', 1, 1,
     $j$["经营分析","指标口径"]$j$,
     $j$[{"title":"单行多指标","detail":"一次扫描即可同时计算金额、订单数与退款率。"},{"title":"退款率分母","detail":"分母是区间内的全部明细行；空区间用 NULLIF 避免除零。"}]$j$,
     $j$["teach.orders_raw@1"]$j$),

    ('metrics-m3', 'metrics-l2-05', '品类经营贡献：GMV 与退款率', 'L2', 'medium', 'SQL',
     '按品类汇总 GMV 与退款率，生成一份可复述的品类贡献表。',
     '经营会要讨论品类贡献：每个品类的 GMV 是多少，退款率是否异常，先给一张按 GMV 排序的表。',
     '输出 category、gmv（有效行金额合计，保留两位小数）、refund_rate（退款行占比百分比，保留两位小数）；按 gmv 降序、category 升序。',
     $j$[{"title":"品类汇总","detail":"先按 category 分组，再计算金额与退款率。"},{"title":"口径一致","detail":"GMV 排除逻辑删除与非正数量，退款率按全部行计算。"},{"title":"排序","detail":"按 GMV 降序，金额相同再按品类名升序。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT category, COUNT(*) AS orders
FROM orders_raw
GROUP BY category
ORDER BY orders DESC;$q$,
     $q$SELECT category,
       ROUND(SUM(quantity * unit_price) FILTER (WHERE is_deleted = false AND quantity > 0), 2) AS gmv,
       ROUND(COUNT(*) FILTER (WHERE order_status = 'refunded') * 100.0 / NULLIF(COUNT(*), 0), 2) AS refund_rate
FROM orders_raw
GROUP BY category
ORDER BY gmv DESC, category;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bfilter\\s*\\("],"requiredColumns":["gmv","refund_rate"],"orderedResult":true}$pol$::jsonb,
     $j$[{"key":"rows","name":"品类结果","weight":40,"passed":true,"detail":"每个品类一行"},{"key":"metrics","name":"贡献口径","weight":40,"passed":true,"detail":"GMV 与退款率口径正确"},{"key":"style","name":"排序规范","weight":20,"passed":true,"detail":"按 GMV 降序、品类升序"}]$j$,
     70, 20, 'published', 1, 2,
     $j$["经营分析","品类贡献"]$j$,
     $j$[{"title":"贡献表","detail":"品类粒度是经营复盘最常用的切片之一。"},{"title":"退款率对比","detail":"高 GMV 不代表高质量，退款率要一起看。"}]$j$,
     $j$["teach.orders_raw@1"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('metrics-l1-01', 'da-metric', 1.0),
    ('metrics-l1-02', 'da-metric', 1.0),
    ('metrics-l2-01', 'asset-management', 1.0),
    ('metrics-l2-02', 'dg-lineage', 1.0),
    ('metrics-l2-03', 'dg-table-rules', 1.0),
    ('metrics-l2-04', 'metric-aggregation', 1.0),
    ('metrics-l2-05', 'da-refund', 1.0)
ON CONFLICT (lab_code, skill_code) DO NOTHING;

-- 补齐空项目：用现有活跃课程任务串成端到端链路。
UPDATE learning.lab SET project_code = 'proj-gmv', updated_at = now()
 WHERE code IN ('de-l2-03', 'de-l2-05', 'de-l2-06');
UPDATE learning.lab SET project_code = 'proj-quality', updated_at = now()
 WHERE code IN ('dg-l1-01', 'dg-l1-02', 'dg-l1-03');
UPDATE learning.lab SET project_code = 'proj-lineage', updated_at = now()
 WHERE code IN ('dg-l2-01', 'dg-l2-02', 'dg-l2-03');

INSERT INTO learning.project (code, title, summary, level, scenario, deliverables, status, order_no)
VALUES
    ('proj-metric-report', '指标运营端到端项目',
     '从指标体检到经营周报，串起一条可交付的指标运营链路。', 'L2',
     '接手一个指标口径混乱的交易域：先体检字典，再评估依赖影响，最后产出一份周报。',
     '["指标体检清单","指标依赖影响面","经营周报总览"]'::jsonb, 'published', 14)
ON CONFLICT (code) DO NOTHING;

UPDATE learning.lab SET project_code = 'proj-metric-report', updated_at = now()
 WHERE code IN ('metrics-l1-01', 'metrics-l2-02', 'metrics-l2-04');
-- 数据分析路径补强：新增用户价值分层模块，补两个数据驱动分析任务
-- （窗口函数排名与占比、CASE 分层），全部基于现有教学表，不引入新数据集。
INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features)
SELECT 'course-da-basic', 'da-m3', 'L2 · 用户价值分层',
       '把订单汇总到用户粒度，再用分层指标回答“谁在持续买”。', 'L2', 3,
       '["用户分析","分层"]'::jsonb
FROM learning.course WHERE code = 'course-da-basic'
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge, dataset_refs)
VALUES
    ('da-m2', 'da-l2-04', '品类贡献排名：RANK 与窗口占比', 'L2', 'medium', 'SQL',
     '一次查询同时输出品类 GMV、排名与占比；练习窗口函数与 GROUP BY 的组合。',
     '运营要一张品类贡献榜：按 GMV 排序，并显示每个品类占总 GMV 的比例。排名与占比都要在同一条 SQL 里算完，不要导出后再手工排。',
     '输出 category、revenue、rnk（按 revenue 降序排名）、share_pct（占总 GMV 的百分比，保留两位小数），按 rnk 升序；只统计 is_deleted = false 且 quantity > 0 的行。',
     $j$[{"title":"清洗口径","detail":"先过滤逻辑删除与非正数量，避免脏数据影响排名。"},{"title":"窗口排名","detail":"在聚合结果上用 RANK() OVER 按 GMV 降序排名。"},{"title":"计算占比","detail":"用 SUM(SUM(...)) OVER () 得到全局 GMV，相除后保留两位小数。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT category, SUM(quantity * unit_price) AS revenue
FROM orders_raw
WHERE is_deleted = false
  AND quantity > 0
GROUP BY category
ORDER BY revenue DESC;$q$,
     $q$SELECT category,
       SUM(quantity * unit_price) AS revenue,
       RANK() OVER (ORDER BY SUM(quantity * unit_price) DESC) AS rnk,
       ROUND(SUM(quantity * unit_price) * 100.0 / SUM(SUM(quantity * unit_price)) OVER (), 2) AS share_pct
FROM orders_raw
WHERE is_deleted = false
  AND quantity > 0
GROUP BY category
ORDER BY rnk;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\brank\\s*\\(","\\bover\\s*\\("],"requiredColumns":["share_pct"]}$pol$::jsonb,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"每个品类一行，与标准答案一致"},{"key":"filters","name":"清洗口径","weight":40,"passed":true,"detail":"排除逻辑删除与非正数量"},{"key":"style","name":"窗口规范","weight":20,"passed":true,"detail":"排名与占比均由窗口函数完成"}]$j$,
     70, 20, 'published', 1, 4,
     $j$["数据分析","窗口函数"]$j$,
     $j$[{"title":"窗口函数与聚合共存","detail":"RANK/SUM OVER 在 GROUP BY 之后计算，可以直接在聚合结果上排名与求占比。"},{"title":"占比口径","detail":"分子是当前品类 GMV，分母是全局 GMV；空窗口 OVER () 给出全局汇总。"}]$j$,
     $j$["teach.orders_raw@1"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge, dataset_refs)
VALUES
    ('da-m3', 'da-l3-01', '用户价值分层：CASE 与平均消费', 'L2', 'medium', 'SQL',
     '按用户汇总有效订单后分层，统计各层人数与平均消费；练习 CASE 分组。',
     '运营想区分复购用户与单次用户：先把订单汇总到用户粒度，再用 CASE 打标签，最后按标签统计人数与人均消费。',
     '输出 segment（有效订单数不少于 2 为「复购」，否则「单次」）、users（用户数）、avg_spend（平均消费，保留两位小数），按 segment 升序；只统计 is_deleted = false 且 quantity > 0 的订单。',
     $j$[{"title":"用户粒度汇总","detail":"先按 user_id 统计有效订单数与消费额。"},{"title":"CASE 分层","detail":"订单数不少于 2 标记为复购，其余为单次。"},{"title":"聚合提交","detail":"按分层统计人数与平均消费，保留两位小数后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT user_id, COUNT(*) AS orders, SUM(quantity * unit_price) AS spend
FROM orders_raw
WHERE is_deleted = false
  AND quantity > 0
GROUP BY user_id
ORDER BY orders DESC, user_id;$q$,
     $q$SELECT CASE WHEN t.orders >= 2 THEN '复购' ELSE '单次' END AS segment,
       COUNT(*) AS users,
       ROUND(AVG(t.spend), 2) AS avg_spend
FROM (
    SELECT user_id, COUNT(*) AS orders, SUM(quantity * unit_price) AS spend
    FROM orders_raw
    WHERE is_deleted = false
      AND quantity > 0
    GROUP BY user_id
) t
GROUP BY 1
ORDER BY segment;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bcase\\b","\\bavg\\s*\\("],"requiredColumns":["segment"]}$pol$::jsonb,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"分层结果与标准答案一致"},{"key":"filters","name":"清洗口径","weight":40,"passed":true,"detail":"仅统计未删除且数量为正的订单"},{"key":"style","name":"CASE 规范","weight":20,"passed":true,"detail":"分层用 CASE 完成，聚合到用户粒度"}]$j$,
     70, 25, 'published', 1, 1,
     $j$["用户分析","分层"]$j$,
     $j$[{"title":"先汇总再分层","detail":"分层指标依赖用户粒度的订单数与消费额，子查询先做聚合，外层再打标签。"},{"title":"CASE 与分组","detail":"CASE 产生新维度，可以像普通字段一样参与 GROUP BY 与排序。"}]$j$,
     $j$["teach.orders_raw@1"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('da-l2-04', 'da-segment', 1.0),
    ('da-l3-01', 'da-segment', 1.0)
ON CONFLICT (lab_code, skill_code) DO NOTHING;
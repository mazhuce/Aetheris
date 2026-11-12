-- L3/L4 扩展内容：两个端到端项目、六个任务、四个技能节点。
-- 所有任务仍基于 orders_raw，保证在 Light 沙箱与外部 MCP 只读工具中可复现。

INSERT INTO learning.project (code, title, summary, level, scenario, deliverables, status, order_no) VALUES
    ('proj-order-analytics', '订单分析端到端项目', '从有效订单基线出发，完成用户分层、留存与转化漏斗，形成一份可复用的经营分析包。', 'L3',
     '你是电商数据团队的分析工程师。运营要复盘 8 月大促：哪些用户值得重点维护、次日留存是否健康、支付到完成哪一步流失最多。',
     $j$["用户分层结果表","次日留存率日报","支付-发货-完成漏斗","口径说明与复盘结论"]$j$, 'published', 1),
    ('proj-quality-production', '质量与生产化项目', '把清洗逻辑落成可按日重跑的增量快照，并建立质量基线与趋势监控。', 'L4',
     '数据平台要接入每日调度。你需要证明这条链路可重复执行、指标可核对，并且能提前发现脏数据。',
     $j$["按日增量快照","质量基线四项指标","GMV 三日移动平均","重跑与验收说明"]$j$, 'published', 2)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.skill_node (path_code, code, parent_code, title, level, knowledge_points, outcome, order_no) VALUES
    ('de-foundation', 'analytics-modeling', NULL, '分析建模', 'L3',
     $j$["用户分层","留存口径","漏斗转化"]$j$, '能把业务问题翻译成可复现的分析 SQL', 7),
    ('de-foundation', 'retention-analysis', NULL, '留存与 cohort', 'L3',
     $j$["首次活跃","次日留存","cohort 分组"]$j$, '能独立计算并解释留存指标', 8),
    ('de-foundation', 'production-pipeline', NULL, '增量与可重跑', 'L4',
     $j$["分区快照","窗口去重","幂等重跑","移动平均"]$j$, '能写出可按日重跑的生产级 SQL', 9),
    ('de-foundation', 'quality-governance', NULL, '质量基线与监控', 'L4',
     $j$["空值率","重复率","非法枚举","阈值思维"]$j$, '能定义质量基线并解释告警阈值', 10)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no) VALUES
    ('course-de-foundation', 'm-l3-analytics', 'L3 经营分析', '把订单明细转成用户分层、留存与漏斗三类经营指标。', 'L3', 3),
    ('course-de-foundation', 'm-l4-production', 'L4 生产化与质量', '产出可重跑的增量快照、质量基线与趋势指标。', 'L4', 4)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     project_code, author, review_status, published_at)
VALUES
    ('m-l3-analytics', 'l3-rfm-segments', '用户 RFM 分层', 'L3', 'medium', 'SQL',
     '按最近购买、购买频次、消费金额给用户分层，输出可直接交付运营的分群表。',
     '运营准备针对不同价值用户做差异化触达。你需要以 2026-08-10 为观察日，输出每个用户的 R/F/M 与分群结果。',
     '输出 user_id、recency_days、frequency、monetary、segment 五列，按 user_id 升序。',
     $j$[{"title":"先清洗","detail":"按 order_id 去重，只保留未删除、有用户、数量为正、状态有效的订单。"},{"title":"算 RFM","detail":"R 用观察日减最近购买日，F 用订单数，M 用金额合计。"},{"title":"分群","detail":"最近 2 天且有复购记高价值；最近 4 天记潜力；其余记流失风险。"},{"title":"自检","detail":"每个用户只出现一行，金额与有效订单基线一致。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$WITH clean AS (
  SELECT DISTINCT ON (order_id) order_id, user_id, quantity, unit_price, dt
  FROM orders_raw
  ORDER BY order_id, updated_at DESC
)
SELECT user_id, MAX(dt) AS last_dt, COUNT(*) AS frequency
FROM clean GROUP BY user_id$q$,
     $q$WITH clean AS (
  SELECT DISTINCT ON (order_id)
         order_id, user_id, quantity, unit_price, dt
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
  ORDER BY order_id, updated_at DESC
)
SELECT user_id,
       DATE '2026-08-10' - MAX(dt) AS recency_days,
       COUNT(*) AS frequency,
       SUM(quantity * unit_price) AS monetary,
       CASE
         WHEN DATE '2026-08-10' - MAX(dt) <= 2 AND COUNT(*) >= 2 THEN '高价值'
         WHEN DATE '2026-08-10' - MAX(dt) <= 4 THEN '潜力'
         ELSE '流失风险'
       END AS segment
FROM clean
GROUP BY user_id
ORDER BY user_id$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["distinct\\s+on","group\\s+by"],"requiredColumns":["is_deleted","order_status","quantity"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"去重与分层结构","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 35, 'published', 1, 1, 'proj-order-analytics', 'Aetheris 教学组', 'approved', now()),

    ('m-l3-analytics', 'l3-daily-retention', '次日留存率', 'L3', 'medium', 'SQL',
     '按用户首次活跃日分组，计算次日留存率，输出 cohort 日报。',
     '增长团队需要判断新用户是否留得住。请按首次下单日划分 cohort，计算每个 cohort 的次日留存率。',
     '输出 dt、cohort_size、retained_users、d1_rate 四列，按 dt 升序。',
     $j$[{"title":"定义活跃","detail":"有效订单的用户-日期去重集合就是活跃集合。"},{"title":"找首次","detail":"每个用户取 MIN(dt) 作为 cohort 日期。"},{"title":"算留存","detail":"次日仍出现在活跃集合中的用户计为留存。"},{"title":"换算比率","detail":"留存数除以 cohort 规模，保留两位小数。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$WITH activity AS (
  SELECT DISTINCT user_id, dt FROM orders_raw
)
SELECT user_id, MIN(dt) AS first_dt FROM activity GROUP BY user_id$q$,
     $q$WITH activity AS (
  SELECT DISTINCT user_id, dt
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
), first_seen AS (
  SELECT user_id, MIN(dt) AS first_dt FROM activity GROUP BY user_id
), cohorts AS (
  SELECT f.first_dt AS dt,
         COUNT(*) AS cohort_size,
         COUNT(*) FILTER (WHERE EXISTS (
             SELECT 1 FROM activity a
             WHERE a.user_id = f.user_id AND a.dt = f.first_dt + 1
         )) AS retained_users
  FROM first_seen f
  GROUP BY f.first_dt
)
SELECT dt, cohort_size, retained_users,
       ROUND(retained_users * 100.0 / NULLIF(cohort_size, 0), 2) AS d1_rate
FROM cohorts
ORDER BY dt$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["filter\\s*\\(","group\\s+by"],"requiredColumns":["is_deleted","order_status"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"cohort 结构","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 40, 'published', 1, 2, 'proj-order-analytics', 'Aetheris 教学组', 'approved', now()),

    ('m-l3-analytics', 'l3-funnel-rates', '支付-发货-完成漏斗', 'L3', 'medium', 'SQL',
     '计算支付、发货、完成三个环节的订单量与转化率。',
     '履约团队想定位流失环节。请以去重后的有效订单为基数，给出三个环节的订单量和相对支付的转化率。',
     '输出 paid_orders、shipped_orders、completed_orders、ship_rate、complete_rate 五列，单行结果。',
     $j$[{"title":"确定基数","detail":"先按 order_id 去重，避免重复订单放大漏斗。"},{"title":"分环节计数","detail":"FILTER 子句分别统计发货和完成订单。"},{"title":"算转化率","detail":"用 NULLIF 防止除零，百分比保留两位小数。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT COUNT(*) AS paid_orders FROM orders_raw$q$,
     $q$WITH clean AS (
  SELECT DISTINCT ON (order_id) order_id, order_status
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
  ORDER BY order_id, updated_at DESC
), funnel AS (
  SELECT COUNT(*) AS paid_orders,
         COUNT(*) FILTER (WHERE order_status IN ('shipped','completed')) AS shipped_orders,
         COUNT(*) FILTER (WHERE order_status = 'completed') AS completed_orders
  FROM clean
)
SELECT paid_orders,
       shipped_orders,
       completed_orders,
       ROUND(shipped_orders * 100.0 / NULLIF(paid_orders, 0), 2) AS ship_rate,
       ROUND(completed_orders * 100.0 / NULLIF(paid_orders, 0), 2) AS complete_rate
FROM funnel$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["filter\\s*\\("],"requiredColumns":["is_deleted","order_status"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"漏斗口径","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 35, 'published', 1, 3, 'proj-order-analytics', 'Aetheris 教学组', 'approved', now()),

    ('m-l4-production', 'l4-incremental-snapshot', '按日增量快照', 'L4', 'hard', 'SQL',
     '按 dt 分区去重，输出每日订单数与 GMV，保证同一天重跑结果一致。',
     '调度即将上线，需要一条可重复执行的日增量 SQL。重复投递和跨天更新都可能出现，必须在分区内保留最新记录。',
     '输出 dt、order_cnt、gmv 三列，按 dt 升序；同一 dt 重跑结果必须一致。',
     $j$[{"title":"分区去重","detail":"窗口分区键是 dt + order_id，排序键是 updated_at。"},{"title":"过滤脏数据","detail":"未删除、有用户、数量为正、状态有效。"},{"title":"聚合","detail":"只统计 rn=1 的记录，输出订单数与金额。"},{"title":"验证重跑","detail":"同一 SQL 连续执行两次，结果行数与金额完全一致。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT dt, COUNT(*) AS order_cnt, SUM(quantity * unit_price) AS gmv
FROM orders_raw
GROUP BY dt$q$,
     $q$WITH ranked AS (
  SELECT dt, order_id, quantity, unit_price,
         ROW_NUMBER() OVER (PARTITION BY dt, order_id ORDER BY updated_at DESC) AS rn
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
)
SELECT dt,
       COUNT(*) AS order_cnt,
       SUM(quantity * unit_price) AS gmv
FROM ranked
WHERE rn = 1
GROUP BY dt
ORDER BY dt$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge"],"requiredPatterns":["row_number\\s*\\(","partition\\s+by"],"requiredColumns":["is_deleted","order_status","group by"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"增量与去重结构","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 45, 'published', 1, 1, 'proj-quality-production', 'Aetheris 教学组', 'approved', now()),

    ('m-l4-production', 'l4-quality-baseline', '质量基线四项指标', 'L4', 'hard', 'SQL',
     '一条 SQL 输出空用户率、重复率、非法状态率、非法数量率。',
     '质量平台要为订单表建立基线。请用一条 SQL 给出四项比例，作为后续告警阈值的依据。',
     '输出 null_user_rate、duplicate_rate、invalid_status_rate、invalid_qty_rate 四列，单行结果。',
     $j$[{"title":"统一分母","detail":"四项比例都以原始表总行数为分母。"},{"title":"空用户率","detail":"COUNT(*) FILTER (WHERE user_id IS NULL)。"},{"title":"重复率","detail":"总行数减去 distinct order_id 再除以总行数。"},{"title":"非法枚举与数量","detail":"状态不在合法集合内、数量小于等于 0 分别计数。"}]$j$,
     $j$["teach.orders_raw","teach.order_status_dict"]$j$,
     $q$SELECT COUNT(*) AS total_rows FROM orders_raw$q$,
     $q$SELECT
       ROUND(100.0 * COUNT(*) FILTER (WHERE user_id IS NULL) / COUNT(*), 2) AS null_user_rate,
       ROUND(100.0 * (COUNT(*) - COUNT(DISTINCT order_id)) / COUNT(*), 2) AS duplicate_rate,
       ROUND(100.0 * COUNT(*) FILTER (WHERE order_status NOT IN
             ('paid','shipped','completed','refunded','cancelled')) / COUNT(*), 2) AS invalid_status_rate,
       ROUND(100.0 * COUNT(*) FILTER (WHERE quantity <= 0) / COUNT(*), 2) AS invalid_qty_rate
FROM orders_raw$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["filter\\s*\\("],"requiredColumns":["user_id","order_status","quantity"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"指标覆盖度","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 40, 'published', 1, 2, 'proj-quality-production', 'Aetheris 教学组', 'approved', now()),

    ('m-l4-production', 'l4-daily-gmv-rolling', 'GMV 三日移动平均', 'L4', 'hard', 'SQL',
     '在日粒度 GMV 上计算三日移动平均，用于趋势判断。',
     '经营看板需要平滑短期波动。请先算日 GMV，再用窗口函数给出包含当天的三日移动平均。',
     '输出 dt、gmv、gmv_ma3 三列，按 dt 升序。',
     $j$[{"title":"日粒度","detail":"先去重有效订单，再按 dt 汇总 GMV。"},{"title":"移动窗口","detail":"窗口按 dt 排序，范围是前两行到当前行。"},{"title":"保留精度","detail":"移动平均保留两位小数，便于看板展示。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT dt, SUM(quantity * unit_price) AS gmv
FROM orders_raw
GROUP BY dt$q$,
     $q$WITH clean AS (
  SELECT DISTINCT ON (order_id) order_id, dt, quantity, unit_price
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
  ORDER BY order_id, updated_at DESC
), daily AS (
  SELECT dt, SUM(quantity * unit_price) AS gmv
  FROM clean
  GROUP BY dt
)
SELECT dt,
       gmv,
       ROUND(AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2) AS gmv_ma3
FROM daily
ORDER BY dt$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredPatterns":["over\\s*\\(","rows\\s+between"],"requiredColumns":["group by","order by"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"窗口与聚合结构","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 35, 'published', 1, 3, 'proj-quality-production', 'Aetheris 教学组', 'approved', now())
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('l3-rfm-segments', 'analytics-modeling', 100),
    ('l3-rfm-segments', 'retention-analysis', 50),
    ('l3-daily-retention', 'retention-analysis', 100),
    ('l3-funnel-rates', 'analytics-modeling', 100),
    ('l4-incremental-snapshot', 'production-pipeline', 100),
    ('l4-quality-baseline', 'quality-governance', 100),
    ('l4-daily-gmv-rolling', 'production-pipeline', 100)
ON CONFLICT (lab_code, skill_code) DO NOTHING;

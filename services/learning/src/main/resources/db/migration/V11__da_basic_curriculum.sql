-- 数据分析师入门：围绕指标口径、分组对比、趋势与转化，补齐第 5 条职业路径。
-- 所有任务只使用 teach 教学表；expected_sql 已在等价沙箱 schema 中验证可执行。

INSERT INTO learning.learning_path (code, title, description, target_role, level, version, status, estimated_hours, order_no) VALUES
    ('da-basic', '数据分析师入门', '从指标口径到分组对比与趋势分析：用真实订单与支付数据回答业务问题，对应平台的数据分析、指标资产与看板能力。', '数据分析师', 'L1-L2', 1, 'published', 36, 5)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.skill_node (path_code, code, parent_code, title, level, knowledge_points, outcome, order_no) VALUES
    ('da-basic', 'da-metric', NULL, '指标口径', 'L1', $j$["成功率","分母口径","ROUND 精度"]$j$, '能写出可复算的比率型指标', 1),
    ('da-basic', 'da-segment', NULL, '分组对比', 'L1', $j$["GROUP BY","JOIN 放大","COUNT DISTINCT"]$j$, '能按维度产出可信的分组对比', 2),
    ('da-basic', 'da-trend', NULL, '趋势与环比', 'L2', $j$["LAG","NULLIF","窗口排序"]$j$, '能计算日环比并解释波动', 3),
    ('da-basic', 'da-funnel', NULL, '转化漏斗', 'L2', $j$["LEFT JOIN","转化率","可加性"]$j$, '能按注册到下单口径计算转化', 4),
    ('da-basic', 'da-refund', NULL, '退款与异常', 'L2', $j$["退款率","去重计数","异常识别"]$j$, '能定位退款异常并量化影响', 5)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course (path_code, code, title, summary, version, status, author) VALUES
    ('da-basic', 'course-da-basic', '数据分析师入门（实战）', '从指标口径到分组对比与趋势分析：用真实订单与支付数据回答业务问题，对应平台的数据分析、指标资产与看板能力。', 1, 'published', 'Aetheris 教学组')
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features) VALUES
    ('course-da-basic', 'da-m1', 'L1 · 指标口径与分组对比', '先把比率型指标的分母说清楚，再按品类与城市做可信的分组对比。', 'L1', 1, $j$["数据分析","指标资产"]$j$),
    ('course-da-basic', 'da-m2', 'L2 · 趋势、转化与退款', '用窗口函数看趋势，用漏斗口径看转化，用退款率定位异常。', 'L2', 2, $j$["数据分析","数据质量","数据看板"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.project (code, title, summary, level, scenario, deliverables, status, order_no) VALUES
    ('proj-analysis-report', '电商经营分析周报', '把支付、品类、转化与退款四个指标串成一份可复算的经营周报。', 'L2', '为电商业务产出一份周报：支付成功率、品类销售排行、注册转化与退款率，每个指标都能追溯到 SQL 与口径说明。', $j$["支付成功率日报","品类销售排行","注册转化与退款分析"]$j$, 'published', 13)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     project_code, related_features, knowledge)
VALUES
    ('da-m1', 'da-l1-01', '支付成功率日报：FILTER 聚合', 'L1', 'easy', 'SQL',
     '用条件聚合算出每天的成功支付笔数与成功率，理解比率指标的分母。',
     '支付组每天晨会第一张图是"支付成功率"。支付流水表 payments 里既有成功也有失败与重复流水；口径必须写死为：成功笔数除以全部支付笔数（含失败），按 dt 分组。',
     '输出 dt、success_pays、total_pays、success_rate（保留两位小数），按 dt 升序。',
     $j$[{"title":"先看状态分布","detail":"按 pay_status 分组计数，确认成功状态的字面值与失败笔数。"},{"title":"条件聚合","detail":"用 COUNT(*) FILTER (WHERE pay_status = ''SUCCESS'') 统计成功笔数，总数用 COUNT(*)。"},{"title":"比率与精度","detail":"用 * 100.0 避免整数除法，ROUND(..., 2) 保留两位小数后提交。"}]$j$,
     $j$["teach.payments"]$j$,
     $q$SELECT dt, pay_status
FROM payments
LIMIT 100;$q$,
     $q$SELECT dt,
       COUNT(*) FILTER (WHERE pay_status = 'SUCCESS') AS success_pays,
       COUNT(*) AS total_pays,
       ROUND(COUNT(*) FILTER (WHERE pay_status = 'SUCCESS') * 100.0 / COUNT(*), 2) AS success_rate
FROM payments
GROUP BY dt
ORDER BY dt;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"pay_status\",\"dt\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"成功与总数为同一分母"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"别名与两位小数符合要求"}]$j$,
     60, 15, 'published', 1, 1, NULL,
     $j$["数据分析","指标资产"]$j$,
     $j$[{"title":"条件聚合 FILTER","detail":"COUNT(*) FILTER (WHERE 条件) 等价于 SUM(CASE WHEN 条件 THEN 1 ELSE 0 END)，在 Postgres 更易读。"},{"title":"比率的分母","detail":"成功率必须同时说明分子和分母。只写 SUM(status=''SUCCESS'') 而不交代分母，是无法复算的口径。"}]$j$),
    ('da-m1', 'da-l1-02', '品类销售排行：先清洗再聚合', 'L1', 'easy', 'SQL',
     '排除逻辑删除与非正数量后，按品类汇总销售额并排序。',
     '运营要看"哪个品类卖得最好"。orders_raw 里存在逻辑删除、负数量与零价脏数据；直接 SUM 会把退款与错误行算进销售额。',
     '输出 category、revenue、orders，按 revenue 降序；只统计 is_deleted = false 且 quantity > 0 的行。',
     $j$[{"title":"确认脏数据","detail":"分别统计 is_deleted、quantity <= 0 的行数，理解它们对销售额的影响。"},{"title":"过滤后聚合","detail":"在 WHERE 里排除逻辑删除与非正数量，再按 category 汇总 quantity * unit_price。"},{"title":"排序与核对","detail":"按 revenue 降序，核对订单数用 COUNT(DISTINCT order_id)。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT category, SUM(quantity * unit_price) AS revenue
FROM orders_raw
GROUP BY category;$q$,
     $q$SELECT category,
       SUM(quantity * unit_price) AS revenue,
       COUNT(DISTINCT order_id) AS orders
FROM orders_raw
WHERE is_deleted = false
  AND quantity > 0
GROUP BY category
ORDER BY revenue DESC;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"category\",\"quantity\",\"unit_price\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"排除逻辑删除与非正数量"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"聚合别名符合要求"}]$j$,
     60, 20, 'published', 1, 2, NULL,
     $j$["数据分析"]$j$,
     $j$[{"title":"GIGO：垃圾进垃圾出","detail":"聚合前先定义「有效行」。逻辑删除、负数量、零价必须在 WHERE 里显式处理，否则结果无法解释。"},{"title":"COUNT DISTINCT 的用途","detail":"同一订单可能有多行明细，COUNT(*) 会放大订单数；按 order_id 去重才能得到真实订单量。"}]$j$),
    ('da-m1', 'da-l1-03', '城市客单价：关联后的聚合口径', 'L1', 'medium', 'SQL',
     '把订单与用户表关联，按城市计算订单数与客单价。',
     '市场部要按城市对比客单价。用户表 users 有 25 个注册用户，其中 2 个手机号为空；订单表里部分订单的 user_id 为空（游客单）。统计只能覆盖能关联到用户的有效订单。',
     '输出 city、orders、avg_order_value（两位小数），按客单价降序；只统计未删除、数量为正、user_id 非空的订单。',
     $j$[{"title":"先看关联键","detail":"确认 users.user_id 与 orders_raw.user_id 的对应关系，以及空值的处理。"},{"title":"LEFT/INNER 的选择","detail":"本任务需要用户城市，关联不上的游客单无法归城，使用 INNER JOIN 并说明口径。"},{"title":"去重与除法","detail":"订单数用 COUNT(DISTINCT order_id)；客单价 = 销售额 / 订单数，用 NULLIF 防御除零。"}]$j$,
     $j$["teach.orders_raw","teach.users"]$j$,
     $q$SELECT u.city, COUNT(*) AS orders
FROM orders_raw o
JOIN users u ON u.user_id = o.user_id
GROUP BY u.city;$q$,
     $q$SELECT u.city,
       COUNT(DISTINCT o.order_id) AS orders,
       ROUND(SUM(o.quantity * o.unit_price) / NULLIF(COUNT(DISTINCT o.order_id), 0), 2) AS avg_order_value
FROM orders_raw o
JOIN users u ON u.user_id = o.user_id
WHERE o.is_deleted = false
  AND o.quantity > 0
  AND o.user_id IS NOT NULL
GROUP BY u.city
ORDER BY avg_order_value DESC;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"city\",\"user_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"关联与去重口径正确"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"除零防御与别名符合要求"}]$j$,
     60, 25, 'published', 1, 3, NULL,
     $j$["数据分析","数据建模"]$j$,
     $j$[{"title":"JOIN 放大","detail":"一对多关联会让行数膨胀。先想清楚「一行代表什么」，再决定 COUNT(*) 还是 COUNT(DISTINCT 业务主键)。"},{"title":"NULLIF 防御除零","detail":"NULLIF(分母, 0) 把 0 变成 NULL，避免 division by zero；结果为 NULL 表示该维度没有有效样本。"}]$j$),
    ('da-m2', 'da-l2-01', '日 GMV 环比：窗口函数 LAG', 'L2', 'medium', 'SQL',
     '按日汇总 GMV，并用 LAG 计算环比增长率。',
     '经营周会要解释"昨天为什么涨/跌"。先用明细算出每日 GMV，再用窗口函数取前一天的值，计算环比百分比；第一天没有前一天，环比应为 NULL 而不是 0。',
     '输出 dt、gmv、prev_gmv、growth_pct（两位小数），按 dt 升序；排除逻辑删除与非正数量。',
     $j$[{"title":"先聚合到日粒度","detail":"用 CTE 按 dt 汇总 GMV，窗口函数在聚合结果上计算。"},{"title":"取前一天","detail":"LAG(gmv) OVER (ORDER BY dt)；不要用自关联，窗口函数更清晰。"},{"title":"环比与空值","detail":"(gmv - prev) / NULLIF(prev, 0) * 100；第一天 prev 为 NULL，增长率保持 NULL。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$WITH daily AS (
  SELECT dt, SUM(quantity * unit_price) AS gmv
  FROM orders_raw
  GROUP BY dt
)
SELECT dt, gmv FROM daily ORDER BY dt;$q$,
     $q$WITH daily AS (
  SELECT dt, SUM(quantity * unit_price) AS gmv
  FROM orders_raw
  WHERE is_deleted = false AND quantity > 0
  GROUP BY dt
)
SELECT dt,
       gmv,
       LAG(gmv) OVER (ORDER BY dt) AS prev_gmv,
       ROUND((gmv - LAG(gmv) OVER (ORDER BY dt)) * 100.0
             / NULLIF(LAG(gmv) OVER (ORDER BY dt), 0), 2) AS growth_pct
FROM daily
ORDER BY dt;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"lag\",\"gmv\",\"dt\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"窗口排序与过滤口径正确"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"CTE 与 NULLIF 使用符合要求"}]$j$,
     60, 25, 'published', 1, 1, 'proj-analysis-report',
     $j$["数据分析","数据看板"]$j$,
     $j$[{"title":"窗口函数不折叠行","detail":"LAG/LEAD 在保留明细行的同时取相邻行的值，适合环比、同比和相邻事件分析。"},{"title":"NULL 不是 0","detail":"第一天没有前一天，增长率应为 NULL。把 NULL 当 0 会得出「从 0 增长」的错误结论。"}]$j$),
    ('da-m2', 'da-l2-02', '注册转化率：漏斗的第一个口径', 'L2', 'medium', 'SQL',
     '计算注册用户中有多少人真正下过有效订单。',
     '增长团队要看注册到下单的转化。users 是注册表，orders_raw 是订单表；有效订单定义为未删除且数量为正。转化率 = 下单用户数 / 注册用户数。',
     '输出 registered_users、buyers、conversion_rate（两位小数），单行结果。',
     $j$[{"title":"确定分母","detail":"分母是全部注册用户（COUNT(DISTINCT user_id) FROM users），不是订单用户。"},{"title":"LEFT JOIN 保底","detail":"以 users 为主表 LEFT JOIN 订单，保证没有下单的注册用户也被计入分母。"},{"title":"去重与比率","detail":"下单用户数用 COUNT(DISTINCT o.user_id)；比率乘 100.0 并保留两位小数。"}]$j$,
     $j$["teach.users","teach.orders_raw"]$j$,
     $q$SELECT COUNT(*) AS registered_users
FROM users;$q$,
     $q$SELECT COUNT(DISTINCT u.user_id) AS registered_users,
       COUNT(DISTINCT o.user_id) AS buyers,
       ROUND(COUNT(DISTINCT o.user_id) * 100.0 / COUNT(DISTINCT u.user_id), 2) AS conversion_rate
FROM users u
LEFT JOIN orders_raw o
  ON o.user_id = u.user_id
 AND o.is_deleted = false
 AND o.quantity > 0;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"users\",\"orders_raw\",\"left join\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"单行结果与标准答案一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"分母为注册用户且包含未下单者"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"LEFT JOIN 与去重计数符合要求"}]$j$,
     60, 25, 'published', 1, 2, 'proj-analysis-report',
     $j$["数据分析","指标资产"]$j$,
     $j$[{"title":"漏斗口径","detail":"漏斗每一层的分母必须写清楚。注册转化率的分母是注册用户，不是访问用户，也不是下单用户。"},{"title":"LEFT JOIN 的过滤位置","detail":"把订单条件写在 ON 里才能保留未下单用户；写在 WHERE 里会把 LEFT JOIN 退化成 INNER JOIN。"}]$j$),
    ('da-m2', 'da-l2-03', '退款率：异常订单的量化', 'L2', 'medium', 'SQL',
     '按日统计订单数与退款订单数，计算退款率并定位异常日。',
     '财务发现某天退款明显变多。refunds 是退款流水，一个订单可能有多条退款；orders_raw 里还有逻辑删除行。退款率口径：发生退款的订单数 / 当日有效订单数。',
     '输出 dt、orders、refunded_orders、refund_rate（两位小数），按 dt 升序；只统计未删除订单。',
     $j$[{"title":"先看退款粒度","detail":"统计 refunds 每个订单的流水条数，确认需要按 order_id 去重。"},{"title":"LEFT JOIN 保留未退款订单","detail":"以有效订单为主表 LEFT JOIN 退款，COUNT(DISTINCT r.order_id) 统计退款订单数。"},{"title":"比率与排序","detail":"退款率保留两位小数，按日期升序输出；检查是否有异常高的日期。"}]$j$,
     $j$["teach.orders_raw","teach.refunds"]$j$,
     $q$SELECT dt, COUNT(*) AS refunds
FROM refunds
GROUP BY dt;$q$,
     $q$SELECT o.dt,
       COUNT(DISTINCT o.order_id) AS orders,
       COUNT(DISTINCT r.order_id) AS refunded_orders,
       ROUND(COUNT(DISTINCT r.order_id) * 100.0 / COUNT(DISTINCT o.order_id), 2) AS refund_rate
FROM orders_raw o
LEFT JOIN refunds r ON r.order_id = o.order_id
WHERE o.is_deleted = false
GROUP BY o.dt
ORDER BY o.dt;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"refunds\",\"order_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"退款订单按订单去重"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"LEFT JOIN 与比率写法符合要求"}]$j$,
     60, 25, 'published', 1, 3, 'proj-analysis-report',
     $j$["数据分析","数据质量"]$j$,
     $j$[{"title":"事实表的粒度","detail":"refunds 的一行是一次退款流水，不是一个退款订单。统计订单级指标必须先按 order_id 去重。"},{"title":"异常日排查","detail":"退款率突然升高时，先看退款流水的时间分布与订单来源，再决定是数据问题还是业务问题。"}]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('da-l1-01', 'da-metric', 1.0),
    ('da-l1-02', 'da-segment', 1.0),
    ('da-l1-03', 'da-segment', 1.0),
    ('da-l2-01', 'da-trend', 1.0),
    ('da-l2-02', 'da-funnel', 1.0),
    ('da-l2-03', 'da-refund', 1.0)
ON CONFLICT (lab_code, skill_code) DO NOTHING;
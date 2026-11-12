-- L1-L2 起始课程：1 条路径、6 个技能节点、2 个模块、7 个实验任务。
-- 课程内容版本化，后续新增任务走 learning-service 管理接口并递增 version。

INSERT INTO learning.learning_path (code, title, description, target_role, level, version, status, estimated_hours, order_no)
VALUES ('de-foundation', '数据开发基础路径（L1-L2）',
        '从 SQL 与元数据认知开始，完成 ODS 到 DWD/ADS 的清洗、去重、聚合与质量核查，建立企业数据开发的第一条完整链路。',
        '初级数据开发工程师', 'L1-L2', 1, 'published', 48, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.skill_node (path_code, code, parent_code, title, level, knowledge_points, outcome, order_no) VALUES
    ('de-foundation', 'sql-basics', NULL, 'SQL 查询与安全边界', 'L1',
     $j$["SELECT 投影","WHERE 过滤","NULL 语义","只读边界"]$j$, '能写出可解释、可审查的只读查询', 1),
    ('de-foundation', 'metadata-basics', NULL, '元数据与表结构认知', 'L1',
     $j$["表结构","字段注释","分区字段","数据字典"]$j$, '能在开发前确认表、字段和分区含义', 2),
    ('de-foundation', 'data-quality-basics', NULL, '脏数据识别', 'L1',
     $j$["空值","重复","非法枚举","逻辑删除"]$j$, '能定位常见脏数据并说明业务影响', 3),
    ('de-foundation', 'ods-to-dwd', NULL, 'ODS 到 DWD 清洗', 'L2',
     $j$["去重策略","ROW_NUMBER","状态过滤","幂等重跑"]$j$, '能产出可重复执行的 DWD 清洗 SQL', 4),
    ('de-foundation', 'metric-aggregation', NULL, '指标聚合与口径', 'L2',
     $j$["金额计算","CASE 映射","分组聚合","COUNT DISTINCT"]$j$, '能按明确口径产出 ADS 汇总', 5),
    ('de-foundation', 'quality-sql', NULL, '质量核查 SQL', 'L2',
     $j$["违规识别","阈值思维","核查可复现"]$j$, '能写出可复现的质量核查语句', 6)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course (path_code, code, title, summary, version, status, author)
VALUES ('de-foundation', 'course-de-foundation', '企业电商数仓入门', '以合成电商订单数据为载体，完成从原始表到可用指标的完整开发链路。', 1, 'published', 'Aetheris 教学组')
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no) VALUES
    ('course-de-foundation', 'm-l1-sql', 'L1 查询与数据认知',
     '读懂表结构，写出安全的只读查询，识别空值、重复与非法枚举。', 'L1', 1),
    ('course-de-foundation', 'm-l2-warehouse', 'L2 离线数仓开发',
     '完成 ODS 清洗、DWD 去重、ADS 聚合和质量核查四类核心任务。', 'L2', 2)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no)
VALUES
    ('m-l1-sql', 'l1-valid-orders', '找出全部有效订单明细', 'L1', 'easy', 'SQL',
     '按业务口径过滤逻辑删除、空用户、负数量和无效状态，返回有效订单明细。',
     '运营同学反馈日报订单数偏高。数据团队怀疑原始表混入了删除单、异常单和测试单。你需要先给出一份“有效订单”明细，作为后续所有指标的基线。',
     '写出只读查询，返回有效订单的 8 个业务字段。',
     $j$[{"title":"读需求","detail":"明确“有效订单”的四个条件：未删除、用户存在、数量为正、状态属于已支付/已发货/已完成。"},{"title":"看表结构","detail":"确认 orders_raw 的字段含义与状态字典。"},{"title":"写查询","detail":"逐条落实过滤条件，不要使用 SELECT *。"},{"title":"自检","detail":"确认没有把 refunded / cancelled 计入有效订单。"}]$j$,
     $j$["teach.orders_raw","teach.order_status_dict"]$j$,
     $q$SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at
FROM orders_raw
WHERE 1 = 1$q$,
     $q$SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at
FROM orders_raw
WHERE is_deleted = false
  AND user_id IS NOT NULL
  AND quantity > 0
  AND order_status IN ('paid','shipped','completed')$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"requiredColumns":["is_deleted","user_id","quantity","order_status"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"过滤条件完整性","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 20, 'published', 1, 1),

    ('m-l1-sql', 'l1-null-audit', '空用户订单影响面排查', 'L1', 'easy', 'SQL',
     '统计 user_id 为空的订单数，量化脏数据对用户维度分析的影响。',
     '用户增长团队发现“下单用户数”与订单数对不上。你需要先量化有多少订单缺少 user_id，再决定清洗策略。',
     '返回一个数值列，表示 user_id 为空的订单数量。',
     $j$[{"title":"定位字段","detail":"user_id 允许为空，说明接入侧没有做强校验。"},{"title":"写统计","detail":"使用 COUNT(*) 并给出清晰别名。"},{"title":"解释影响","detail":"空用户订单无法进入用户维度汇总，必须在 DWD 层隔离。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT COUNT(*) AS null_user_orders
FROM orders_raw
WHERE$q$,
     $q$SELECT COUNT(*) AS null_user_orders
FROM orders_raw
WHERE user_id IS NULL$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredColumns":["user_id","is null"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"空值判断写法","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 15, 'published', 1, 2),

    ('m-l1-sql', 'l1-partition-count', '按分区统计每日订单量', 'L1', 'easy', 'SQL',
     '按 dt 分区统计未删除且状态有效的订单量，验证分区裁剪意识。',
     '调度同学需要一份按天订单量趋势，用于核对上游补数是否完整。',
     '返回 dt 与订单量两列，按 dt 升序。',
     $j$[{"title":"理解分区","detail":"dt 是业务日期分区字段，按 dt 聚合可以直接命中分区。"},{"title":"写聚合","detail":"GROUP BY dt，并给出清晰别名。"},{"title":"检查口径","detail":"只统计未删除且状态有效的订单。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT dt, COUNT(*) AS order_cnt
FROM orders_raw
GROUP BY dt$q$,
     $q$SELECT dt, COUNT(*) AS order_cnt
FROM orders_raw
WHERE is_deleted = false
  AND order_status IN ('paid','shipped','completed')
GROUP BY dt
ORDER BY dt$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredColumns":["dt","group by"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"分区聚合写法","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 20, 'published', 1, 3),

    ('m-l2-warehouse', 'l2-dwd-dedup', 'DWD 订单去重清洗', 'L2', 'medium', 'SQL',
     '使用窗口函数按 order_id 去重，保留最新更新记录，并过滤全部脏数据。',
     '上游接入重复投递，同一个 order_id 出现多条记录。DWD 层必须保证一单一行，否则所有金额指标都会翻倍。',
     '输出一单一行，字段与有效订单明细一致，按 updated_at 取最新。',
     $j$[{"title":"识别重复","detail":"按 order_id 分组计数即可发现重复。"},{"title":"选择保留策略","detail":"按 updated_at 倒序编号，保留 rn = 1。"},{"title":"清洗脏数据","detail":"同时过滤逻辑删除、空用户、负数量与非法状态。"},{"title":"自检","detail":"确认输出行数等于去重后的有效订单数。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$WITH ranked AS (
  SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at, updated_at,
         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) AS rn
  FROM orders_raw
)
SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at
FROM ranked
WHERE rn = 1$q$,
     $q$WITH ranked AS (
  SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at, updated_at,
         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC, order_id) AS rn
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
)
SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at
FROM ranked
WHERE rn = 1$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge"],"requiredPatterns":["row_number\\s*\\(","partition\\s+by"],"requiredColumns":["is_deleted","order_status"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"去重与清洗结构","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 35, 'published', 1, 4),

    ('m-l2-warehouse', 'l2-order-amount', '订单金额与状态映射', 'L2', 'medium', 'SQL',
     '计算订单金额，并用 CASE 将状态映射为业务分组，输出可读的宽表。',
     '财务需要按订单核对金额，同时把状态翻译成业务语言，避免直接看英文枚举。',
     '返回 order_id、amount、status_group 三列；金额为 quantity * unit_price。',
     $j$[{"title":"金额口径","detail":"金额 = 数量 × 单价，注意数值类型。"},{"title":"状态映射","detail":"用 CASE WHEN 把有效状态映射为“有效”，其余为“其他”。"},{"title":"保持行级","detail":"不要聚合，保持一单一行。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT order_id, quantity * unit_price AS amount,
       CASE WHEN order_status IN ('paid','shipped','completed') THEN '有效' ELSE '其他' END AS status_group
FROM orders_raw$q$,
     $q$SELECT order_id, quantity * unit_price AS amount,
       CASE WHEN order_status IN ('paid','shipped','completed') THEN '有效' ELSE '其他' END AS status_group
FROM orders_raw
WHERE is_deleted = false
  AND user_id IS NOT NULL
  AND quantity > 0
ORDER BY order_id$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredColumns":["case when","unit_price"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"金额与映射写法","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 25, 'published', 1, 5),

    ('m-l2-warehouse', 'l2-category-gmv', '品类 GMV 汇总（ADS）', 'L2', 'medium', 'SQL',
     '在去重后的有效订单上聚合品类 GMV 与订单数，产出 ADS 指标表。',
     '经营分析会需要一张品类 GMV 看板。口径要求：先去重、再过滤、后聚合，订单数按 order_id 去重计数。',
     '返回 category、gmv、order_cnt 三列，按 category 升序。',
     $j$[{"title":"先清洗","detail":"复用 DWD 去重逻辑，避免重复订单放大 GMV。"},{"title":"再聚合","detail":"SUM(quantity * unit_price) 计算 GMV。"},{"title":"订单数口径","detail":"使用 COUNT(DISTINCT order_id)。"},{"title":"自检","detail":"四个品类的 GMV 之和应等于有效订单总金额。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT category, SUM(quantity * unit_price) AS gmv, COUNT(DISTINCT order_id) AS order_cnt
FROM orders_raw
GROUP BY category$q$,
     $q$WITH clean AS (
  SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, created_at, updated_at,
         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC, order_id) AS rn
  FROM orders_raw
  WHERE is_deleted = false
    AND user_id IS NOT NULL
    AND quantity > 0
    AND order_status IN ('paid','shipped','completed')
)
SELECT category,
       SUM(quantity * unit_price) AS gmv,
       COUNT(DISTINCT order_id) AS order_cnt
FROM clean
WHERE rn = 1
GROUP BY category
ORDER BY category$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge"],"requiredPatterns":["row_number\\s*\\("],"requiredColumns":["group by","count(distinct"],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"去重与聚合结构","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 40, 'published', 1, 6),

    ('m-l2-warehouse', 'l2-quality-violations', '质量违规总量核查', 'L2', 'medium', 'SQL',
     '写一条可复现的质量核查语句，统计四类脏数据的违规订单总数。',
     '质量平台要为本数据集配置第一条规则。你需要先给出违规总量的核查 SQL，作为规则的验收基线。',
     '返回一个数值列：user_id 为空、数量非正、状态非法、单价非正四类问题的订单数之和（按行去重计数）。',
     $j$[{"title":"定义违规","detail":"四类问题分别对应空用户、负数量、非法状态、零价。"},{"title":"合并条件","detail":"用 OR 合并条件，COUNT(*) 统计违规行数。"},{"title":"可复现","detail":"语句不依赖参数，任何人执行结果一致。"}]$j$,
     $j$["teach.orders_raw","teach.order_status_dict"]$j$,
     $q$SELECT COUNT(*) AS violation_cnt
FROM orders_raw
WHERE user_id IS NULL$q$,
     $q$SELECT COUNT(*) AS violation_cnt
FROM orders_raw
WHERE user_id IS NULL
   OR quantity <= 0
   OR order_status NOT IN ('paid','shipped','completed','refunded','cancelled')
   OR unit_price <= 0$q$,
     $j$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create"],"requiredColumns":["count(","or "],"disallowSelectStar":true}$j$,
     $j$[{"key":"safety","name":"SQL 安全边界","weight":20},{"key":"static","name":"违规条件覆盖","weight":30},{"key":"result","name":"结果正确性","weight":50}]$j$,
     70, 25, 'published', 1, 7)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('l1-valid-orders', 'sql-basics', 100),
    ('l1-null-audit', 'data-quality-basics', 100),
    ('l1-partition-count', 'metadata-basics', 100),
    ('l2-dwd-dedup', 'ods-to-dwd', 100),
    ('l2-order-amount', 'metric-aggregation', 100),
    ('l2-category-gmv', 'metric-aggregation', 100),
    ('l2-quality-violations', 'quality-sql', 100)
ON CONFLICT (lab_code, skill_code) DO NOTHING;

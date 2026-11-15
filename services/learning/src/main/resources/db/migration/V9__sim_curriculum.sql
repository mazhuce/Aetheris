-- sim 学习中心：三条职业路径的课程种子（开发 / 治理 / 运维，共 29 个任务）。
-- 同时扩充教学数据集：店铺维表、用户表、支付与退款流水，并给订单表补 shop_id 列，
-- 使治理与口径类任务的 SQL 在真实数据上可判分。
-- related_features / knowledge 两列为 sim 新增，记录课程与平台功能、SQL 知识点的对应关系。

-- 店铺维表：按类目专营，与 orders_raw.category 对应。
CREATE TABLE IF NOT EXISTS teach.shop_dim (
    shop_id   BIGINT PRIMARY KEY,
    shop_name VARCHAR(64) NOT NULL,
    category  VARCHAR(32) NOT NULL
);
INSERT INTO teach.shop_dim (shop_id, shop_name, category) VALUES
    (9001, '云集电器', 'electronics'),
    (9002, '书香书店', 'books'),
    (9003, '居家优选', 'home'),
    (9004, '动感体育', 'sports')
ON CONFLICT (shop_id) DO NOTHING;

-- 订单表补充店铺维度（按类目映射专营店），供店铺粒度聚合任务使用。
ALTER TABLE teach.orders_raw ADD COLUMN IF NOT EXISTS shop_id BIGINT;
UPDATE teach.orders_raw
SET shop_id = CASE category
    WHEN 'electronics' THEN 9001
    WHEN 'books' THEN 9002
    WHEN 'home' THEN 9003
    WHEN 'sports' THEN 9004 END
WHERE shop_id IS NULL;

-- 用户表：25 个注册用户，故意保留 2 个空手机号与 1 个未登记城市。
CREATE TABLE IF NOT EXISTS teach.users (
    user_id       BIGINT PRIMARY KEY,
    user_name     VARCHAR(32) NOT NULL,
    mobile        VARCHAR(20),
    city          VARCHAR(16) NOT NULL,
    register_time TIMESTAMP   NOT NULL
);
INSERT INTO teach.users (user_id, user_name, mobile, city, register_time)
SELECT * FROM (VALUES
    (501, '用户501', '13810010001', '上海', TIMESTAMP '2026-07-02 09:00:00'),
    (502, '用户502', '13810020002', '北京', TIMESTAMP '2026-07-03 09:00:00'),
    (503, '用户503', NULL, '深圳', TIMESTAMP '2026-07-04 09:00:00'),
    (504, '用户504', '13810040004', '杭州', TIMESTAMP '2026-07-05 09:00:00'),
    (505, '用户505', '13810050005', '上海', TIMESTAMP '2026-07-06 09:00:00'),
    (506, '用户506', '13810060006', '北京', TIMESTAMP '2026-07-07 09:00:00'),
    (507, '用户507', '13810070007', '深圳', TIMESTAMP '2026-07-08 09:00:00'),
    (508, '用户508', '13810080008', '杭州', TIMESTAMP '2026-07-09 09:00:00'),
    (509, '用户509', '13810090009', '上海', TIMESTAMP '2026-07-10 09:00:00'),
    (510, '用户510', '13810100010', '北京', TIMESTAMP '2026-07-11 09:00:00'),
    (511, '用户511', '13810110011', '深圳', TIMESTAMP '2026-07-12 09:00:00'),
    (512, '用户512', '13810120012', '杭州', TIMESTAMP '2026-07-13 09:00:00'),
    (513, '用户513', '13810130013', '上海', TIMESTAMP '2026-07-14 09:00:00'),
    (514, '用户514', '13810140014', '北京', TIMESTAMP '2026-07-15 09:00:00'),
    (515, '用户515', '13810150015', '深圳', TIMESTAMP '2026-07-16 09:00:00'),
    (516, '用户516', '13810160016', '杭州', TIMESTAMP '2026-07-17 09:00:00'),
    (517, '用户517', NULL, '上海', TIMESTAMP '2026-07-18 09:00:00'),
    (518, '用户518', '13810180018', '北京', TIMESTAMP '2026-07-19 09:00:00'),
    (519, '用户519', '13810190019', '未知区域', TIMESTAMP '2026-07-20 09:00:00'),
    (520, '用户520', '13810200020', '杭州', TIMESTAMP '2026-07-21 09:00:00'),
    (521, '用户521', '13810210021', '上海', TIMESTAMP '2026-07-22 09:00:00'),
    (522, '用户522', '13810220022', '北京', TIMESTAMP '2026-07-23 09:00:00'),
    (523, '用户523', '13810230023', '深圳', TIMESTAMP '2026-07-24 09:00:00'),
    (524, '用户524', '13810240024', '杭州', TIMESTAMP '2026-07-25 09:00:00'),
    (525, '用户525', '13810250025', '上海', TIMESTAMP '2026-07-26 09:00:00')
) AS seed(user_id, user_name, mobile, city, register_time)
WHERE NOT EXISTS (SELECT 1 FROM teach.users);

-- 支付流水：与订单一一对应，故意保留重复流水、失败流水与无订单的孤儿支付。
CREATE TABLE IF NOT EXISTS teach.payments (
    pay_no     BIGINT PRIMARY KEY,
    order_id   BIGINT,
    user_id    BIGINT,
    amount     NUMERIC(12,2) NOT NULL,
    pay_status VARCHAR(16)   NOT NULL,
    dt         DATE          NOT NULL
);
INSERT INTO teach.payments (pay_no, order_id, user_id, amount, pay_status, dt)
SELECT * FROM (VALUES
    (7001, 1001, 501, 398.00, 'SUCCESS', DATE '2026-08-01'),
    (7002, 1002, 502, 59.90, 'SUCCESS', DATE '2026-08-01'),
    (7003, 1004, 504, 899.00, 'SUCCESS', DATE '2026-08-02'),
    (7004, 1005, NULL, 45.00, 'SUCCESS', DATE '2026-08-02'),
    (7005, 1010, 510, 176.00, 'SUCCESS', DATE '2026-08-04'),
    (7006, 1011, 511, 300.00, 'SUCCESS', DATE '2026-08-04'),
    (7007, 1013, 513, 499.00, 'SUCCESS', DATE '2026-08-05'),
    (7008, 1014, 514, 60.00, 'SUCCESS', DATE '2026-08-05'),
    (7009, 1015, 515, 0.00, 'FAIL', DATE '2026-08-05'),
    (7010, 1016, 516, 99.00, 'SUCCESS', DATE '2026-08-06'),
    (7011, 1018, 518, 210.00, 'SUCCESS', DATE '2026-08-06'),
    (7012, 1020, 520, 35.00, 'SUCCESS', DATE '2026-08-07'),
    (7013, 1021, 521, 2599.00, 'SUCCESS', DATE '2026-08-07'),
    (7014, 1023, 523, 780.00, 'SUCCESS', DATE '2026-08-08'),
    (7015, 1025, 525, 140.00, 'SUCCESS', DATE '2026-08-09'),
    (7016, 1001, 501, 398.00, 'SUCCESS', DATE '2026-08-01'),
    (7017, 1023, 523, 780.00, 'SUCCESS', DATE '2026-08-08'),
    (7018, 1023, 523, 780.00, 'SUCCESS', DATE '2026-08-08'),
    (7019, 1023, 523, 780.00, 'SUCCESS', DATE '2026-08-08'),
    (7020, 1023, 523, 780.00, 'SUCCESS', DATE '2026-08-08'),
    (7021, 9999, NULL, 500.00, 'SUCCESS', DATE '2026-08-08')
) AS seed(pay_no, order_id, user_id, amount, pay_status, dt)
WHERE NOT EXISTS (SELECT 1 FROM teach.payments);

-- 退款流水：两笔，其中一笔对应已被逻辑删除的订单，用于口径差异演示。
CREATE TABLE IF NOT EXISTS teach.refunds (
    refund_no     BIGINT PRIMARY KEY,
    order_id      BIGINT        NOT NULL,
    refund_amount NUMERIC(12,2) NOT NULL,
    dt            DATE          NOT NULL
);
INSERT INTO teach.refunds (refund_no, order_id, refund_amount, dt)
SELECT * FROM (VALUES
    (8001, 1006, 30.00, DATE '2026-08-02'),
    (8002, 1011, 300.00, DATE '2026-08-04')
) AS seed(refund_no, order_id, refund_amount, dt)
WHERE NOT EXISTS (SELECT 1 FROM teach.refunds);

-- 课程与平台功能的对应关系、SQL 知识点，随任务下发到前端。
ALTER TABLE learning.course_module ADD COLUMN IF NOT EXISTS related_features JSONB;
ALTER TABLE learning.lab ADD COLUMN IF NOT EXISTS related_features JSONB;
ALTER TABLE learning.lab ADD COLUMN IF NOT EXISTS knowledge JSONB;

-- 三条职业路径（与既有 de-foundation 并存，code 不同互不影响）。
INSERT INTO learning.learning_path (code, title, description, target_role, level, version, status, estimated_hours, order_no) VALUES
    ('de-basic', '数据开发工程师入门', '从 SQL 基础到数仓分层：读表、过滤、JOIN、清洗去重、增量加工、指标口径，对应平台的数据开发与建模功能。', '初级数据开发工程师', 'L1-L2', 1, 'published', 52, 2),
    ('dg-basic', '数据治理工程师入门', '质量核查、元数据与血缘、分类分级：让"数据对不对、谁在用、能不能改"变成可查询的事实，对应平台的数据治理功能。', '数据治理专员', 'L2-L3', 1, 'published', 30, 3),
    ('ops-basic', '数据管道与运维入门', '任务编排、发布验收、监控排障：从跑通一条 SQL 到对生产链路负责，对应平台的工作流、调度与运维中心功能。', '数据平台运维工程师', 'L2-L4', 1, 'published', 30, 4)
ON CONFLICT (code) DO NOTHING;

-- 旧的 de-foundation 路径与新的 de-basic 内容重叠（都从 SQL 基础讲到 DWD 清洗），
-- 归档后不再出现在路径列表里（listPaths 只取 published），已产生的提交与进度不受影响。
UPDATE learning.learning_path SET status = 'archived' WHERE code = 'de-foundation';

INSERT INTO learning.skill_node (path_code, code, parent_code, title, level, knowledge_points, outcome, order_no) VALUES
    ('de-basic', 'de-sql-basics', NULL, 'SQL 查询与过滤', 'L1', $j$["SELECT 投影","WHERE","NULL 三值逻辑","排序与行数限制"]$j$, '能写出列裁剪、口径明确的只读查询', 1),
    ('de-basic', 'de-join-dict', NULL, 'JOIN 与字典关联', 'L1', $j$["LEFT JOIN","重复行放大","孤儿数据"]$j$, '能安全关联字典并处理未知枚举', 2),
    ('de-basic', 'de-aggregate', NULL, '聚合与去重计数', 'L1', $j$["GROUP BY","HAVING","COUNT DISTINCT"]$j$, '能按口径产出分组统计', 3),
    ('de-basic', 'de-dedup', NULL, '幂等去重', 'L2', $j$["ROW_NUMBER","幂等重跑","排序键确定性"]$j$, '能产出可重跑的 DWD 去重结果', 4),
    ('de-basic', 'de-partition', NULL, '增量与分区裁剪', 'L2', $j$["分区裁剪","增量口径"]$j$, '能避免全表扫描并解释扫描量', 5),
    ('de-basic', 'de-quality-check', NULL, '清洗对账', 'L2', $j$["行数对账","主键唯一","空值率"]$j$, '能证明加工没有丢数据', 6),
    ('de-basic', 'de-metric', NULL, '指标口径与聚合层', 'L2', $j$["可加性","LAG 环比","口径冲突"]$j$, '能按登记口径产出 DWS/ADS', 7),
    ('dg-basic', 'dg-table-rules', NULL, '表级质量规则', 'L2', $j$["行数波动","主键唯一","基线选择"]$j$, '能配置行数波动与唯一性核查', 1),
    ('dg-basic', 'dg-column-rules', NULL, '列级质量规则', 'L2', $j$["空值率","枚举校验","格式校验"]$j$, '能一次扫描产出多列合法率', 2),
    ('dg-basic', 'dg-reconcile', NULL, '跨表勾稽', 'L3', $j$["FULL OUTER JOIN","差异分解"]$j$, '能把对账差异拆解到可解释类别', 3),
    ('dg-basic', 'dg-metadata', NULL, '元数据认知', 'L3', $j$["技术元数据","业务元数据","责任人"]$j$, '能评估一张表的可信度', 4),
    ('dg-basic', 'dg-lineage', NULL, '血缘与影响面', 'L3', $j$["字段级血缘","读取方式"]$j$, '能评估字段变更的影响范围', 5),
    ('dg-basic', 'dg-semantic', NULL, '语义资产评审', 'L3', $j$["同名指标","口径登记"]$j$, '能发现并收口口径冲突', 6),
    ('dg-basic', 'dg-standard', NULL, '数据标准落地', 'L3', $j$["分层前缀","命名规范"]$j$, '能按标准审查与整改对象名', 7),
    ('dg-basic', 'dg-classify', NULL, '分类分级与脱敏', 'L3', $j$["敏感字段","脱敏写法","审计联动"]$j$, '能在 SQL 层养成脱敏习惯', 8),
    ('ops-basic', 'ops-task-def', NULL, '任务定义', 'L2', $j$["脚本与任务","调度周期","超时"]$j$, '能把脚本变成生产任务', 1),
    ('ops-basic', 'ops-dependency', NULL, '依赖编排', 'L2', $j$["DAG","拓扑序","失败传播"]$j$, '能声明三层任务的依赖关系', 2),
    ('ops-basic', 'ops-retry-alert', NULL, '重试与告警', 'L2', $j$["瞬时故障","数据错误","信噪比"]$j$, '能区分失败类型并配置告警', 3),
    ('ops-basic', 'ops-release', NULL, '发布检查清单', 'L3', $j$["质量门槛","回滚方案"]$j$, '能完成一次可回滚的发布', 4),
    ('ops-basic', 'ops-acceptance', NULL, '运行验收', 'L3', $j$["验收门","证据收集"]$j$, '能产出可复核的验收记录', 5),
    ('ops-basic', 'ops-troubleshoot', NULL, '监控排障', 'L3', $j$["耗时曲线","扫描量","分区下推"]$j$, '能定位一条慢 SQL 的根因', 6),
    ('ops-basic', 'ops-audit', NULL, '查询审计', 'L3', $j$["审计三问","分级联动"]$j$, '能回答谁在何时访问了什么', 7)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course (path_code, code, title, summary, version, status, author) VALUES
    ('de-basic', 'course-de-basic', '数据开发工程师入门（实战）', '从 SQL 基础到数仓分层：读表、过滤、JOIN、清洗去重、增量加工、指标口径，对应平台的数据开发与建模功能。', 1, 'published', 'Aetheris 教学组'),
    ('dg-basic', 'course-dg-basic', '数据治理工程师入门（实战）', '质量核查、元数据与血缘、分类分级：让"数据对不对、谁在用、能不能改"变成可查询的事实，对应平台的数据治理功能。', 1, 'published', 'Aetheris 教学组'),
    ('ops-basic', 'course-ops-basic', '数据管道与运维入门（实战）', '任务编排、发布验收、监控排障：从跑通一条 SQL 到对生产链路负责，对应平台的工作流、调度与运维中心功能。', 1, 'published', 'Aetheris 教学组')
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.course_module (course_code, code, title, objective, level, order_no, related_features) VALUES
    ('course-de-basic', 'de-m1', 'L1 · SQL 基础与数据认知', '从零开始读表、查询和过滤，理解 NULL 与 JOIN 的行为，为后续清洗打基础。', 'L1', 1, $j$["SQL开发","元数据管理"]$j$),
    ('course-de-basic', 'de-m2', 'L2 · ODS 到 DWD：数据清洗与分层', '把脏的原始流水加工成可复用的明细宽表，掌握幂等去重、字典归一与增量加工。', 'L2', 2, $j$["SQL开发","物理模型","数据质量"]$j$),
    ('course-de-basic', 'de-m3', 'L2 · 指标口径与聚合层', '从明细到聚合：写出可复用的 DWS/ADS，理解指标口径为什么必须先于 SQL。', 'L2', 3, $j$["指标管理","数据看板","语义依赖图"]$j$),
    ('course-dg-basic', 'dg-m1', 'L2 · 数据质量核查', '用 SQL 把"数据对不对"变成可量化、可报警的核查规则。', 'L2', 1, $j$["数据质量","质量趋势"]$j$),
    ('course-dg-basic', 'dg-m2', 'L3 · 元数据、血缘与影响分析', '让"改一张表会炸到谁"从一个玄学问题变成一次查询。', 'L3', 2, $j$["元数据管理","数据血缘","语义依赖图"]$j$),
    ('course-dg-basic', 'dg-m3', 'L3 · 标准与安全', '把命名规范和敏感数据分级落到日常 SQL 与表设计里。', 'L3', 3, $j$["数据标准","分类分级"]$j$),
    ('course-ops-basic', 'ops-m1', 'L2 · 从 SQL 到任务编排', '把一条跑通的 SQL 变成可调度、可依赖、可重试的生产任务。', 'L2', 1, $j$["工作流","任务调度"]$j$),
    ('course-ops-basic', 'ops-m2', 'L3 · 发布与运行验收', '从"我本地跑通了"到"可以在生产跑"：发布检查清单与运行验收。', 'L3', 2, $j$["发布管理","运行验收"]$j$),
    ('course-ops-basic', 'ops-m3', 'L3 · 监控与排障', '从一条告警出发定位根因，并回答"谁动过这张表"。', 'L3', 3, $j$["任务监控","告警中心","查询审计"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge)
VALUES
    ('de-m1', 'de-l1-01', '第一条查询：SELECT 与列裁剪', 'L1', 'easy', 'SQL',
     '从订单流水表里取出指定列，养成"用多少列取多少列"的习惯。',
     '你是刚入职的数据开发。订单中台把原始订单流水同步进了 ods_orders_raw（原始订单流水，约 3.2 亿行，按天分区，含逻辑删除、空用户、负金额等脏数据）。报表组要一份"当天订单的订单号、用户ID、金额"清单，明确说不要多余列——大表上 SELECT * 会把扫描和传输成本放大好几倍。',
     '只输出 order_id、user_id、pay_amount 三列，行数与全表当月分区一致，不出现重复列。',
     $j$[{"title":"看表结构","detail":"在元数据管理里打开 ods_orders_raw，确认列名、类型与分区字段 dt。"},{"title":"改写列裁剪","detail":"把 SELECT * 换成明确的三列，并给 pay_amount 加别名 amount。"},{"title":"核对结果","detail":"确认输出列名与行数，检查金额列没有科学计数法或精度丢失。"},{"title":"提交判分","detail":"提交 SQL，服务端在只读事务中执行并比对标准结果。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 先看这条 SQL 扫了多少列，再改成列裁剪
SELECT *
FROM orders_raw
WHERE dt = DATE '2026-08-01'
LIMIT 100;$q$,
     $q$SELECT order_id, user_id, quantity * unit_price AS amount
FROM orders_raw
WHERE dt = DATE '2026-08-01';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_id\",\"user_id\",\"quantity\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 15, 'published', 1, 1,
     $j$["SQL开发","元数据管理"]$j$,
     $j$[{"title":"列裁剪（Projection Pruning）","detail":"只 SELECT 需要的列。列式存储下扫描的列数直接决定耗时与 IO，SELECT * 在宽表上代价极高；列裁剪也是引擎做分区裁剪之外最有效的提速手段。"},{"title":"别名的两种写法","detail":"pay_amount AS amount 与 pay_amount amount 等价。别名在后续 GROUP BY / ORDER BY 中可直接引用，但同一条 SQL 里 WHERE 不能引用 SELECT 别名——逻辑执行顺序是 FROM → WHERE → GROUP BY → SELECT → ORDER BY。"}]$j$),
    ('de-m1', 'de-l1-02', '过滤与排序：WHERE / ORDER BY / LIMIT', 'L1', 'easy', 'SQL',
     '用组合条件筛出"已支付的大额订单"，并按金额倒序取前 100。',
     '风控同学要看 11 月 1 日支付金额超过 1000 元的订单，按金额从高到低取前 100 条做人工复核。ods_orders_raw 里 status 字段是编码，2 表示已支付；金额字段里混有 0 和负数的脏数据，需要一并排除。',
     'status = 2 且 pay_amount > 1000，按 pay_amount 倒序取前 100 行；金额必须大于 0。',
     $j$[{"title":"补全过滤条件","detail":"加上 pay_amount > 1000，并显式排除 pay_amount <= 0 的脏行。"},{"title":"排序","detail":"ORDER BY pay_amount DESC；注意 SQL 里 NULL 参与排序的位置因引擎而异。"},{"title":"限制行数","detail":"LIMIT 100 放在最后；排序在 LIMIT 之前执行，所以拿到的是\"全量排序后的前 100\"。"},{"title":"提交判分","detail":"核对结果后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT order_id, user_id, quantity * unit_price AS amount
FROM orders_raw
WHERE dt = DATE '2026-08-01'
  AND order_status = 'paid'
LIMIT 100;$q$,
     $q$SELECT order_id, user_id, quantity * unit_price AS amount
FROM orders_raw
WHERE order_status = 'paid'
  AND quantity > 0
  AND quantity * unit_price > 500
ORDER BY quantity * unit_price DESC
LIMIT 100;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_status\",\"quantity\",\"unit_price\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 20, 'published', 1, 2,
     $j$["SQL开发"]$j$,
     $j$[{"title":"AND / OR 的优先级","detail":"AND 优先级高于 OR，a OR b AND c 等价于 a OR (b AND c)。拿不准就加括号，可读性永远优先。"},{"title":"排序的稳定性与代价","detail":"ORDER BY 在大数据量下会触发全量排序甚至 spill 到磁盘。如果只关心\"每组前 N\"，优先考虑窗口函数而不是先排序再过滤。"}]$j$),
    ('de-m1', 'de-l1-03', 'NULL 的三值逻辑与 COALESCE', 'L1', 'easy', 'SQL',
     '订单表里 user_id 为 NULL 表示游客单；学会判断 NULL、给 NULL 兜底，以及为什么 COUNT(*) 不等于 COUNT(user_id)。',
     '运营要统计 11 月 1 日的"注册用户订单数"。ods_orders_raw 中 user_id 为 NULL 是游客下单（原始订单流水，约 3.2 亿行，按天分区，含逻辑删除、空用户、负金额等脏数据）。直接 WHERE user_id != '''' 或 = NULL 都拿不到正确结果——这是新人最常见的坑。',
     '输出注册用户订单数、游客订单数、总订单数三行；user_id 为 NULL 判定为游客。',
     $j$[{"title":"理解三值逻辑","detail":"NULL 与任何值的比较结果都是 UNKNOWN，WHERE 只保留 TRUE。= NULL 永远不成立。"},{"title":"改用 IS NULL","detail":"用 user_id IS NULL 区分游客；同时体会 COUNT(*) 与 COUNT(user_id) 的差异。"},{"title":"给 NULL 兜底","detail":"用 COALESCE(user_id, 'guest') 把 NULL 变成可读的占位值再分组。"},{"title":"提交判分","detail":"三个数字与标准答案对齐后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 三种写法，哪种能查到 NULL？
SELECT COUNT(*) FROM orders_raw WHERE user_id = NULL;
SELECT COUNT(*) FROM orders_raw WHERE user_id != 'xxx';
SELECT COUNT(*) FROM orders_raw;$q$,
     $q$SELECT COUNT(*) FILTER (WHERE user_id IS NOT NULL) AS member_orders,
       COUNT(*) FILTER (WHERE user_id IS NULL) AS guest_orders,
       COUNT(*) AS total_orders
FROM orders_raw;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"user_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 20, 'published', 1, 3,
     $j$["SQL开发"]$j$,
     $j$[{"title":"三值逻辑（TRUE / FALSE / UNKNOWN）","detail":"NULL 参与任何比较（=、!=、>、LIKE）结果都是 UNKNOWN 而不是 FALSE。判断 NULL 只能 IS NULL / IS NOT NULL。这也是为什么 NOT IN 遇到 NULL 子查询会整条返回空。"},{"title":"COUNT(*) 与 COUNT(col)","detail":"COUNT(*) 统计行数；COUNT(col) 跳过 NULL。同理 SUM 会忽略 NULL 但空集时返回 NULL 而不是 0，需要用 COALESCE(SUM(x), 0) 兜底。"}]$j$),
    ('de-m1', 'de-l1-04', '关联字典表：JOIN 的正确姿势', 'L1', 'easy', 'SQL',
     '把订单状态编码翻译成可读名称；理解 INNER 与 LEFT JOIN 的差异，以及小表关联的重复行陷阱。',
     '报表不能给业务方看 status = 2 这种编码。ods_order_status_dict（订单状态字典，12 行，code→name 映射）是字典表。注意：字典里有一条 status = 99（测试单）在订单表里出现了多次，INNER JOIN 会把测试单也带出来；而有些异常订单的 status 在字典里不存在，INNER JOIN 会把它们丢掉。',
     '输出订单号、状态编码、状态名称；保留字典中不存在的异常订单（状态名给"未知"），并排除测试单。',
     $j$[{"title":"换成 LEFT JOIN","detail":"以订单为主表 LEFT JOIN 字典，观察哪些行的 status_name 变成 NULL。"},{"title":"排除测试单","detail":"字典中 status_code = 99 是测试单，直接在 JOIN 条件或 WHERE 里排除。"},{"title":"给未知状态兜底","detail":"用 COALESCE(d.status_name, '未知') 处理孤儿编码。"},{"title":"提交判分","detail":"核对行数与状态名后提交。"}]$j$,
     $j$["teach.orders_raw","teach.order_status_dict"]$j$,
     $q$SELECT o.order_id, o.order_status, d.status_name
FROM orders_raw o
JOIN order_status_dict d ON o.order_status = d.status_code
WHERE o.dt = DATE '2026-08-03'
LIMIT 100;$q$,
     $q$SELECT o.order_id, o.order_status, COALESCE(d.status_name, '未知') AS status_name
FROM orders_raw o
LEFT JOIN order_status_dict d
  ON o.order_status = d.status_code AND d.is_valid = true
WHERE o.dt = DATE '2026-08-03'
ORDER BY o.order_id;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_status\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 25, 'published', 1, 4,
     $j$["SQL开发","语义依赖图"]$j$,
     $j$[{"title":"INNER 与 LEFT JOIN","detail":"INNER JOIN 只留两边都匹配的行；LEFT JOIN 保留左表全部。事实表关联字典表时默认用 LEFT JOIN——字典缺失不应该让事实蒸发，这是数仓口径稳定性的基本要求。"},{"title":"重复行放大（Fan-out）","detail":"如果右表关联键不唯一，左表一行会被复制成多行，行数悄悄变大且难以察觉。关联前先确认右表键唯一，或者先对右表去重/聚合。"}]$j$),
    ('de-m1', 'de-l1-05', 'GROUP BY 与聚合函数', 'L1', 'easy', 'SQL',
     '按状态统计当天订单量与支付金额，理解"非聚合列必须出现在 GROUP BY 里"。',
     '日报需要一行一个状态：订单量、支付总金额、平均金额。注意订单表里有负金额的冲正单，直接 SUM 会把 GMV 拉低——先决定是否排除。',
     '按 status 分组输出订单数、SUM(pay_amount)、AVG(pay_amount)；排除 pay_amount <= 0 的冲正单。',
     $j$[{"title":"明确口径","detail":"冲正单（负金额）算不算订单量？本任务口径：排除后再统计。"},{"title":"补齐聚合列","detail":"加 AVG；非聚合列必须出现在 GROUP BY 中，否则大多数引擎直接报错。"},{"title":"命名输出列","detail":"给三个聚合列起语义化别名：order_cnt、gmv、avg_amount。"},{"title":"提交判分","detail":"与标准结果比对后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT order_status, COUNT(*), SUM(quantity * unit_price)
FROM orders_raw
WHERE dt = DATE '2026-08-01'
GROUP BY order_status;$q$,
     $q$SELECT order_status,
       COUNT(*) AS order_cnt,
       SUM(quantity * unit_price) AS total_amount
FROM orders_raw
WHERE dt = DATE '2026-08-01' AND quantity > 0
GROUP BY order_status
ORDER BY order_status;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_status\",\"quantity\",\"unit_price\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 20, 'published', 1, 5,
     $j$["SQL开发","指标管理"]$j$,
     $j$[{"title":"GROUP BY 的铁律","detail":"SELECT 里的列要么出现在 GROUP BY，要么包在聚合函数里。这不是风格问题，是语义问题——引擎无法决定同一组里取哪一行的值。"},{"title":"聚合对 NULL 的处理","detail":"除 COUNT(*) 外，聚合函数都会忽略 NULL 行。SUM 一列全 NULL 返回 NULL；想得到 0 就包一层 COALESCE。"}]$j$),
    ('de-m1', 'de-l1-06', '去重计数与近似计算', 'L1', 'normal', 'SQL',
     '统计当天独立买家数；理解 COUNT(DISTINCT) 在亿级表上的代价与近似方案的取舍。',
     '运营要"当日独立买家数"。ods_orders_raw 单日约 180 万行、买家约 60 万。精确 COUNT(DISTINCT) 需要全量去重，在大表上是昂贵的操作；数据团队约定：日报允许 1% 以内误差，可用近似算法。',
     '输出当日订单数、独立买家数（精确），并用注释说明如果要近似计算应该怎么做。',
     $j$[{"title":"先写精确版","detail":"COUNT(DISTINCT user_id)，注意它天然跳过 NULL（游客不计入买家）。"},{"title":"评估代价","detail":"想清楚去重发生在哪一步：shuffle 全量 user_id 到同一节点。"},{"title":"写出近似方案","detail":"在注释里写明近似思路（如 HLL / HyperLogLog 类函数）及误差预期。"},{"title":"提交判分","detail":"核对买家数后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT COUNT(*) AS order_rows
FROM orders_raw;$q$,
     $q$SELECT COUNT(*) AS order_rows,
       COUNT(DISTINCT order_id) AS distinct_orders,
       COUNT(DISTINCT user_id) AS distinct_users
FROM orders_raw;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_id\",\"user_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 20, 'published', 1, 6,
     $j$["SQL开发","指标管理"]$j$,
     $j$[{"title":"去重的代价","detail":"COUNT(DISTINCT) 需要把所有候选值 shuffle 到一起做去重，内存与耗时随基数线性增长。亿级表上它经常是任务里最慢的一步。"},{"title":"精确与近似的取舍","detail":"基数统计类算法（HyperLogLog 等）用固定内存换可控误差（通常 <1%）。能用近似的场景（日报、大盘）就用近似，要精确的场景（财务对账）必须精确——口径先于性能。"}]$j$),
    ('de-m1', 'de-l1-07', 'HAVING 与聚合后过滤', 'L1', 'easy', 'SQL',
     '找出当天订单量超过 1 万的店铺；体会 WHERE 与 HAVING 的执行顺序差异。',
     '运营想定位"爆款店铺"：11 月 1 日订单量超过 1 万的店铺。店铺信息在 dim_shop（店铺维表，约 42 万行，含店铺一级/二级类目），需要先关联再按店铺聚合。过滤"聚合后结果"必须用 HAVING，写在 WHERE 里会直接报错。',
     '输出店铺名、订单数，只保留订单数 > 10000 的店铺，按订单数倒序。',
     $j$[{"title":"先跑通关联","detail":"确认 JOIN 后行数与订单表一致（dim_shop 的 shop_id 唯一）。"},{"title":"加 HAVING","detail":"HAVING COUNT(*) > 10000；尝试把它写进 WHERE 观察报错，加深理解。"},{"title":"排序输出","detail":"ORDER BY order_cnt DESC。"},{"title":"提交判分","detail":"核对店铺清单后提交。"}]$j$,
     $j$["teach.orders_raw","teach.shop_dim"]$j$,
     $q$SELECT s.shop_name, COUNT(*) AS order_cnt
FROM orders_raw o
JOIN shop_dim s ON o.shop_id = s.shop_id
WHERE o.dt = DATE '2026-08-01'
GROUP BY s.shop_name;$q$,
     $q$SELECT s.shop_name, COUNT(*) AS order_cnt
FROM orders_raw o
JOIN shop_dim s ON o.shop_id = s.shop_id
WHERE o.dt = DATE '2026-08-01' AND o.quantity > 0
GROUP BY s.shop_name
HAVING COUNT(*) > 1
ORDER BY order_cnt DESC;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"shop_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     60, 15, 'published', 1, 7,
     $j$["SQL开发"]$j$,
     $j$[{"title":"WHERE 与 HAVING 的顺序","detail":"逻辑执行顺序 FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT。WHERE 过滤原始行（先过滤少聚合），HAVING 过滤聚合结果——能用 WHERE 就不要用 HAVING，前者成本低得多。"}]$j$),
    ('de-m2', 'de-l2-01', '幂等去重：ROW_NUMBER 保留最新一条', 'L2', 'normal', 'SQL',
     '原始流水里同一订单被同步了多次，取 update_time 最新的一条，并且重跑结果必须一致。',
     '上游 SDK 偶尔重发导致 ods_orders_raw 同一 order_id 出现 2-5 条记录，字段值可能不同。DWD 层要求一个 order_id 只有一行，且任务重跑（比如补数）后结果完全一致——这是幂等性，生产上最重要的要求之一。',
     '按 order_id 去重，保留 update_time 最大的一行；输出到 dwd_order_detail 的口径；重跑两次结果一致。',
     $j$[{"title":"验证重复规模","detail":"先查当天有多少 order_id 出现多次，心里有数。"},{"title":"窗口函数去重","detail":"ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY update_time DESC) = 1。"},{"title":"验证幂等","detail":"把同一条 SQL 跑两遍比对结果；再想想为什么\"先 DISTINCT 后随机取一条\"是不行的。"},{"title":"提交判分","detail":"行数与内容双比对后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$SELECT order_id, user_id, quantity, unit_price, order_status
FROM orders_raw
WHERE dt = DATE '2026-08-01';$q$,
     $q$SELECT order_id, user_id, quantity, unit_price, order_status
FROM (
  SELECT order_id, user_id, quantity, unit_price, order_status,
         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) AS rn
  FROM orders_raw
  WHERE dt = DATE '2026-08-01'
) ranked
WHERE rn = 1
ORDER BY order_id;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_id\",\"updated_at\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 30, 'published', 1, 1,
     $j$["SQL开发","物理模型"]$j$,
     $j$[{"title":"窗口函数：ROW_NUMBER","detail":"ROW_NUMBER() OVER (PARTITION BY 分组键 ORDER BY 排序键) 给组内编号，取 rn = 1 即\"每组最新一条\"。这是数仓去重的标准范式。"},{"title":"幂等（Idempotent）","detail":"同一输入重复执行得到同一输出的任务才敢重跑、敢补数。凡是有\"取最新/取第一条\"语义的加工，必须让排序键完全确定（必要时加第二排序键防并列）。"}]$j$),
    ('de-m2', 'de-l2-02', '字典归一与枚举校验', 'L2', 'normal', 'SQL',
     '把状态编码翻译成名称并校验枚举合法性，孤儿编码显式落到"未知"而不是悄悄丢掉。',
     'DWD 明细要求状态列是可读名称且枚举可控。字典只有 12 个合法值，但线上出现了 3 个孤儿编码（历史遗留）。数据标准要求：非法枚举不允许静默丢弃，必须显式标记并计入质量核查。',
     '输出 status_name；孤儿编码统一为"未知"，并额外输出一列 is_abnormal_status 标记。',
     $j$[{"title":"找孤儿编码","detail":"LEFT JOIN 后 status_name IS NULL 的行就是非法枚举。"},{"title":"标记与兜底","detail":"CASE WHEN 把它们标成\"未知\"，同时输出 is_abnormal_status 供质量核查。"},{"title":"对齐数据标准","detail":"理解\"显式处理\"与\"静默丢弃\"的差别：后者会让下游对账永远差几行。"},{"title":"提交判分","detail":"核对标记列后提交。"}]$j$,
     $j$["teach.orders_raw","teach.order_status_dict","teach.orders_raw"]$j$,
     $q$SELECT o.order_id, o.order_status, d.status_name
FROM orders_raw o
LEFT JOIN order_status_dict d ON o.order_status = d.status_code
WHERE o.dt = DATE '2026-08-03';$q$,
     $q$SELECT o.order_id, o.order_status,
       COALESCE(d.status_name, '未登记') AS status_name,
       CASE WHEN d.is_valid = false OR d.status_name IS NULL THEN true ELSE false END AS is_abnormal_status
FROM orders_raw o
LEFT JOIN order_status_dict d ON o.order_status = d.status_code
WHERE o.dt = DATE '2026-08-03'
ORDER BY o.order_id;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_status\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 2,
     $j$["SQL开发","数据标准","数据质量"]$j$,
     $j$[{"title":"枚举列的质量契约","detail":"枚举列必须能通过\"值域校验\"：所有取值都在字典内。孤儿枚举是上游变更最常见的信号，显式标记才能让质量工具及时发现。"},{"title":"CASE WHEN 的位置","detail":"CASE WHEN 属于 SELECT 阶段的表达式，可以引用 JOIN 后的字段；需要按表达式分组时，要么写完整表达式进 GROUP BY，要么用子查询先算好再分组。"}]$j$),
    ('de-m2', 'de-l2-03', '增量加工与分区裁剪', 'L2', 'normal', 'SQL',
     '只加工当天的增量分区；理解"分区列必须出现在 WHERE 里"否则全表扫描。',
     'DWD 明细按天分区。每天的任务只应该处理 dt = 当天的分区（约 180 万行）。如果忘了分区条件，任务会在 3.2 亿行的全表上跑一遍——生产上这种"忘了分区条件"的事故每月都会发生，轻则任务超时，重则拖垮集群。',
     '在去重逻辑之上只处理 dt = 2026-11-01 的分区；SQL 中显式体现分区条件。',
     $j$[{"title":"组合逻辑","detail":"把 L2 的去重 + 字典归一合并成一条完整的 DWD 加工 SQL。"},{"title":"检查分区条件","detail":"确认 WHERE 里有 dt，且没有对 dt 做函数包装（会让裁剪失效）。"},{"title":"评估扫描量","detail":"解释这条 SQL 为什么只扫一个分区而不是全表。"},{"title":"提交判分","detail":"行数一致后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$SELECT o.order_id, o.user_id, o.quantity, o.unit_price
FROM orders_raw o
WHERE dt = DATE '2026-08-01';$q$,
     $q$SELECT o.order_id, o.user_id, o.quantity, o.unit_price,
       COALESCE(d.status_name, '未登记') AS status_name
FROM (
  SELECT order_id, user_id, quantity, unit_price, order_status,
         ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) AS rn
  FROM orders_raw
  WHERE dt = DATE '2026-08-01' AND is_deleted = false
) o
LEFT JOIN order_status_dict d ON o.order_status = d.status_code
WHERE o.rn = 1 AND o.quantity > 0
ORDER BY o.order_id;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"dt\",\"order_id\",\"is_deleted\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 3,
     $j$["SQL开发","任务调度"]$j$,
     $j$[{"title":"分区裁剪（Partition Pruning）","detail":"分区列直接出现在 WHERE 的等值/范围比较里，引擎才会只读对应分区。对分区列套函数（如 date_format(dt)）会让裁剪失效，退化成全表扫描。"},{"title":"增量的两种口径","detail":"按分区增量（T-1 全量快照）与按时间戳增量（update_time > 上次水位）。前者简单可靠可重跑，后者省资源但要管理水位——新人优先掌握前者。"}]$j$),
    ('de-m2', 'de-l2-04', '清洗质量核查：对账与空值率', 'L2', 'normal', 'SQL',
     'DWD 产出后立刻做三件核查：行数对账、主键唯一、关键字段空值率——这是数据质量的第一道防线。',
     '任务跑完不代表数据是对的。发布管理要求 DWD 任务必须附带质量核查：ODS 输入行数与 DWD 输出行数要能解释（去重掉多少）、order_id 必须唯一、pay_amount 与 user_id 的空值率要在阈值内。核查不过，任务不允许发布。',
     '输出核查结果：输入行数、去重后行数、去重丢弃数、order_id 重复数、pay_amount 空值率、user_id 空值率。',
     $j$[{"title":"行数对账","detail":"输入行数 - 输出行数 = 被去重丢弃的行数，必须能对上。"},{"title":"主键唯一性","detail":"GROUP BY order_id HAVING COUNT(*) > 1，任何结果都算失败。"},{"title":"空值率","detail":"AVG(CASE WHEN col IS NULL THEN 1 ELSE 0 END) 计算空值率并与阈值比较。"},{"title":"提交判分","detail":"六项核查指标全部符合预期后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$SELECT COUNT(*) AS input_rows
FROM orders_raw
WHERE dt = DATE '2026-08-01';$q$,
     $q$SELECT (SELECT COUNT(*) FROM orders_raw WHERE dt = DATE '2026-08-01') AS input_rows,
       (SELECT COUNT(DISTINCT order_id) FROM orders_raw WHERE dt = DATE '2026-08-01') AS dedup_rows,
       (SELECT COUNT(*) FROM orders_raw WHERE dt = DATE '2026-08-01')
         - (SELECT COUNT(DISTINCT order_id) FROM orders_raw WHERE dt = DATE '2026-08-01') AS dup_dropped;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 30, 'published', 1, 4,
     $j$["数据质量","发布管理"]$j$,
     $j$[{"title":"对账（Reconciliation）","detail":"上下游行数必须可解释：输入 = 输出 + 丢弃 + 过滤。对不上就要人查，这是发现\"任务默默丢数据\"的唯一手段。"},{"title":"空值率的计算写法","detail":"AVG(CASE WHEN col IS NULL THEN 1.0 ELSE 0.0 END) 一次扫描同时算多列空值率，比每列写一个 COUNT 快得多。"}]$j$),
    ('de-m3', 'de-l2-05', '店铺日销售聚合（DWS）', 'L2', 'normal', 'SQL',
     '从 DWD 明细聚合出店铺粒度的日销售：订单数、GMV、买家数——为看板和 GMV 日报供数。',
     '看板要按店铺看每天的销售。DWS 层是"轻度聚合"：粒度是 店铺 × 天，口径在这里定死，下游 ADS/看板只做再聚合。口径必须与指标管理中登记的 GMV 定义一致：已支付且金额 > 0 的订单，不含冲正。',
     '按 shop_id × dt 输出 order_cnt、gmv、buyer_cnt（买家去重），口径排除冲正单。',
     $j$[{"title":"对齐指标口径","detail":"打开指标管理里的 GMV 定义，确认过滤条件与 DWD 一致。"},{"title":"三指标同算","detail":"一次扫描同时算 COUNT / SUM / COUNT(DISTINCT user_id)。"},{"title":"验证可再聚合","detail":"确认 DWS 按 shop 求和能对上 DWD 总 GMV（加法可分解）。"},{"title":"提交判分","detail":"三个指标与标准结果比对后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$SELECT shop_id, dt, COUNT(*) AS order_cnt
FROM orders_raw
WHERE dt = DATE '2026-08-01'
GROUP BY shop_id, dt;$q$,
     $q$SELECT o.shop_id, s.shop_name, o.dt,
       COUNT(*) AS order_cnt,
       SUM(o.quantity * o.unit_price) AS gmv,
       COUNT(DISTINCT o.user_id) AS buyer_cnt
FROM orders_raw o
JOIN shop_dim s ON o.shop_id = s.shop_id
WHERE o.dt = DATE '2026-08-01' AND o.is_deleted = false AND o.quantity > 0
GROUP BY o.shop_id, s.shop_name, o.dt
ORDER BY o.shop_id;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"shop_id\",\"dt\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 30, 'published', 1, 1,
     $j$["指标管理","数据看板"]$j$,
     $j$[{"title":"可加性（Additivity）","detail":"聚合层设计的第一原则：下游能通过再聚合还原上层指标。COUNT/SUM 可加；AVG 与 COUNT(DISTINCT) 不可加，必须把\"分子/分母\"或\"去重前的明细粒度\"存下来。"},{"title":"口径先行","detail":"先在指标管理里定义指标（过滤条件、统计粒度、去重维度），再写 SQL。SQL 只是口径的实现，口径冲突时改的是口径文档而不是代码。"}]$j$),
    ('de-m3', 'de-l2-06', 'GMV 日报（ADS）与环比', 'L2', 'normal', 'SQL',
     '产出全站 GMV 日报并计算环比；第一次接触"今天依赖昨天"的跨分区自关联。',
     'ADS 层直接对接看板：一行一天，GMV + 环比。环比需要今天的 GMV 和昨天的 GMV，两种写法：LAG 窗口函数或自 JOIN。在大表上 LAG 通常更优。',
     '输出最近 7 天的 dt、gmv、gmv_dod（环比比例），分母为 0 或 NULL 时环比给 NULL 而不是报错。',
     $j$[{"title":"写出当日 GMV","detail":"基于 DWS 再聚合到全站粒度。"},{"title":"用 LAG 取昨日","detail":"LAG(gmv) OVER (ORDER BY dt) 取上一行；理解窗口按 dt 排序的含义。"},{"title":"处理除零","detail":"环比 = (今 - 昨) / 昨；昨为 NULL 或 0 时返回 NULL，用 NULLIF + CASE 组合。"},{"title":"提交判分","detail":"七行数据与环比值比对后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$SELECT dt, SUM(quantity * unit_price) AS gmv
FROM orders_raw
WHERE is_deleted = false
GROUP BY dt
ORDER BY dt;$q$,
     $q$WITH daily AS (
  SELECT dt, SUM(quantity * unit_price) AS gmv
  FROM orders_raw
  WHERE is_deleted = false AND quantity > 0
  GROUP BY dt
)
SELECT dt, gmv,
       CASE WHEN LAG(gmv) OVER (ORDER BY dt) IS NULL OR LAG(gmv) OVER (ORDER BY dt) = 0 THEN NULL
            ELSE ROUND((gmv - LAG(gmv) OVER (ORDER BY dt)) / LAG(gmv) OVER (ORDER BY dt), 4) END AS dod
FROM daily
ORDER BY dt;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"dt\",\"is_deleted\",\"quantity\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 30, 'published', 1, 2,
     $j$["数据看板","指标管理"]$j$,
     $j$[{"title":"窗口函数 LAG / LEAD","detail":"LAG(col) OVER (ORDER BY dt) 取排序后的上一行值，跨行计算不需要自 JOIN。窗口函数在 GROUP BY 之后执行，可以引用聚合结果。"},{"title":"除零与 NULLIF","detail":"a / b 在 b = 0 时多数引擎报错或返回 NULL。NULLIF(b, 0) 把 0 变成 NULL，再配合外层 COALESCE 控制展示。"}]$j$),
    ('de-m3', 'de-l2-07', '口径冲突排查：为什么两份 GMV 对不上', 'L2', 'hard', 'SQL',
     '看板 GMV 与财务 GMV 差了 2.3%——用 SQL 定位差异来源，理解"同名词不同义"是数据团队最常见的事故。',
     '财务的 GMV 包含已退款订单（按支付口径），看板的 GMV 排除了退款（按履约口径）。两个数都"对"，口径不同。你的任务：写一条 SQL 把两种口径各算一遍并输出差异明细，把"对不上"变成可解释的差值。',
     '输出支付口径 GMV、履约口径 GMV、差值、差值占比；并找出两种口径下行数差异的具体订单样例。',
     $j$[{"title":"分别实现两种口径","detail":"支付口径基于 ods_payments；履约口径基于 DWD（排除退款）。"},{"title":"输出差异","detail":"两个子查询 FULL OUTER 或分别算完后相减，给出占比。"},{"title":"抽样定位","detail":"取差值订单样例，验证它们正是\"已退款\"订单。"},{"title":"提交判分","detail":"差值能被样例完全解释后提交。"}]$j$,
     $j$["teach.payments","teach.refunds","teach.orders_raw"]$j$,
     $q$-- 支付口径：只看成功支付流水
SELECT SUM(amount) FROM payments WHERE pay_status = 'SUCCESS';$q$,
     $q$SELECT (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE pay_status = 'SUCCESS') AS payment_gmv,
       (SELECT COALESCE(SUM(quantity * unit_price), 0) FROM orders_raw
         WHERE is_deleted = false AND quantity > 0
           AND order_status IN ('paid', 'shipped', 'completed')) AS fulfillment_gmv;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"is_deleted\",\"order_status\",\"quantity\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 35, 'published', 1, 3,
     $j$["指标管理","语义依赖图","数据看板"]$j$,
     $j$[{"title":"同名词不同义","detail":"两个都叫 GMV 的指标，过滤条件差一个条件就是两套数。指标管理存在的意义：让每个指标有唯一登记的口径，SQL 里的每个 WHERE 都能在口径文档里找到出处。"},{"title":"把差异变成可解释","detail":"对账的产出不是\"对不上\"，而是\"差异 = 已知的某种订单 × N\"。能解释的差异是口径，解释不了的差异才是事故。"}]$j$),
    ('dg-m1', 'dg-l1-01', '表级核查：行数波动与主键唯一', 'L2', 'normal', 'SQL',
     '行数同比暴涨暴跌、主键重复，是数据事故最常见的两种形态。',
     '数据质量的表级规则有两类：波动规则（今日行数 / 7 日均值超出 ±30% 告警）与唯一性规则（主键不得重复）。ods_payments 单日 900 万行左右，昨天上游重跑导致行数翻倍，核查规则必须能抓住它。',
     '输出今日行数、近 7 日均值、波动比例、order_no 重复数；波动超阈值时输出 is_abnormal = true。',
     $j$[{"title":"取 7 日基线","detail":"聚合出近 7 天行数，排除今天本身。"},{"title":"算波动比例","detail":"ABS(今日 - 基线) / 基线，注意基线为 0 的除零保护。"},{"title":"主键唯一性","detail":"GROUP BY order_no HAVING COUNT(*) > 1。"},{"title":"提交判分","detail":"四项输出与阈值判断一致后提交。"}]$j$,
     $j$["teach.payments"]$j$,
     $q$SELECT dt, COUNT(*) AS row_cnt
FROM payments
GROUP BY dt
ORDER BY dt;$q$,
     $q$WITH daily AS (
  SELECT dt, COUNT(*) AS row_cnt FROM payments GROUP BY dt
),
base AS (
  SELECT AVG(row_cnt) AS avg_rows FROM daily WHERE dt < DATE '2026-08-08'
)
SELECT d.dt, d.row_cnt, ROUND(b.avg_rows, 1) AS baseline,
       ROUND(ABS(d.row_cnt - b.avg_rows) / NULLIF(b.avg_rows, 0), 4) AS fluctuation,
       (ABS(d.row_cnt - b.avg_rows) / NULLIF(b.avg_rows, 0)) > 0.3 AS is_abnormal
FROM daily d CROSS JOIN base b
WHERE d.dt = DATE '2026-08-08';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"dt\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 1,
     $j$["数据质量","告警中心"]$j$,
     $j$[{"title":"波动规则的基线选择","detail":"7 日均值能抹平周内波动（周末与工作日量级不同）。基线本身要用\"业务可比\"的日期集合，大促日要单独对待，否则规则天天误报。"},{"title":"质量规则的三层","detail":"表级（行数/分区存在）→ 列级（空值率/枚举/格式）→ 跨表（勾稽/一致性）。先有表级再上列级，一上来就堆规则只会误报成灾。"}]$j$),
    ('dg-m1', 'dg-l1-02', '列级核查：空值率、枚举与格式', 'L2', 'normal', 'SQL',
     '一次扫描算出多列空值率与枚举合法率，学会把"看起来不对"写成机器可判定的规则。',
     'ods_users（用户表，约 8600 万行，手机号为敏感字段）中手机号是敏感字段且要求非空可关联；city 字段是枚举，但上游新增了两个未登记城市；register_time 有格式异常的历史数据。质量规则要求逐列给出"合法率"。',
     '输出 user_id 空值率、mobile 空值率、city 合法率、register_time 格式合法率，四列一次算完。',
     $j$[{"title":"一次扫描多指标","detail":"AVG(CASE WHEN ...) 组合，避免每列一个查询。"},{"title":"枚举合法率","detail":"合法城市清单用 IN 列表或关联标准表；NOT IN 遇到 NULL 的坑要规避。"},{"title":"格式校验","detail":"正则或长度判断 register_time 是否为标准格式。"},{"title":"提交判分","detail":"四个比率与预期一致后提交。"}]$j$,
     $j$["teach.users"]$j$,
     $q$SELECT COUNT(*) AS total FROM users;$q$,
     $q$SELECT ROUND(AVG(CASE WHEN mobile IS NULL THEN 1.0 ELSE 0.0 END), 4) AS mobile_null_rate,
       ROUND(AVG(CASE WHEN city IN ('杭州', '上海', '北京', '深圳') THEN 1.0 ELSE 0.0 END), 4) AS city_valid_rate
FROM users;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"mobile\",\"city\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 2,
     $j$["数据质量","数据标准"]$j$,
     $j$[{"title":"NOT IN 与 NULL","detail":"col NOT IN (1,2,NULL) 永远返回空集——ANY 与 NULL 比较结果是 UNKNOWN。要么保证列表无 NULL，要么改用 LEFT JOIN + IS NULL 的反连接写法。"},{"title":"格式校验的成本","detail":"正则校验在大表上不便宜；能靠类型约束（强类型字段）就不要靠运行时正则。校验应该前移到接入层，质量层只做兜底。"}]$j$),
    ('dg-m1', 'dg-l1-03', '跨表勾稽：订单、支付与退款', 'L2', 'hard', 'SQL',
     '三张表各说各话：订单金额、支付金额、退款金额之间必须能勾稽对平，差多少要能拆解到原因。',
     '财务月度对账要求：订单表"已支付金额"= 支付流水成功金额；退款金额 <= 支付金额。当前差了约 41 万。勾稽的目的不是"必须相等"，而是把差异拆解成已知的类别（时点差、状态差、重复流水）。',
     '输出三方金额、两两差值，并按差异类别（未支付订单 / 支付无订单 / 重复流水）给出金额分解。',
     $j$[{"title":"分别取三方金额","detail":"三个子查询各自按自己的口径取数。"},{"title":"FULL OUTER 对明细","detail":"按 order_id 全外连接，找\"只有一边有\"的订单。"},{"title":"拆解差异类别","detail":"每类差异给金额小计，总和必须等于总差值。"},{"title":"提交判分","detail":"差异能完全分解后提交。"}]$j$,
     $j$["teach.orders_raw","teach.payments","teach.refunds"]$j$,
     $q$SELECT SUM(quantity * unit_price) FROM orders_raw WHERE order_status = 'paid';$q$,
     $q$SELECT (SELECT COALESCE(SUM(quantity * unit_price), 0) FROM orders_raw
          WHERE order_status = 'paid' AND is_deleted = false AND quantity > 0) AS order_paid_amount,
       (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE pay_status = 'SUCCESS') AS paid_amount,
       (SELECT COALESCE(SUM(refund_amount), 0) FROM refunds) AS refund_amount,
       (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE pay_status = 'SUCCESS')
         - (SELECT COALESCE(SUM(refund_amount), 0) FROM refunds) AS net_after_refund;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"order_status\",\"is_deleted\",\"quantity\",\"unit_price\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     75, 35, 'published', 1, 3,
     $j$["数据质量","指标管理"]$j$,
     $j$[{"title":"勾稽的本质","detail":"勾稽不是要求两个数相等，而是要求\"差异可分解且各部分可解释\"。能写自动化的差异分解 SQL，财务对账就从人工变成例行任务。"},{"title":"FULL OUTER JOIN 的使用场景","detail":"需要同时看\"左有右无\"和\"右有左无\"时用它；两侧都用 COALESCE 补键，否则 NULL 键行会互相配不上。"}]$j$),
    ('dg-m2', 'dg-l2-01', '读懂元数据：表、字段与责任人', 'L3', 'normal', 'SQL',
     '元数据是数据的说明书：找表、看口径、认责任人，三步定位一个陌生表。',
     '新同事接手一个报表，上游是 dwd_order_detail。他需要在元数据管理里回答三个问题：这张表谁负责、每天几点更新、pay_amount 的口径是什么。元数据缺失或过期的表，在治理平台上会被标记为"不可信资产"。',
     '基于元数据信息输出：表责任人、更新节奏、关键列口径；并把结论写成三行注释留在 SQL 里。',
     $j$[{"title":"查表负责人","detail":"在元数据管理搜索表名，查看责任人与更新信息。"},{"title":"读列口径","detail":"看字段描述与数据字典，确认 pay_amount 的口径说明。"},{"title":"固化结论","detail":"把三个问题的答案写成 SQL 注释与 UNION ALL 结果。"},{"title":"提交判分","detail":"三项信息与元数据一致后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 在元数据管理里查 dwd_order_detail 的资产信息，把结论固化为查询$q$,
     $q$SELECT 'owner' AS item, '数据平台组' AS value
UNION ALL
SELECT 'schedule', '每日 02:10'
UNION ALL
SELECT 'amount 口径', 'quantity * unit_price，已支付有效订单';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 20, 'published', 1, 1,
     $j$["元数据管理","资产目录"]$j$,
     $j$[{"title":"元数据的三要素","detail":"技术元数据（类型/分区/行数）、业务元数据（口径/责任人/更新节奏）、运营元数据（访问热度/下游数量）。判断一张表能不能信，三条都要看。"},{"title":"不可信资产的信号","detail":"无责任人、90 天无更新、口径描述为空——命中任何一条都不要直接引用，先找数据负责人确认。"}]$j$),
    ('dg-m2', 'dg-l2-02', '血缘分析：改这张表会炸到谁', 'L3', 'normal', 'SQL',
     '上游改字段类型之前，先跑一遍下游影响面：哪些任务、哪些看板、哪些指标会受影响。',
     '有人想把 ods_orders_raw.pay_amount 从 DECIMAL(18,2) 改成 DECIMAL(20,4)。这个改动会顺着血缘影响 dwd_order_detail、dws_shop_sales_daily、ads_gmv_daily 和 6 张看板。数据血缘把"谁读了这张表的这个字段"变成了可查询的事实。',
     '输出 ods_orders_raw.pay_amount 的直接下游清单（任务/表），并标注每一层的读取方式（全量/增量）。',
     $j$[{"title":"查直接下游","detail":"在数据血缘里按字段检索，列出直接读取该字段的任务与表。"},{"title":"递归到末端","detail":"沿着 DWD → DWS → ADS → 看板补全整条链。"},{"title":"标注读取方式","detail":"全量重算的下游可以放心改，增量水位的下游需要兼容旧值。"},{"title":"提交判分","detail":"影响面清单与标准答案一致后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw","teach.orders_raw","teach.orders_raw"]$j$,
     $q$-- 在数据血缘平台按字段检索 pay 相关的下游，把影响面固化为查询$q$,
     $q$SELECT 'dwd_order_detail' AS downstream, '全量重算' AS read_mode
UNION ALL
SELECT 'dws_shop_sales_daily', '增量依赖 DWD'
UNION ALL
SELECT 'ads_gmv_daily', '增量依赖 DWS';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     75, 30, 'published', 1, 2,
     $j$["数据血缘","语义依赖图"]$j$,
     $j$[{"title":"字段级血缘","detail":"表级血缘只告诉你\"有关系\"，字段级血缘才告诉你\"改这个字段会影响哪列\"。做破坏性变更前必须看字段级。"},{"title":"读取方式决定兼容策略","detail":"全量重算的下游：上游改完重跑即可。增量/水位下游：上游必须保留旧值兼容期，否则会出现\"新旧口径拼接\"。"}]$j$),
    ('dg-m2', 'dg-l2-03', '语义依赖图与口径冲突评审', 'L3', 'hard', 'SQL',
     '两个团队各自建了一个"活跃买家"指标，口径不同。用语义依赖图定位冲突并发起评审。',
     '运营的"活跃买家"= 当日有浏览；数据的"活跃买家"= 当日有下单。同名指标在语义依赖图上有两条依赖链，看板配错了就出事故。治理流程：发现冲突 → 发起语义资产评审 → 统一口径或改名 → 下游整改。',
     '用 SQL 输出两个口径各自的定义、依赖表与当日结果值，并标注哪个看板引用了哪个口径。',
     $j$[{"title":"找出同名指标","detail":"在语义资产评审里搜索\"活跃买家\"，确认存在两条登记。"},{"title":"对比口径与依赖","detail":"每个口径的过滤条件、来源表、下游看板列清楚。"},{"title":"给出评审建议","detail":"合并成一个指标加维度过滤，或改名区分，写明理由。"},{"title":"提交判分","detail":"结论与评审标准一致后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$-- 口径 A：当月有行为的独立用户
SELECT COUNT(DISTINCT user_id) FROM orders_raw;$q$,
     $q$SELECT 'browse' AS metric_code, COUNT(DISTINCT user_id) AS metric_value FROM orders_raw
UNION ALL
SELECT 'order', COUNT(DISTINCT user_id) FROM orders_raw
  WHERE is_deleted = false AND quantity > 0
    AND order_status IN ('paid', 'shipped', 'completed');$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"user_id\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     75, 35, 'published', 1, 3,
     $j$["语义资产评审","语义依赖图"]$j$,
     $j$[{"title":"同名指标是事故之源","detail":"看板配置引用的是\"指标名\"，名字一样口径不一样，报表就会悄悄错。治理的手段是唯一登记 + 评审收口，而不是口头约定。"},{"title":"评审的价值","detail":"评审不是流程负担，是把\"两个团队各自的正确\"变成\"全公司一个正确\"的唯一机会。评审记录就是未来的口径文档。"}]$j$),
    ('dg-m3', 'dg-l3-01', '命名与编码标准落地', 'L3', 'normal', 'SQL',
     '按数据标准审查一张新表：分层前缀、命名风格、主键与分区约定。',
     '数据标准规定：ODS 层 ods_ 前缀、DWD 层 dwd_、DWS 层 dws_、ADS 层 ads_；字段小写下划线；每表必须有主键注释与分区说明。同事建的表叫 OrderDetail_DWD，主键无注释——按标准应整改。',
     '输出违规项清单（表名、字段名、注释缺失），并给出符合标准的建表语句。',
     $j$[{"title":"对照标准逐项检查","detail":"分层前缀、大小写、缩写、主键注释、分区说明。"},{"title":"输出违规清单","detail":"每行一个违规项与原因。"},{"title":"给出整改 DDL","detail":"写一份符合标准的建表语句作为整改样例。"},{"title":"提交判分","detail":"违规项与整改 DDL 都符合标准后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 对照数据标准逐项检查对象名，输出违规项清单$q$,
     $q$SELECT 'OrderDetail_DWD' AS object_name, 'dwd_order_detail' AS fixed_name, '层级前缀位置错误' AS issue
UNION ALL
SELECT 'UserName', 'user_name', '应使用小写下划线';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 1,
     $j$["数据标准","元数据管理"]$j$,
     $j$[{"title":"命名标准的真正作用","detail":"统一的分层前缀让人一眼判断\"这是哪一层、能不能直接给报表用\"；统一的字段名让跨表 JOIN 的条件写法可预期。标准不是为了好看，是为了降低协作成本。"}]$j$),
    ('dg-m3', 'dg-l3-02', '敏感字段分类分级', 'L3', 'normal', 'SQL',
     '识别用户表里的敏感字段并给出分级；理解为什么"手机号脱敏"必须在 SQL 层养成习惯。',
     'ods_users 里有 mobile、user_name、address。按公司分级标准：手机号是 L3（高敏感，必须脱敏展示）、姓名 L2、地址 L2。任何输出手机号原文的查询/导出都会被查询审计拦截并告警。',
     '输出敏感字段清单与级别；写一条"可对外展示"的用户查询：手机号中间四位打码。',
     $j$[{"title":"识别敏感字段","detail":"对照分级标准给每个字段标注级别。"},{"title":"学习脱敏写法","detail":"CONCAT(LEFT(mobile,3), '****', RIGHT(mobile,4))；或平台提供的 mask 函数。"},{"title":"改写查询","detail":"输出打码后的手机号，其余字段保持原样。"},{"title":"提交判分","detail":"打码格式与级别清单都正确后提交。"}]$j$,
     $j$["teach.users"]$j$,
     $q$SELECT user_id, user_name, mobile, city
FROM users
LIMIT 10;$q$,
     $q$SELECT user_id, user_name,
       CASE WHEN mobile IS NULL THEN NULL
            ELSE CONCAT(LEFT(mobile, 3), '****', RIGHT(mobile, 4)) END AS mobile_masked,
       city
FROM users
WHERE city = '杭州'
ORDER BY user_id;$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"mobile\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 2,
     $j$["分类分级","查询审计"]$j$,
     $j$[{"title":"脱敏要在最内层做","detail":"脱敏函数写在外层子查询之外，内层一旦被复用就会泄露。习惯在\"取数那一步\"就脱敏，而不是展示前。"},{"title":"分级与审计的关系","detail":"分类分级是元数据，查询审计是执行记录。审计规则按级别配置：L3 字段原文出现在结果集里直接告警——这就是\"为什么 SQL 层要养成脱敏习惯\"的技术兜底。"}]$j$),
    ('ops-m1', 'ops-l2-01', '把 SQL 变成任务节点', 'L2', 'normal', 'SQL',
     '在 SQL 开发里验证过的脚本，怎么变成每天 02:10 自动跑的任务。',
     'DWD 清洗 SQL 已经在 SQL 开发里验证通过。生产化要做三件事：登记脚本与产出表、配置调度周期（每日 02:10）、声明产出表的分区。这三件事做完，这条 SQL 才从"脚本"变成"任务"。',
     '按任务定义规范写出：任务名、脚本引用、产出表与分区、调度周期与超时时间。',
     $j$[{"title":"登记任务元信息","detail":"任务名、责任人、脚本、产出表与分区说明。"},{"title":"配置调度","detail":"每日 02:10；上游就绪才触发，不是纯时间触发。"},{"title":"声明超时","detail":"给任务设超时（如 60 分钟），避免挂死占资源。"},{"title":"提交判分","detail":"任务定义四要素齐全后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 把已验证的清洗 SQL 登记为任务：任务名、脚本、产出表、调度、超时$q$,
     $q$SELECT 'dwd_order_detail_daily' AS task_name, '每日 02:10' AS schedule,
       'dwd_order_detail' AS output_table, '60 分钟' AS timeout
UNION ALL
SELECT '声明上游就绪依赖', '非纯时间触发', '产出按 dt 分区', '重试 3 次';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 1,
     $j$["工作流","SQL开发"]$j$,
     $j$[{"title":"脚本 ≠ 任务","detail":"脚本是 SQL 文本，任务是\"脚本 + 调度 + 依赖 + 超时 + 告警 + 责任人\"的完整定义。缺少任何一项，它都不该出现在生产调度里。"},{"title":"事件依赖优于时间依赖","detail":"02:10 触发只是兜底；正确做法是声明\"上游 DWS 就绪后触发\"，让平台管依赖，而不是靠猜上游几点跑完。"}]$j$),
    ('ops-m1', 'ops-l2-02', '依赖编排：DWS 依赖 DWD', 'L2', 'normal', 'SQL',
     '把 DWD → DWS → ADS 三层任务串成有向无环图，理解为什么禁止循环依赖。',
     '聚合层 DWS 必须等 DWD 完成，ADS 等 DWS。把依赖声明在工作流里，平台会按拓扑序调度；如果有人把依赖配成了环（A→B→A），整条链永远无法启动。',
     '给出三个任务的依赖声明与执行顺序；说明如果 DWD 失败，DWS/ADS 的行为。',
     $j$[{"title":"列出三层任务","detail":"明确每层读什么、写什么。"},{"title":"声明依赖","detail":"每个任务只声明直接上游，传递依赖由平台解析。"},{"title":"推演失败场景","detail":"DWD 失败 → DWS/ADS 不应启动；上游重跑成功后下游补跑。"},{"title":"提交判分","detail":"依赖声明与失败推演都正确后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw","teach.orders_raw"]$j$,
     $q$-- 列出三层任务并声明直接上游$q$,
     $q$SELECT 'dwd_order_detail_daily' AS task_name, '无上游' AS depends_on
UNION ALL
SELECT 'dws_shop_sales_daily', 'dwd_order_detail_daily'
UNION ALL
SELECT 'ads_gmv_daily', 'dws_shop_sales_daily';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 30, 'published', 1, 2,
     $j$["工作流","任务调度"]$j$,
     $j$[{"title":"DAG 与拓扑序","detail":"依赖图必须是无环的。平台按拓扑序调度：入度归零才执行。理解这一点，\"任务为什么没被调度\"这类问题就能自己推理。"},{"title":"失败传播","detail":"上游失败时下游默认不执行（避免用错数据）；需要\"继续跑但打标\"的场景要显式配置弱依赖，并承担口径风险。"}]$j$),
    ('ops-m1', 'ops-l2-03', '失败重试、超时与告警', 'L2', 'normal', 'SQL',
     '瞬时故障自动重试、真失败及时告警——配错了重试，要么雪崩要么漏报。',
     '上游数据库偶发超时是常态（重试 3 次、间隔 5 分钟就能恢复），但数据错误类失败重试多少次都没用。告警要区分：重试耗尽才告警；告警必须带任务名、实例时间与失败原因，直接发到值班群。',
     '为 DWD 任务配置：重试 3 次/间隔 5 分钟、超时 60 分钟、重试耗尽后告警；并说明哪类失败重试无效。',
     $j$[{"title":"区分两类失败","detail":"环境类（超时/连接）可重试；数据类（脏数据/口径错误）重试无效。"},{"title":"配置重试与超时","detail":"按任务实际耗时设置超时，宁大勿小，避免正常任务被杀。"},{"title":"配置告警","detail":"告警内容带上下文；告警无人处理等于没有告警。"},{"title":"提交判分","detail":"配置合理且区分了失败类型后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 区分两类失败：环境类可重试，数据类重试无效$q$,
     $q$SELECT 'dwd_order_detail_daily' AS task_name, '3 次 / 间隔 5 分钟' AS retry_policy,
       '重试耗尽后告警值班群' AS alert_policy
UNION ALL
SELECT '数据错误（脏数据/口径）', '重试无效', '直接告警并附失败原因';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 30, 'published', 1, 3,
     $j$["任务调度","告警中心"]$j$,
     $j$[{"title":"重试是给瞬时故障的","detail":"重试的成本是\"把同一个错误重复三遍\"。数据正确性错误重试没有意义，反而推迟告警——先判断失败类型再谈重试。"},{"title":"告警的信噪比","detail":"每条告警都要值得有人点开。误报多的告警群最终会变成无人看管的死群，真正的故障反而没人发现。"}]$j$),
    ('ops-m2', 'ops-l3-01', '发布前检查清单', 'L3', 'hard', 'SQL',
     '发布管理要求逐项确认：质量核查通过、依赖声明完整、产出分区正确、回滚方案明确。',
     'DWD 任务首次发布。发布管理有硬性检查项：质量核查全绿、依赖已声明、产出分区与任务周期匹配、回滚方案（重跑上游）明确、责任人已确认。任何一项缺失，评审都会打回。',
     '逐项输出检查结果（通过/缺失），并对"产出分区"给出验证 SQL。',
     $j$[{"title":"逐项自检","detail":"按发布清单六项逐条确认，缺失项如实标注。"},{"title":"验证产出分区","detail":"分区存在、行数大于 0、且与输入分区对得上。"},{"title":"写回滚方案","detail":"失败时：删除当日分区 + 重跑上游 + 重跑本任务。"},{"title":"提交判分","detail":"六项检查与回滚方案完整后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$-- 发布前逐项自检：质量核查、依赖、分区、回滚方案$q$,
     $q$SELECT '质量核查全绿' AS check_item, '通过' AS result
UNION ALL
SELECT '依赖声明完整', '通过'
UNION ALL
SELECT '产出分区与任务周期匹配', '通过'
UNION ALL
SELECT '回滚方案（删分区重跑上游）', '已明确';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     75, 35, 'published', 1, 1,
     $j$["发布管理","数据质量"]$j$,
     $j$[{"title":"发布清单为什么存在","detail":"事故复盘里 80% 的\"低级失误\"都能被清单拦住：忘分区条件、忘声明依赖、没想回滚。清单是把别人的事故变成你的检查项。"},{"title":"可回滚才敢发布","detail":"发布前就要写清\"出了问题怎么退\"：删分区重跑是最小回滚单元。说不出回滚方案的发布，本质是把风险转给值班同学。"}]$j$),
    ('ops-m2', 'ops-l3-02', '运行验收：跑通 R1-R5', 'L3', 'hard', 'SQL',
     '平台用五个验收门（R1-R5）判断环境是否达标：兼容矩阵、空环境重建、任务链路、血缘完整、审计齐全。',
     '运行验收页面列出了 R1-R5 五道门。你的验收任务：逐门验证并记录证据——R1 跨引擎读写、R2 空环境重建、R3 任务链路跑通、R4 血缘完整、R5 审计可查。任何一道门不过，环境不算交付。',
     '输出五道门的验证结论与证据（SQL 或截图说明），构成一份验收记录。',
     $j$[{"title":"逐门验证","detail":"按 R1-R5 顺序执行，每门记录\"做了什么 + 结果\"。"},{"title":"收集证据","detail":"每门至少一条可复现的证据（SQL 结果或页面状态）。"},{"title":"形成验收记录","detail":"五门结论汇总，标注任何\"有条件通过\"项。"},{"title":"提交判分","detail":"五门齐全、证据可核后提交。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$-- 逐门验证 R1-R5 并收集证据$q$,
     $q$SELECT 'R1 跨引擎读写' AS gate, '通过：同一张表双引擎查询一致' AS evidence
UNION ALL
SELECT 'R2 空环境重建', '通过：迁移后数据完整'
UNION ALL
SELECT 'R3 任务链路', '通过：DWD-DWS-ADS 全链成功'
UNION ALL
SELECT 'R4 血缘完整', '通过：字段级上下游可见'
UNION ALL
SELECT 'R5 审计可查', '通过：查询记录含用户与语句';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     75, 35, 'published', 1, 2,
     $j$["运行验收","运维中心"]$j$,
     $j$[{"title":"验收门的意义","detail":"环境问题在验收阶段暴露的成本最低。五个门各自覆盖一类事故源：兼容性、可重建性、链路完整性、可追溯性、可审计性。"}]$j$),
    ('ops-m3', 'ops-l3-03', '从告警到根因：定位一条慢 SQL', 'L3', 'hard', 'SQL',
     '凌晨 02:30 DWS 任务告警超时。用运行概览与查询审计定位：是数据量暴涨、忘了分区裁剪，还是 JOIN 放大。',
     'DWS 任务平时 8 分钟，今天 65 分钟超时。排障三板斧：看运行概览的任务耗时曲线（是变慢还是上游晚）、看 SQL 的扫描量（分区裁剪是否失效）、看 JOIN 后行数（是否被放大）。今天的原因是新人改 SQL 时把分区条件写进了子查询外层。',
     '输出排障结论：耗时对比、扫描分区数对比、根因描述与修复 SQL。',
     $j$[{"title":"先看趋势再猜原因","detail":"耗时突变通常是变更引起：查这个任务近期的 SQL 变更记录。"},{"title":"量扫描量","detail":"对比修复前后 EXPLAIN 的扫描分区数：1 个分区 vs 3.2 亿行全表。"},{"title":"修复并验证","detail":"把分区条件下推到读表的那一层，重跑验证耗时回到 8 分钟。"},{"title":"提交判分","detail":"根因描述与修复 SQL 都正确后提交。"}]$j$,
     $j$["teach.orders_raw","teach.orders_raw"]$j$,
     $q$-- 排障第一步：对比逐日耗时，找出从哪天开始变慢$q$,
     $q$SELECT '根因' AS item, '分区条件写在外层查询，分区裁剪失效退化为全表扫描' AS detail
UNION ALL
SELECT '修复', '把 dt 过滤下推到读表的那一层子查询'
UNION ALL
SELECT '验证', '重跑耗时回到 8 分钟，扫描仅命中当日分区';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true,\"requiredColumns\":[\"dt\",\"quantity\",\"unit_price\"]}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     75, 35, 'published', 1, 1,
     $j$["任务监控","告警中心","SQL开发"]$j$,
     $j$[{"title":"排障三板斧","detail":"看曲线（何时开始变慢）、看扫描（读了多少数据）、看行数（JOIN 是否放大）。三步能定位绝大多数任务性能问题。"},{"title":"分区条件下推","detail":"分区过滤必须写在\"直接读表\"的那层查询里。包在外层查询或 JOIN 之后的条件，引擎优化器可能无法下推，导致全表扫描。"}]$j$),
    ('ops-m3', 'ops-l3-04', '审计：谁动过这张表', 'L3', 'normal', 'SQL',
     '用查询审计回答合规三问：谁、在什么时候、对这张表做了什么。',
     '安全团队要求说明过去 24 小时内所有读取 ods_users 的人与用途。查询审计记录每次查询的用户、时间、语句与扫描量。审计不是事后追责的工具，而是让"查数据"这件事天然带责任。',
     '输出过去 24 小时访问 ods_users 的记录：用户、时间、扫描行数、语句摘要；标出扫描量异常的记录。',
     $j$[{"title":"拉取审计记录","detail":"按表名 + 时间窗过滤审计日志。"},{"title":"识别异常","detail":"全表扫描用户表的明细查询、非工作时间的大查询要标注。"},{"title":"输出结论","detail":"每条异常给一句\"是否合理\"的判断与依据。"}]$j$,
     $j$["teach.users"]$j$,
     $q$-- 从查询审计里按表名与时间窗拉取访问记录$q$,
     $q$SELECT 'report_svc' AS user_name, '2026-11-13 09:12' AS query_time, '明细查询全表扫描' AS finding
UNION ALL
SELECT 'ops_wang', '2026-11-13 14:03', '读取 L3 敏感字段原文，需脱敏复核';$q$,
     $j$"{\"requireSelect\":true,\"forbiddenKeywords\":[\"insert\",\"update\",\"delete\",\"drop\",\"alter\",\"truncate\",\"grant\",\"create\",\"merge\",\"call\"],\"disallowSelectStar\":true}"$j$,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"与标准答案行数一致"},{"key":"filters","name":"口径正确","weight":40,"passed":true,"detail":"过滤与聚合口径符合任务要求"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"列裁剪、别名与缩进符合规范"}]$j$,
     70, 25, 'published', 1, 2,
     $j$["查询审计","审计日志","分类分级"]$j$,
     $j$[{"title":"审计的三个问题","detail":"谁（用户）、何时（时间）、做了什么（语句与扫描量）。审计记录要能独立回答这三个问题，缺一不可。"},{"title":"审计与分级联动","detail":"L3 字段出现在查询结果里自动升级为高优先级事件。这也是\"SQL 层养成脱敏习惯\"的技术兜底。"}]$j$)
ON CONFLICT (code) DO NOTHING;

-- 端到端项目：把任务串成可交付成果。project 表没有 path_code 列（V5 定义），
-- 项目与路径的关联通过任务归属隐式表达。
INSERT INTO learning.project (code, title, summary, level, scenario, deliverables, status, order_no) VALUES
    ('proj-gmv', '交易域 GMV 链路', '从 ODS 原始流水到 ADS 日报：清洗、去重、聚合、口径对账，交付一条可重跑的 GMV 链路。', 'L2', '为云集电商交易域搭建一条从原始流水到日报指标的完整链路，所有产出必须可重跑、可对账。', $j$["DWD 明细表","DWS 店铺日聚合","GMV 日报","口径对账说明"]$j$, 'published', 10),
    ('proj-quality', '交易域质量防线', '为交易域核心表配置质量核查：表级波动、列级空值率、跨表勾稽，并接通报警。', 'L2', '给交易域三张核心表配上质量核查规则，把对账从人工变成例行任务。', $j$["核查规则清单","差异分解 SQL"]$j$, 'published', 11),
    ('proj-lineage', '字段变更影响面评估', '模拟一次字段类型变更：从血缘定位影响面，输出变更方案与回滚预案。', 'L3', 'pay 相关字段要做类型变更，先评估影响面再动手。', $j$["影响面清单","变更与回滚方案"]$j$, 'published', 12)
ON CONFLICT (code) DO NOTHING;

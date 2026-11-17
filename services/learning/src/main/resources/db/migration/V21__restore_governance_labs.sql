-- 将两个固定文案任务改造成数据驱动、可真实判分的任务。
-- 标准答案均来自 teach.table_asset，学员可通过查询推导。

UPDATE learning.lab
SET title = '读懂元数据：表、字段与责任人',
    objective = '输出 ods_orders_raw 的表名、层级、责任人、行数与最近刷新日期；一行结果。',
    status = 'published',
    expected_sql = $q$SELECT table_name, layer, owner, row_count, last_refresh
FROM table_asset
WHERE table_name = 'ods_orders_raw';$q$,
    starter_sql = $q$SELECT * FROM table_asset LIMIT 5;$q$,
    table_refs = '["teach.table_asset"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["table_name","owner","row_count"]}'::jsonb,
    steps = $j$[{"title":"打开资产表","detail":"查询 table_asset，确认 ods_orders_raw 的一行记录。"},{"title":"选择字段","detail":"只取 table_name、layer、owner、row_count、last_refresh 五列。"},{"title":"核对结果","detail":"确认行数为 1 且刷新日期可读，再提交判分。"}]$j$,
    dataset_refs = '["teach.table_asset@1"]'::jsonb,
    updated_at = now()
WHERE code = 'dg-l2-01';

UPDATE learning.lab
SET title = '资产健康巡检：状态与质量分',
    objective = '输出所有非 online 或质量分低于 90 的表：表名、层级、责任人、状态、质量分，按质量分升序、表名升序。',
    status = 'published',
    expected_sql = $q$SELECT table_name, layer, owner, status, quality_score
FROM table_asset
WHERE status <> 'online' OR quality_score < 90
ORDER BY quality_score, table_name;$q$,
    starter_sql = $q$SELECT table_name, status, quality_score FROM table_asset LIMIT 10;$q$,
    table_refs = '["teach.table_asset"]'::jsonb,
    static_policy = '{"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredColumns":["status","quality_score"]}'::jsonb,
    steps = $j$[{"title":"先看资产全集","detail":"查询 table_asset 的状态与质量分布。"},{"title":"组合过滤条件","detail":"用 status <> ''online'' OR quality_score < 90 筛选异常资产。"},{"title":"排序并提交","detail":"按质量分、表名升序输出，核对行数后提交。"}]$j$,
    knowledge = $j$[{"title":"巡检口径","detail":"状态异常与质量分偏低是两类不同风险，用 OR 合并后仍要在输出中保留各自字段。"},{"title":"NULL 与阈值","detail":"quality_score 若为 NULL，比较结果未知；生产中应显式处理 NULL 而不是假设它达标。"}]$j$,
    dataset_refs = '["teach.table_asset@1"]'::jsonb,
    updated_at = now()
WHERE code = 'dg-l3-01';
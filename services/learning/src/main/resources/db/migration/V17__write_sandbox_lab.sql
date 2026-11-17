-- 写任务沙箱：在学员 schema 内执行单条 UPDATE，判分对比最终表状态并重置目标表。
INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge)
VALUES
    ('de-m2', 'de-l2-08', '修正脏数据：UPDATE 与 WHERE 边界', 'L2', 'medium', 'SQL_WRITE',
     '把负数量订单修正为 0；练习写语句的 WHERE 边界与幂等性。',
     '订单表里存在数量为负的异常行，需要在不影响已删除订单的前提下把数量归零。写任务只允许在沙箱副本上执行，判分依据是最终表状态。',
     '把 quantity < 0 且 is_deleted = false 的订单数量改为 0；其他行保持不变。',
     $j$[{"title":"先定位异常行","detail":"用 SELECT 查看 quantity < 0 的订单，确认哪些行未删除。"},{"title":"写 UPDATE","detail":"只更新 quantity < 0 且 is_deleted = false 的行；不要改已删除订单。"},{"title":"核对并提交","detail":"提交后会执行写语句、比对最终表状态，并把目标表重置为教学初始数据。"}]$j$,
     $j$["teach.orders_raw"]$j$,
     $q$SELECT order_id, quantity, is_deleted
FROM orders_raw
WHERE quantity < 0;$q$,
     $q$UPDATE orders_raw
SET quantity = 0
WHERE quantity < 0
  AND is_deleted = false;$q$,
     $j$"{\"allowWrite\":true,\"targetTable\":\"orders_raw\",\"stateQuery\":\"SELECT order_id, user_id, sku_id, category, quantity, unit_price, order_status, is_deleted, created_at, updated_at, dt, shop_id FROM orders_raw ORDER BY order_id, dt, sku_id, user_id NULLS FIRST, created_at, unit_price, quantity, order_status, is_deleted\",\"forbiddenKeywords\":[\"insert\",\"delete\",\"create\",\"drop\",\"alter\",\"truncate\",\"grant\",\"copy\",\"merge\",\"call\"],\"requiredColumns\":[\"update\",\"where\",\"is_deleted\"]}"$j$,
     $j$[{"key":"rows","name":"影响行数","weight":40,"passed":true,"detail":"最终表状态与标准答案一致"},{"key":"filters","name":"WHERE 边界","weight":40,"passed":true,"detail":"只修正未删除的负数量行"},{"key":"style","name":"SQL 规范","weight":20,"passed":true,"detail":"单条 UPDATE 且无条件拼接"}]$j$,
     60, 20, 'published', 1, 5,
     $j$["SQL开发","数据质量"]$j$,
     $j$[{"title":"写语句的 WHERE 边界","detail":"写错 WHERE 会永久改坏沙箱数据；先用 SELECT 验证边界，再执行 UPDATE。"},{"title":"写任务判分","detail":"判分执行后会比对最终表状态，并把目标表重置为教学初始数据，方便反复练习。"}]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('de-l2-08', 'de-quality-check', 1.0)
ON CONFLICT (lab_code, skill_code) DO NOTHING;
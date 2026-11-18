-- starter_sql 可执行性修复：de-l1-03 原 starter 用 user_id != 'xxx'，
-- 而 user_id 是 bigint，学员首次运行会直接报类型错误。
UPDATE learning.lab
   SET starter_sql = $q$SELECT COUNT(*) AS total_orders
FROM orders_raw;
-- 再分别统计注册用户（user_id IS NOT NULL）与游客（user_id IS NULL）$q$,
       updated_at = now()
 WHERE code = 'de-l1-03';
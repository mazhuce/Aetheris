-- 数据集引用修复：de-l2-03 与 de-l2-05 的 table_refs/dataset_refs
-- 重复声明了 orders_raw，却缺少实际 JOIN 的字典/维度表。
UPDATE learning.lab
   SET table_refs = '["teach.orders_raw","teach.order_status_dict"]'::jsonb,
       dataset_refs = '["teach.orders_raw@1","teach.order_status_dict@1"]'::jsonb,
       updated_at = now()
 WHERE code = 'de-l2-03';

UPDATE learning.lab
   SET table_refs = '["teach.orders_raw","teach.shop_dim"]'::jsonb,
       dataset_refs = '["teach.orders_raw@1","teach.shop_dim@1"]'::jsonb,
       updated_at = now()
 WHERE code = 'de-l2-05';
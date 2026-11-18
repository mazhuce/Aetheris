-- 补齐首个写任务的数据集引用：V17 插入时未绑定 dataset_refs，
-- 导致任务页不展示样例数据来源与许可。
UPDATE learning.lab
   SET dataset_refs = '["teach.orders_raw@1"]'::jsonb,
       updated_at = now()
 WHERE code = 'de-l2-08'
   AND coalesce(jsonb_array_length(dataset_refs), 0) = 0;
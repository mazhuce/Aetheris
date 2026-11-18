-- 课程内容一致性修订：三处 objective 与标准答案的口径/列不一致，
-- 以标准答案为准改任务描述，避免学员照描述写出判分失败的正确查询。
UPDATE learning.lab
   SET objective = '只输出 order_id、user_id、amount 三列（amount = quantity * unit_price），行数与 2026-08-01 分区一致，不出现重复列。',
       updated_at = now()
 WHERE code = 'de-l1-01';

UPDATE learning.lab
   SET objective = '输出 mobile 空值率与 city 合法率（city 属于杭州/上海/北京/深圳，保留四位小数），一次查询完成。',
       updated_at = now()
 WHERE code = 'dg-l1-02';

UPDATE learning.lab
   SET objective = '输出杭州用户的可对外展示查询：user_id、user_name、手机号中间四位打码（NULL 保持 NULL）、city，按 user_id 升序。',
       updated_at = now()
 WHERE code = 'dg-l3-02';
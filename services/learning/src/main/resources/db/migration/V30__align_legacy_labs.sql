-- 课程内容一致性修订（第三批）：旧课程描述与教学样本的标准答案脱节，
-- 涉及阈值、状态编码、输出列数/列名、日期分区等；全部以标准答案为准修订。
UPDATE learning.lab
   SET objective = $q$筛选已支付（order_status = 'paid'）且金额（quantity * unit_price）大于 500 的订单，只输出 order_id、user_id、amount（amount 为计算列），按金额倒序取前 100 行。$q$,
       steps = $j$[{"title":"补齐过滤条件","detail":"状态用 'paid'；金额条件写 quantity * unit_price > 500，并排除非正数量。"},{"title":"排序","detail":"ORDER BY quantity * unit_price DESC；注意 NULL 的排序位置因引擎而异。"},{"title":"限制行数","detail":"LIMIT 100 放在最后；排序在 LIMIT 之前执行，拿到的是全量排序后的前 100。"},{"title":"提交判分","detail":"核对结果后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l1-02';

UPDATE learning.lab
   SET objective = '一行输出三列：member_orders（注册用户订单数）、guest_orders（游客订单数）、total_orders（总订单数）；user_id 为 NULL 判定为游客。',
       steps = $j$[{"title":"理解三值逻辑","detail":"NULL 与任何值的比较结果都是 UNKNOWN，WHERE 只保留 TRUE；= NULL 永远不成立。"},{"title":"用 IS NULL 区分游客","detail":"COUNT(*) FILTER (WHERE user_id IS NULL) 统计游客订单，IS NOT NULL 统计注册用户订单。"},{"title":"列裁剪与计数","detail":"三个数可以在一行里同时输出；COUNT(*) 与 COUNT(user_id) 的差异要能解释。"},{"title":"提交判分","detail":"三个数字与标准答案对齐后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l1-03';

UPDATE learning.lab
   SET objective = '按 order_status 分组输出 order_cnt（订单数）与 total_amount（quantity * unit_price 之和），只统计 2026-08-01 分区且 quantity > 0 的行，按状态升序。',
       steps = $j$[{"title":"明确口径","detail":"冲正单（负数量）算不算订单量？本任务口径：排除后再统计。"},{"title":"一次聚合","detail":"COUNT(*) 与 SUM(quantity * unit_price) 同一次扫描完成。"},{"title":"命名输出列","detail":"给聚合列起语义化别名：order_cnt、total_amount。"},{"title":"提交判分","detail":"与标准结果比对后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l1-05';

UPDATE learning.lab
   SET objective = '输出三列：order_rows（订单行数）、distinct_orders（订单号去重数）、distinct_users（独立买家数，精确），统计全表。',
       steps = $j$[{"title":"先写精确版","detail":"COUNT(DISTINCT user_id)，注意它天然跳过 NULL（游客不计入买家）。"},{"title":"评估代价","detail":"想清楚去重发生在哪一步：shuffle 全量 user_id 到同一节点。"},{"title":"对照两种计数","detail":"order_rows 是行数，distinct_orders 是订单号去重；两者不相等说明一单多行。"},{"title":"提交判分","detail":"核对三列数字后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l1-06';

UPDATE learning.lab
   SET objective = '输出 shop_name、order_cnt，只保留 order_cnt > 1 的店铺，按 order_cnt 倒序；只统计 2026-08-01 分区且数量为正的订单。',
       steps = $j$[{"title":"先跑通关联","detail":"确认 JOIN 后行数与订单表一致（shop_dim 的 shop_id 唯一）。"},{"title":"加 HAVING","detail":"HAVING COUNT(*) > 1；尝试把它写进 WHERE 观察报错，加深理解。"},{"title":"排序输出","detail":"ORDER BY order_cnt DESC。"},{"title":"提交判分","detail":"核对店铺清单后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l1-07';

UPDATE learning.lab
   SET objective = '输出 order_id、order_status、status_name、is_abnormal_status；孤儿编码的 status_name 统一为「未登记」，is_abnormal_status 标记 TRUE；只处理 2026-08-03 分区。',
       steps = $j$[{"title":"找孤儿编码","detail":"LEFT JOIN 后 status_name IS NULL 的行就是非法枚举。"},{"title":"标记与兜底","detail":"COALESCE 兜底为「未登记」，同时输出 is_abnormal_status 供质量核查。"},{"title":"对齐数据标准","detail":"理解显式处理与静默丢弃的差别：后者会让下游对账永远差几行。"},{"title":"提交判分","detail":"核对标记列后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-02';

UPDATE learning.lab
   SET objective = '在去重逻辑之上只处理 dt = 2026-08-01 的分区；SQL 中显式体现分区条件，输出去重后的订单字段与状态名（未登记兜底）。',
       updated_at = now()
 WHERE code = 'de-l2-03';

UPDATE learning.lab
   SET objective = '一行输出三项核查：input_rows（分区行数）、dedup_rows（订单号去重数）、dup_dropped（被去重丢弃的行数）；分区为 2026-08-01。',
       steps = $j$[{"title":"行数对账","detail":"输入行数 - 去重后行数 = 被去重丢弃的行数，必须能对上。"},{"title":"主键唯一性","detail":"GROUP BY order_id HAVING COUNT(*) > 1，任何结果都算异常。"},{"title":"口径一致","detail":"三项子查询使用同一个分区条件，避免口径漂移。"},{"title":"提交判分","detail":"三项核查指标符合预期后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-04';

UPDATE learning.lab
   SET objective = '按 shop_id × dt 输出 shop_name、order_cnt、gmv、buyer_cnt（买家去重），口径排除冲正单；只统计 2026-08-01。',
       updated_at = now()
 WHERE code = 'de-l2-05';

UPDATE learning.lab
   SET objective = '输出全部日期的 dt、gmv、dod（环比比例，保留四位小数），按 dt 升序；分母为 0 或 NULL 时环比给 NULL 而不是报错。',
       steps = $j$[{"title":"写出当日 GMV","detail":"基于 DWD 再聚合到全站粒度。"},{"title":"用 LAG 取昨日","detail":"LAG(gmv) OVER (ORDER BY dt) 取上一行；理解窗口按 dt 排序的含义。"},{"title":"处理除零","detail":"环比 = (今 - 昨) / 昨；昨为 NULL 或 0 时返回 NULL，用 NULLIF + CASE 组合。"},{"title":"提交判分","detail":"全部日期与环比值比对后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-06';

UPDATE learning.lab
   SET objective = '分别输出支付口径 GMV（payments 成功流水）与履约口径 GMV（未删除、正数量、状态 paid/shipped/completed），一行为两列。',
       steps = $j$[{"title":"分别实现两种口径","detail":"支付口径基于 payments；履约口径基于 orders_raw。"},{"title":"核对差异","detail":"相减得到差值，思考差异来源（未支付订单、退款、状态口径）。"},{"title":"解释样例","detail":"抽几笔差值订单，验证它们确实属于未支付或已退款。"},{"title":"提交判分","detail":"两列金额与标准结果一致后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-07';

UPDATE learning.lab
   SET objective = '输出 2026-08-08 当天的 dt、row_cnt、baseline（历史均值）、fluctuation（波动比例，四位小数）、is_abnormal（波动 > 0.3）。',
       steps = $j$[{"title":"取历史基线","detail":"聚合历史日期行数并求均值，排除当天本身。"},{"title":"算波动比例","detail":"ABS(今日 - 基线) / 基线，注意基线为 0 的除零保护。"},{"title":"阈值判断","detail":"波动超过 0.3 时 is_abnormal 为 true。"},{"title":"提交判分","detail":"五个输出与阈值判断一致后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'dg-l1-01';

UPDATE learning.lab
   SET objective = '一行输出四列：order_paid_amount（已支付订单金额）、paid_amount（成功支付流水）、refund_amount（退款金额）、net_after_refund（支付减退款）。',
       steps = $j$[{"title":"分别取三方金额","detail":"三个子查询各自按自己的口径取数：订单、支付流水、退款。"},{"title":"核对净额","detail":"净额 = 成功支付 - 退款，思考它和已支付订单金额的差异来源。"},{"title":"解释差异","detail":"抽几笔订单对比两种口径，验证差异属于未支付或退款。"},{"title":"提交判分","detail":"四列金额与标准结果一致后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'dg-l1-03';
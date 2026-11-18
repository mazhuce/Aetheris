-- 内容一致性修订（第五批）：清理残留的旧列名与场景口径。
UPDATE learning.lab
   SET steps = $j$[{"title":"验证重复规模","detail":"先查当天有多少 order_id 出现多次，心里有数。"},{"title":"窗口函数去重","detail":"ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) = 1。"},{"title":"验证幂等","detail":"把同一条 SQL 跑两遍比对结果；再想想为什么先 DISTINCT 后随机取一条是不行的。"},{"title":"提交判分","detail":"行数与内容双比对后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-01';

UPDATE learning.lab
   SET knowledge = $j$[{"title":"分区裁剪（Partition Pruning）","detail":"分区列直接出现在 WHERE 的等值/范围比较里，引擎才会只读对应分区。对分区列套函数（如 date_format(dt)）会让裁剪失效，退化成全表扫描。"},{"title":"增量的两种口径","detail":"按分区增量（T-1 全量快照）与按时间戳增量（updated_at > 上次水位）。前者简单可靠可重跑，后者省资源但要管理水位——新人优先掌握前者。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-03';

UPDATE learning.lab
   SET scenario = $q$运营要统计订单的来源结构：注册用户与游客各下多少单。教学样本表 orders_raw 中 user_id 为 NULL 是游客下单（生产对应 ods_orders_raw，约 3.2 亿行，按天分区，含逻辑删除、空用户、负金额等脏数据）。直接 WHERE user_id != '' 或 = NULL 都拿不到正确结果——这是新人最常见的坑。$q$,
       updated_at = now()
 WHERE code = 'de-l1-03';

UPDATE learning.lab
   SET steps = $j$[{"title":"先聚合到日粒度","detail":"用 CTE 按 dt 汇总 GMV，窗口函数在聚合结果上计算。"},{"title":"取前一天","detail":"LAG(gmv) OVER (ORDER BY dt) AS prev_gmv；不要用自关联，窗口函数更清晰。"},{"title":"环比与空值","detail":"(gmv - prev_gmv) / NULLIF(prev_gmv, 0) * 100；第一天 prev_gmv 为 NULL，增长率保持 NULL。"},{"title":"提交判分","detail":"与标准结果比对后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'da-l2-01';

UPDATE learning.lab
   SET knowledge = $j$[{"title":"对账（Reconciliation）","detail":"上下游行数必须可解释：输入 = 输出 + 丢弃 + 过滤。对不上就要人查，这是发现任务默默丢数据的唯一手段。"},{"title":"空值率的计算写法","detail":"AVG(CASE WHEN 目标列 IS NULL THEN 1.0 ELSE 0.0 END) 一次扫描同时算多列空值率，比每列写一个 COUNT 快得多。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l2-04';

UPDATE learning.lab
   SET scenario = 'ods_users（教学样本表 users）中手机号是敏感字段且要求非空可关联；city 是枚举字段，上游新增了两个未登记城市。质量规则本轮先看两项：mobile 空值率与 city 合法率，用一次扫描算完。',
       updated_at = now()
 WHERE code = 'dg-l1-02';

UPDATE learning.lab
   SET scenario = 'ods_users（教学样本表 users）里有 mobile、user_name、address。按公司分级标准：手机号是 L3（高敏感，必须脱敏展示）、姓名 L2、地址 L2。任何输出手机号原文的查询/导出都会被查询审计拦截并告警。',
       updated_at = now()
 WHERE code = 'dg-l3-02';
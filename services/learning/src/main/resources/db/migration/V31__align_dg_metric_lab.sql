-- 内容一致性修订（第四批）：dg-l2-03 描述要求口径定义、依赖表与看板引用，
-- 标准答案只输出两个口径的活跃买家数；以答案为准修订描述与步骤。
UPDATE learning.lab
   SET objective = '输出两个口径的活跃买家数：metric_code（browse = 全量浏览口径，order = 有效下单口径）与 metric_value（去重用户数），两行结果。',
       steps = $j$[{"title":"找出同名指标","detail":"在语义资产评审里搜索「活跃买家」，确认存在两条口径登记。"},{"title":"分别计算","detail":"浏览口径全量去重用户；下单口径排除删除与非正数量，并限定已支付状态。"},{"title":"并排对比","detail":"用 UNION ALL 输出 metric_code 与 metric_value，直观看到口径差异。"},{"title":"提交判分","detail":"两行数值与标准结果一致后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'dg-l2-03';
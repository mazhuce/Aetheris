-- L09 真实样例落地：UCI 在线购物意向数据集（CC BY 4.0，12330 行，无 PII）。
-- 表结构由本迁移创建；数据由 scripts/seed-online-shoppers.mjs 经导入工具填充并登记清单。
-- 任务只依赖表结构与聚合口径，替换数据版本时任务编号与学员进度不变。
CREATE TABLE IF NOT EXISTS teach.online_shoppers (
    administrative integer NOT NULL,
    administrative_duration numeric(10,2) NOT NULL,
    informational integer NOT NULL,
    informational_duration numeric(10,2) NOT NULL,
    product_related integer NOT NULL,
    product_related_duration numeric(12,2) NOT NULL,
    bounce_rates numeric(8,6) NOT NULL,
    exit_rates numeric(8,6) NOT NULL,
    page_values numeric(10,2) NOT NULL,
    special_day numeric(4,2) NOT NULL,
    month varchar(8) NOT NULL,
    operating_systems smallint NOT NULL,
    browser smallint NOT NULL,
    region smallint NOT NULL,
    traffic_type smallint NOT NULL,
    visitor_type varchar(24) NOT NULL,
    weekend boolean NOT NULL,
    revenue boolean NOT NULL
);

COMMENT ON TABLE teach.online_shoppers IS 'UCI Online Shoppers Purchasing Intention (CC BY 4.0, id=468); attribution in artifacts/learning-pivot-20261117/sample-data/LICENSE.txt';

INSERT INTO learning.lab
    (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps, table_refs,
     starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes, status, version, order_no,
     related_features, knowledge, dataset_refs)
VALUES
    ('da-m2', 'da-l2-05', '真实会话转化：访客类型对比', 'L2', 'medium', 'SQL',
     '用真实公开电商会话数据对比新访客与回访访客的转化率；练习 FILTER 聚合与百分比口径。',
     '运营拿到一份真实公开会话数据（UCI 在线购物意向集，12,330 条），想知道新访客与回访访客谁更容易下单、差距多大。请直接对 teach.online_shoppers 聚合，不要导出后再算。',
     '输出 visitor_type、sessions（会话数）、conversions（revenue 为 true 的会话数）、conversion_rate（转化率百分比，保留两位小数），按 conversion_rate 降序、visitor_type 升序。',
     $j$[{"title":"认识真实字段","detail":"revenue 表示该会话是否下单；visitor_type 区分新访客、回访访客与其他。"},{"title":"聚合口径","detail":"按 visitor_type 统计会话数，并用 FILTER 或 CASE 统计 revenue 为 true 的会话。"},{"title":"计算转化率","detail":"用转化数除以会话数再乘 100，保留两位小数，最后按转化率排序。"}]$j$,
     $j$["teach.online_shoppers"]$j$,
     $q$SELECT visitor_type,
       COUNT(*) AS sessions
FROM online_shoppers
GROUP BY visitor_type
ORDER BY sessions DESC;$q$,
     $q$SELECT visitor_type,
       COUNT(*) AS sessions,
       COUNT(*) FILTER (WHERE revenue) AS conversions,
       ROUND(COUNT(*) FILTER (WHERE revenue) * 100.0 / COUNT(*), 2) AS conversion_rate
FROM online_shoppers
GROUP BY visitor_type
ORDER BY conversion_rate DESC, visitor_type;$q$,
     $pol$ {"requireSelect":true,"forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge","call"],"disallowSelectStar":true,"requiredPatterns":["\\bcount\\s*\\("],"requiredColumns":["visitor_type","sessions","conversions","conversion_rate"]}$pol$::jsonb,
     $j$[{"key":"rows","name":"结果行数","weight":40,"passed":true,"detail":"每种访客类型一行，与标准答案一致"},{"key":"metrics","name":"转化口径","weight":40,"passed":true,"detail":"转化数与转化率均基于 revenue 为 true 的会话"},{"key":"style","name":"聚合规范","weight":20,"passed":true,"detail":"按访客类型聚合，百分比保留两位小数"}]$j$,
     70, 15, 'published', 1, 5,
     $j$["数据分析","真实数据"]$j$,
     $j$[{"title":"FILTER 聚合","detail":"COUNT(*) FILTER (WHERE revenue) 只统计下单会话，与 CASE WHEN 等价但更直观。"},{"title":"百分比口径","detail":"分母是该访客类型的全部会话；先聚合再相除，避免逐行除法引入误差。"}]$j$,
     $j$["teach.online_shoppers@1"]$j$)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learning.lab_skill (lab_code, skill_code, weight) VALUES
    ('da-l2-05', 'da-funnel', 1.0)
ON CONFLICT (lab_code, skill_code) DO NOTHING;
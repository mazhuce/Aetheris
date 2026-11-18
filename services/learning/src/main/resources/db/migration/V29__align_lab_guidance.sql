-- 内容一致性修订（第二批）：steps/scenario/knowledge 仍引用与标准答案冲突的旧口径。
-- de-l1-01 的 pay_amount 列并不存在；dg-l1-02 仍按四指标描述；dg-l3-02 仍要求级别清单。
UPDATE learning.lab
   SET scenario = '你是刚入职的数据开发。订单中台把原始订单流水同步进来（教学样本表 orders_raw，生产对应 ods_orders_raw，约 3.2 亿行，按天分区，含逻辑删除、空用户、负金额等脏数据）。报表组要一份"当天订单的订单号、用户ID、金额"清单，明确说不要多余列——大表上 SELECT * 会把扫描和传输成本放大好几倍。',
       steps = $j$[{"title":"看表结构","detail":"打开 orders_raw，确认列名、类型与分区字段 dt。"},{"title":"改写列裁剪","detail":"把 SELECT * 换成明确的三列：order_id、user_id，并用 quantity * unit_price AS amount 计算金额。"},{"title":"核对结果","detail":"确认输出列名与行数，检查金额列没有科学计数法或精度丢失。"},{"title":"提交判分","detail":"提交 SQL，服务端在只读事务中执行并比对标准结果。"}]$j$,
       knowledge = $j$[{"title":"列裁剪（Projection Pruning）","detail":"只 SELECT 需要的列。列式存储下扫描的列数直接决定耗时与 IO，SELECT * 在宽表上代价极高；列裁剪也是引擎做分区裁剪之外最有效的提速手段。"},{"title":"表达式的别名","detail":"quantity * unit_price AS amount 给计算列命名；别名可被 ORDER BY 引用，但同一层 WHERE 不能引用 SELECT 别名——逻辑执行顺序是 FROM → WHERE → GROUP BY → SELECT → ORDER BY。"}]$j$,
       updated_at = now()
 WHERE code = 'de-l1-01';

UPDATE learning.lab
   SET scenario = 'ods_users 中手机号是敏感字段且要求非空可关联；city 是枚举字段，上游新增了两个未登记城市。质量规则本轮先看两项：mobile 空值率与 city 合法率，用一次扫描算完。',
       steps = $j$[{"title":"一次扫描多指标","detail":"用 AVG(CASE WHEN ...) 组合，避免每列一个查询。"},{"title":"枚举合法率","detail":"合法城市清单用 IN 列表；注意 NOT IN 遇到 NULL 的坑。"},{"title":"核对口径","detail":"空值率与合法率都保留四位小数，分子分母口径一致。"},{"title":"提交判分","detail":"两个比率与预期一致后提交。"}]$j$,
       knowledge = $j$[{"title":"NOT IN 与 NULL","detail":"col NOT IN (1,2,NULL) 永远返回空集——ANY 与 NULL 比较结果是 UNKNOWN。要么保证列表无 NULL，要么改用 LEFT JOIN + IS NULL 的反连接写法。"},{"title":"枚举漂移","detail":"上游新增未登记城市会让合法率下降；质量层要能区分是数据出错还是规则该更新。"}]$j$,
       updated_at = now()
 WHERE code = 'dg-l1-02';

UPDATE learning.lab
   SET steps = $j$[{"title":"识别敏感字段","detail":"对照分级标准确认 mobile 为 L3，姓名与地址为 L2。"},{"title":"学习脱敏写法","detail":"CONCAT(LEFT(mobile,3), '****', RIGHT(mobile,4))；或平台提供的 mask 函数。"},{"title":"改写查询","detail":"输出打码后的手机号，其余字段保持原样，只保留杭州用户。"},{"title":"提交判分","detail":"打码格式与过滤条件正确后提交。"}]$j$,
       updated_at = now()
 WHERE code = 'dg-l3-02';
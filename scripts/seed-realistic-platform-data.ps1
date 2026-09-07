param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = 'liuyaning',
    [long]$ReadDataSourceId = 5,
    [long]$WriteDataSourceId = 6,
    [long]$PostgresDataSourceId = 1
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-AetherisApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body,
        [int]$TimeoutSec = 120
    )

    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        TimeoutSec = $TimeoutSec
        Headers = @{ Authorization = "Bearer $script:AccessToken" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    try {
        Invoke-RestMethod @parameters
    } catch {
        $responseBody = $null
        if ($_.Exception.Response) {
            try {
                $responseBody = $_.Exception.Response.GetResponseStream()
                if ($responseBody) {
                    $reader = New-Object System.IO.StreamReader($responseBody)
                    $responseText = $reader.ReadToEnd()
                    $reader.Dispose()
                }
            } catch {
                $responseText = $null
            } finally {
                if ($responseBody) { $responseBody.Dispose() }
            }
        }
        $suffix = if ([string]::IsNullOrWhiteSpace($responseText)) { '' } else { "：$responseText" }
        throw "Aetheris API $Method $Path 失败$($_.Exception.Response.StatusCode)$suffix"
    }
}

function Get-RequiredDataSource {
    param(
        [Parameter(Mandatory)][long]$Id,
        [Parameter(Mandatory)][string]$ExpectedType,
        [Parameter(Mandatory)][string]$Purpose
    )
    $source = Invoke-AetherisApi -Method GET -Path "/api/metadata/datasources/$Id"
    if ($null -eq $source) {
        throw "缺少$Purpose数据源：ID $Id"
    }
    if ($source.type -ne $ExpectedType) {
        throw "$Purpose 数据源 ID $Id 类型应为 $ExpectedType，实际为 $($source.type)"
    }
    $source
}

function Test-DataSource {
    param([Parameter(Mandatory)][long]$Id, [Parameter(Mandatory)][string]$Name)
    $result = Invoke-AetherisApi -Method POST -Path "/api/metadata/datasources/$Id/test"
    if (-not $result.success) {
        throw "数据源连接探测失败：$Name"
    }
    Write-Host "PASS 数据源连接：$Name（$($result.latencyMs) ms）"
}

function Save-WorkflowTask {
    param([Parameter(Mandatory)][hashtable]$Definition)

    $existing = $null
    foreach ($candidate in @($script:WorkflowTasks)) {
        if ($candidate -is [System.Array]) {
            foreach ($nested in $candidate) {
                if ($nested.task -and $nested.task.name -eq $Definition.name) { $existing = $nested; break }
            }
        } elseif ($candidate.task -and $candidate.task.name -eq $Definition.name) {
            $existing = $candidate
        }
        if ($null -ne $existing) { break }
    }
    $body = @{
        name = $Definition.name
        datasourceId = $script:WriteSource.id
        datasourceName = $script:WriteSource.name
        taskSql = $Definition.sql
        targetTable = $Definition.target
        timeoutSeconds = $Definition.timeout
        maxRetries = 1
        status = 'enabled'
        cronExpression = $null
        description = $Definition.description
    }
    if ($null -eq $existing) {
        $saved = Invoke-AetherisApi -Method POST -Path '/api/workflow/tasks' -Body $body
        $script:WorkflowTasks += $saved
        Write-Host "CREATED 工作流：$($Definition.name)"
        return $saved.task
    }

    $saved = Invoke-AetherisApi -Method PUT -Path "/api/workflow/tasks/$([long]$existing.task.id)" -Body $body
    Write-Host "UPDATED 工作流：$($Definition.name)"
    $saved.task
}

function Enable-WorkflowSchedule {
    param([Parameter(Mandatory)][object]$Task, [Parameter(Mandatory)][hashtable]$Definition)
    $body = @{
        name = $Definition.name
        datasourceId = $script:WriteSource.id
        datasourceName = $script:WriteSource.name
        taskSql = $Definition.sql
        targetTable = $Definition.target
        timeoutSeconds = $Definition.timeout
        maxRetries = 1
        status = 'enabled'
        cronExpression = $Definition.cron
        description = $Definition.description
    }
    Invoke-AetherisApi -Method PUT -Path "/api/workflow/tasks/$($Task.id)" -Body $body | Out-Null
    Write-Host "SCHEDULED 工作流：$($Definition.name) [$($Definition.cron)]"
}

function Save-QualityRule {
    param([Parameter(Mandatory)][hashtable]$Definition)

    $existing = $null
    foreach ($candidate in @($script:QualityRules)) {
        foreach ($rule in @($candidate)) {
            if ($rule.name -eq $Definition.name -or ($Definition.replaces -and $rule.name -eq $Definition.replaces)) {
                $existing = $rule
                break
            }
        }
        if ($null -ne $existing) { break }
    }
    $body = @{
        name = $Definition.name
        ruleType = $Definition.type
        datasourceId = $script:ReadSource.id
        targetTable = $Definition.target
        expression = $Definition.expression
        threshold = 0
        severity = $Definition.severity
        status = 'enabled'
        cronExpression = $Definition.cron
        description = $Definition.description
    }
    if ($null -eq $existing) {
        $saved = Invoke-AetherisApi -Method POST -Path '/api/quality/rules' -Body $body
        $script:QualityRules += $saved
        Write-Host "CREATED 质量规则：$($Definition.name)"
        return $saved
    }
    $saved = Invoke-AetherisApi -Method PUT -Path "/api/quality/rules/$($existing.id)" -Body $body
    Write-Host "UPDATED 质量规则：$($Definition.name)"
    $saved
}

function Save-IngestionTask {
    param([Parameter(Mandatory)][hashtable]$Definition)

    $existing = $null
    foreach ($candidate in @($script:IngestionTasks)) {
        foreach ($task in @($candidate)) {
            if ($task.name -eq $Definition.name -or ($Definition.replaces -and $task.name -eq $Definition.replaces)) {
                $existing = $task
                break
            }
        }
        if ($null -ne $existing) { break }
    }
    $body = @{
        name = $Definition.name
        sourceType = 'jdbc'
        sourceDatasourceId = $script:ReadSource.id
        targetDatasourceId = $script:PostgresSource.id
        sourceSql = $Definition.sql
        targetTable = $Definition.target
        writeMode = 'replace'
        batchSize = 500
        scheduleCron = $Definition.cron
        sourceConfig = '{}'
        status = 'enabled'
        description = $Definition.description
    }
    if ($null -eq $existing) {
        $saved = Invoke-AetherisApi -Method POST -Path '/api/ingestion/tasks' -Body $body
        $script:IngestionTasks += $saved
        Write-Host "CREATED 接入任务：$($Definition.name)"
        return $saved
    }
    $saved = Invoke-AetherisApi -Method PUT -Path "/api/ingestion/tasks/$($existing.id)" -Body $body
    Write-Host "UPDATED 接入任务：$($Definition.name)"
    $saved
}

$securePassword = Read-Host '请输入本机 Aetheris 管理员密码' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $loginBody = @{ username = $Username; password = $plainPassword } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $loginBody -TimeoutSec 30
} finally {
    $plainPassword = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
if ($login.role -ne 'admin' -or [string]::IsNullOrWhiteSpace($login.token)) {
    throw '需要管理员身份才能填充任务数据'
}
$script:AccessToken = $login.token

$script:ReadSource = Get-RequiredDataSource -Id $ReadDataSourceId -ExpectedType 'spark' -Purpose '只读 Spark'
$script:WriteSource = Get-RequiredDataSource -Id $WriteDataSourceId -ExpectedType 'spark' -Purpose '写入 Spark'
$script:PostgresSource = Get-RequiredDataSource -Id $PostgresDataSourceId -ExpectedType 'postgresql' -Purpose '平台 PostgreSQL'

Test-DataSource -Id $script:ReadSource.id -Name $script:ReadSource.name
Test-DataSource -Id $script:WriteSource.id -Name $script:WriteSource.name
Test-DataSource -Id $script:PostgresSource.id -Name $script:PostgresSource.name

$preflight = Invoke-AetherisApi -Method POST -Path "/api/metadata/datasources/$($script:ReadSource.id)/query" -Body @{
    sql = 'SELECT dt, COUNT(*) AS row_count FROM ods.ods_order GROUP BY dt ORDER BY dt DESC LIMIT 1'
}
if ($preflight.rowCount -ne 1 -or [long]$preflight.rows[0][1] -le 0) {
    throw 'ODS 没有可用的订单分区，停止创建调度'
}
$businessDate = [string]$preflight.rows[0][0]
Write-Host "PASS 真实分区：ods.ods_order dt=$businessDate，共 $($preflight.rows[0][1]) 行"

$definitions = @(
    @{
        name = '交易-DWD-订单明细日加工'; target = 'dwd.dwd_order'; cron = '0 30 1 * * ?'; timeout = 3600
        description = '每日清洗前一日 ODS 订单：去重、过滤逻辑删除、统一状态与类型，覆盖对应 DWD 分区。'
        sql = @'
INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt='${bizDate}')
SELECT order_id, CAST(user_id AS BIGINT), status_int,
       CASE status_int WHEN 1 THEN '待付款' WHEN 2 THEN '已付款' WHEN 3 THEN '已发货' WHEN 4 THEN '已完成' WHEN 5 THEN '已取消' WHEN 6 THEN '已退款' ELSE '未知' END,
       CAST(pay_amount AS DECIMAL(12,2)), pay_type, CAST(order_time AS TIMESTAMP),
       HOUR(CAST(order_time AS TIMESTAMP)), province,
       COALESCE(status_int IN (2,3,4), false), COALESCE(status_int = 4, false), COALESCE(status_int = 6, false)
FROM (
    SELECT order_id, user_id, pay_amount, pay_type, order_time, province,
           CASE WHEN CAST(order_status AS INT) BETWEEN 1 AND 6 THEN CAST(order_status AS INT) ELSE NULL END AS status_int,
           ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY order_time) AS rn
    FROM ods.ods_order
    WHERE dt='${bizDate}' AND is_deleted='0'
) source_orders
WHERE rn=1
'@
    },
    @{
        name = '交易-DWD-订单商品明细日加工'; target = 'dwd.dwd_order_item'; cron = '0 55 1 * * ?'; timeout = 3600
        description = '每日关联订单和商品维度，清洗数量与优惠金额，覆盖前一日订单商品明细分区。'
        sql = @'
INSERT OVERWRITE TABLE dwd.dwd_order_item PARTITION (dt='${bizDate}')
SELECT i.item_id, i.order_id, o.user_id, CAST(i.product_id AS BIGINT), p.category_id,
       p.category_name, p.brand,
       CASE WHEN CAST(i.quantity AS INT) > 0 THEN CAST(i.quantity AS INT) ELSE NULL END,
       CAST(i.item_amount AS DECIMAL(12,2)), COALESCE(CAST(i.discount AS DECIMAL(12,2)), 0),
       CAST(i.item_amount AS DECIMAL(12,2)) - COALESCE(CAST(i.discount AS DECIMAL(12,2)), 0), o.province
FROM ods.ods_order_item i
JOIN dwd.dwd_order o ON i.order_id=o.order_id AND o.dt='${bizDate}'
LEFT JOIN dwd.dwd_product p ON CAST(i.product_id AS BIGINT)=p.product_id
WHERE i.dt='${bizDate}'
'@
    },
    @{
        name = '交易-DWS-订单主题日汇总'; target = 'dws.dws_order_daily'; cron = '0 10 2 * * ?'; timeout = 1800
        description = '按日汇总订单量、有效订单、GMV、客单价、退款与取消指标，覆盖对应 DWS 分区。'
        sql = @'
INSERT OVERWRITE TABLE dws.dws_order_daily PARTITION (dt='${bizDate}')
SELECT COUNT(*), COUNT(CASE WHEN is_valid_order THEN 1 END),
       COUNT(DISTINCT CASE WHEN is_valid_order THEN user_id END),
       CAST(COALESCE(SUM(CASE WHEN is_valid_order THEN pay_amount END),0) AS DECIMAL(18,2)),
       CASE WHEN COUNT(CASE WHEN is_valid_order THEN 1 END)>0 THEN CAST(SUM(CASE WHEN is_valid_order THEN pay_amount END)/COUNT(CASE WHEN is_valid_order THEN 1 END) AS DECIMAL(12,2)) ELSE NULL END,
       COUNT(CASE WHEN order_status=6 THEN 1 END),
       CAST(COALESCE(SUM(CASE WHEN order_status=6 THEN pay_amount END),0) AS DECIMAL(18,2)),
       COUNT(CASE WHEN order_status=5 THEN 1 END)
FROM dwd.dwd_order
WHERE dt='${bizDate}'
'@
    },
    @{
        name = '销售-DWS-品类主题日汇总'; target = 'dws.dws_category_daily'; cron = '0 25 2 * * ?'; timeout = 1800
        description = '按品类和日期汇总有效订单的销量、销售额、优惠额与件均价，覆盖对应 DWS 分区。'
        sql = @'
INSERT OVERWRITE TABLE dws.dws_category_daily PARTITION (dt='${bizDate}')
SELECT i.category_id, COALESCE(i.category_name,'未分类'), COUNT(DISTINCT i.order_id),
       COALESCE(SUM(i.quantity),0), CAST(COALESCE(SUM(i.net_amount),0) AS DECIMAL(18,2)),
       CAST(COALESCE(SUM(i.discount),0) AS DECIMAL(18,2)),
       CASE WHEN COALESCE(SUM(i.quantity),0)>0 THEN CAST(SUM(i.net_amount)/SUM(i.quantity) AS DECIMAL(12,2)) ELSE NULL END
FROM dwd.dwd_order_item i
JOIN dwd.dwd_order o ON i.order_id=o.order_id AND i.dt=o.dt AND o.is_valid_order
WHERE i.dt='${bizDate}'
GROUP BY i.category_id, COALESCE(i.category_name,'未分类')
'@
    },
    @{
        name = '交易-ADS-订单指标日刷新'; target = 'ads.ads_order_stats'; cron = '0 40 2 * * ?'; timeout = 1800
        description = '基于完整 DWS 日汇总刷新订单核心指标，包括 GMV、客单价、退款率、环比和七日均值。'
        sql = @'
INSERT OVERWRITE TABLE ads.ads_order_stats
SELECT d.stat_date, dd.month_key, dd.is_weekend, d.order_cnt, d.valid_order_cnt, d.paid_user_cnt,
       d.gmv, d.avg_order_amount,
       CASE WHEN d.order_cnt>0 THEN CAST(d.refund_order_cnt/d.order_cnt AS DECIMAL(6,4)) ELSE NULL END,
       CASE WHEN d.order_cnt>0 THEN CAST(d.cancel_order_cnt/d.order_cnt AS DECIMAL(6,4)) ELSE NULL END,
       CASE WHEN d.prev_gmv IS NOT NULL AND d.prev_gmv>0 THEN CAST((d.gmv-d.prev_gmv)/d.prev_gmv AS DECIMAL(10,4)) ELSE NULL END,
       CAST(d.gmv_7d_avg AS DECIMAL(18,2))
FROM (
    SELECT dt AS stat_date, order_cnt, valid_order_cnt, paid_user_cnt, gmv, avg_order_amount,
           refund_order_cnt, cancel_order_cnt, LAG(gmv) OVER (ORDER BY dt) AS prev_gmv,
           AVG(gmv) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS gmv_7d_avg
    FROM dws.dws_order_daily
) d
LEFT JOIN dim.dim_date dd ON d.stat_date=dd.date_key
'@
    },
    @{
        name = '销售-ADS-品类指标日刷新'; target = 'ads.ads_category_stats'; cron = '0 55 2 * * ?'; timeout = 1800
        description = '基于完整 DWS 品类日汇总刷新月度销量、销售额、排名和销售额占比。'
        sql = @'
INSERT OVERWRITE TABLE ads.ads_category_stats
SELECT monthly.month_key, monthly.category_id, monthly.category_name, monthly.order_cnt, monthly.sale_qty,
       monthly.sale_amount, monthly.discount_amount,
       CASE WHEN monthly.sale_qty>0 THEN CAST(monthly.sale_amount/monthly.sale_qty AS DECIMAL(12,2)) ELSE NULL END,
       CAST(RANK() OVER (PARTITION BY monthly.month_key ORDER BY monthly.sale_amount DESC) AS INT),
       CASE WHEN SUM(monthly.sale_amount) OVER (PARTITION BY monthly.month_key)>0 THEN CAST(monthly.sale_amount/SUM(monthly.sale_amount) OVER (PARTITION BY monthly.month_key) AS DECIMAL(6,4)) ELSE NULL END
FROM (
    SELECT SUBSTR(dt,1,7) AS month_key, category_id, category_name, SUM(order_cnt) AS order_cnt,
           SUM(sale_qty) AS sale_qty, CAST(SUM(sale_amount) AS DECIMAL(18,2)) AS sale_amount,
           CAST(SUM(discount_amount) AS DECIMAL(18,2)) AS discount_amount
    FROM dws.dws_category_daily
    GROUP BY SUBSTR(dt,1,7), category_id, category_name
) monthly
'@
    }
)

$script:WorkflowTasks = @(Invoke-AetherisApi -Method GET -Path '/api/workflow/tasks')
$savedTasks = [ordered]@{}
foreach ($definition in $definitions) {
    $savedTasks[$definition.name] = Save-WorkflowTask -Definition $definition
}

$dependencies = @(
    @('交易-DWD-订单商品明细日加工', '交易-DWD-订单明细日加工'),
    @('交易-DWS-订单主题日汇总', '交易-DWD-订单明细日加工'),
    @('销售-DWS-品类主题日汇总', '交易-DWD-订单商品明细日加工'),
    @('交易-ADS-订单指标日刷新', '交易-DWS-订单主题日汇总'),
    @('销售-ADS-品类指标日刷新', '销售-DWS-品类主题日汇总')
)
foreach ($edge in $dependencies) {
    $downstream = $savedTasks[$edge[0]]
    $upstream = $savedTasks[$edge[1]]
    Invoke-AetherisApi -Method POST -Path "/api/workflow/tasks/$($downstream.id)/dependencies?upstreamTaskId=$($upstream.id)" | Out-Null
    Write-Host "LINKED $($edge[1]) -> $($edge[0])"
}

foreach ($definition in $definitions) {
    $task = $savedTasks[$definition.name]
    $instance = Invoke-AetherisApi -Method POST -Path "/api/workflow/tasks/$($task.id)/run" -Body @{ bizDate = $businessDate } -TimeoutSec 3900
    if ($instance.status -ne 'success') {
        throw "工作流验证失败：$($definition.name)，状态 $($instance.status)"
    }
    Write-Host "PASS 工作流：$($definition.name)（$($instance.durationMs) ms）"
}
foreach ($definition in $definitions) {
    Enable-WorkflowSchedule -Task $savedTasks[$definition.name] -Definition $definition
}

$temporaryTasks = @()
$workflowResponse = Invoke-AetherisApi -Method GET -Path '/api/workflow/tasks'
foreach ($candidate in @($workflowResponse)) {
    foreach ($taskView in @($candidate)) {
        if ($taskView.task -and $taskView.task.name -eq '123' -and $taskView.task.targetTable -eq '123123') {
            $temporaryTasks += $taskView
        }
    }
}
foreach ($temporary in $temporaryTasks) {
    $temporaryId = [long]$temporary.task.id
    Invoke-AetherisApi -Method DELETE -Path "/api/workflow/tasks/$temporaryId" | Out-Null
    Write-Host "REMOVED 临时工作流：123（ID $temporaryId）"
}

$qualityDefinitions = @(
    @{ name='交易-DWD-订单主键非空'; replaces='订单表空值检查'; type='not_null'; target='dwd.dwd_order'; expression='order_id IS NOT NULL'; severity='high'; cron='0 10 3 * * ?'; description='DWD 订单主键必须非空，违规阈值为 0。' },
    @{ name='交易-DWD-订单主键唯一'; replaces='用户表重复检查'; type='duplicate'; target='dwd.dwd_order'; expression='order_id'; severity='high'; cron='0 15 3 * * ?'; description='DWD 订单按 order_id 全局去重，重复阈值为 0。' },
    @{ name='交易-DWS-GMV非负'; type='range'; target='dws.dws_order_daily'; expression='gmv >= 0'; severity='high'; cron='0 20 3 * * ?'; description='订单日汇总 GMV 不允许出现负值。' },
    @{ name='交易-ADS-指标日期非空'; type='not_null'; target='ads.ads_order_stats'; expression='stat_date IS NOT NULL'; severity='medium'; cron='0 25 3 * * ?'; description='订单指标日报的统计日期必须完整。' }
)
$script:QualityRules = @(Invoke-AetherisApi -Method GET -Path '/api/quality/rules')
foreach ($definition in $qualityDefinitions) {
    $rule = Save-QualityRule -Definition $definition
    $result = Invoke-AetherisApi -Method POST -Path "/api/quality/rules/$($rule.id)/run" -TimeoutSec 600
    if ($result.status -ne 'passed') {
        throw "质量规则验证未通过：$($definition.name)，状态 $($result.status)"
    }
    Write-Host "PASS 质量规则：$($definition.name)，违规 $($result.violationCount)"
}

$landingSql = @'
CREATE SCHEMA IF NOT EXISTS landing;
CREATE TABLE IF NOT EXISTS landing.ads_order_stats_snapshot (
    stat_date text, month_key text, is_weekend boolean, order_cnt bigint, valid_order_cnt bigint,
    paid_user_cnt bigint, gmv numeric(18,2), avg_order_amount numeric(12,2), refund_rate numeric(6,4),
    cancel_rate numeric(6,4), gmv_mom numeric(10,4), gmv_7d_avg numeric(18,2)
);
CREATE TABLE IF NOT EXISTS landing.ads_category_stats_snapshot (
    month_key text, category_id integer, category_name text, order_cnt bigint, sale_qty bigint,
    sale_amount numeric(18,2), discount_amount numeric(18,2), avg_price numeric(12,2),
    amount_rank integer, amount_share numeric(6,4)
);
'@
& docker exec aetheris-postgres-1 psql -U aetheris -d aetheris -v ON_ERROR_STOP=1 -c $landingSql | Out-Null
if ($LASTEXITCODE -ne 0) { throw '创建 PostgreSQL landing 表失败' }
Write-Host 'PASS PostgreSQL landing 目标表'

$ingestionDefinitions = @(
    @{ name='交易-ADS-订单指标同步至PostgreSQL'; replaces='订单数据每日同步'; target='landing.ads_order_stats_snapshot'; cron='0 20 4 * * ?'; sql='SELECT stat_date, month_key, is_weekend, order_cnt, valid_order_cnt, paid_user_cnt, gmv, avg_order_amount, refund_rate, cancel_rate, gmv_mom, gmv_7d_avg FROM ads.ads_order_stats'; description='每日将 Spark ADS 订单核心指标全量同步到 PostgreSQL landing 区，供服务侧低延迟读取。' },
    @{ name='销售-ADS-品类指标同步至PostgreSQL'; replaces='用户日志实时接入'; target='landing.ads_category_stats_snapshot'; cron='0 30 4 * * ?'; sql='SELECT month_key, category_id, category_name, order_cnt, sale_qty, sale_amount, discount_amount, avg_price, amount_rank, amount_share FROM ads.ads_category_stats'; description='每日将 Spark ADS 品类月度指标全量同步到 PostgreSQL landing 区，供报表与接口消费。' }
)
$script:IngestionTasks = @(Invoke-AetherisApi -Method GET -Path '/api/ingestion/tasks')
foreach ($definition in $ingestionDefinitions) {
    $task = Save-IngestionTask -Definition $definition
    $run = Invoke-AetherisApi -Method POST -Path "/api/ingestion/tasks/$($task.id)/run" -TimeoutSec 1800
    if ($run.status -ne 'success') {
        throw "接入任务验证失败：$($definition.name)，状态 $($run.status)"
    }
    Write-Host "PASS 接入任务：$($definition.name)，写入 $($run.rowsWritten) 行"
}

$domainSql = @'
UPDATE metadata.metadata_table SET business_domain_id=4, domain='用户域', updated_at=now()
WHERE schema_name IN ('ods','dwd','dws','ads') AND table_name IN ('ods_user','dwd_user','dws_user_daily','ads_user_profile');
UPDATE metadata.metadata_table SET business_domain_id=5, domain='交易域', updated_at=now()
WHERE schema_name IN ('ods','dwd','dws','ads') AND table_name IN ('ods_order','ods_order_item','dwd_order','dwd_order_item','dws_order_daily','ads_order_stats');
UPDATE metadata.metadata_table SET business_domain_id=6, domain='销售域', updated_at=now()
WHERE schema_name IN ('ods','dwd','dws','ads','dim') AND table_name IN ('ods_product','dwd_product','dws_category_daily','ads_category_stats','dim_category');
'@
& docker exec aetheris-postgres-1 psql -U aetheris -d aetheris -v ON_ERROR_STOP=1 -c $domainSql | Out-Null
if ($LASTEXITCODE -ne 0) { throw '更新元数据业务域失败' }
Write-Host 'PASS 元数据业务域：用户域 / 交易域 / 销售域'

$summary = [ordered]@{
    businessDate = $businessDate
    workflowTasks = $definitions.name
    workflowDependencies = $dependencies.Count
    qualityRules = $qualityDefinitions.name
    ingestionTasks = $ingestionDefinitions.name
    removedTemporaryTask = ($temporaryTasks.Count -gt 0)
}
$summary | ConvertTo-Json -Depth 5

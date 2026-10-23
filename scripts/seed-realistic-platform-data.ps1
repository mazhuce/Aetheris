param(
    [string]$BaseUrl = 'http://localhost:8080',
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [ValidateScript({ -not [string]::IsNullOrWhiteSpace($_) })]
    [string]$Username,
    [ValidateRange(1, [long]::MaxValue)]
    [long]$ReadDataSourceId = 5,
    [ValidateRange(1, [long]::MaxValue)]
    [long]$WriteDataSourceId = 6,
    [Parameter(Mandatory)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$PostgresDataSourceId,
    [switch]$ApproveDefinitions,
    [switch]$RunTasks,
    [switch]$BindSchedules
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Default: save definitions/dependencies only. Approval, execution and schedule binding
# require explicit switches. Legacy database SQL is returned for review, never executed.
if (($RunTasks -or $BindSchedules) -and -not $ApproveDefinitions) {
    throw '-RunTasks / -BindSchedules 必须显式同时指定 -ApproveDefinitions'
}
$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$baseUri) -or
    $baseUri.Scheme -notin @('http', 'https') -or $baseUri.UserInfo -or $baseUri.Query -or $baseUri.Fragment -or $baseUri.AbsolutePath -ne '/') {
    throw 'BaseUrl 必须是无路径、查询、片段或凭据的 HTTP(S) origin'
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:SessionClock = [Diagnostics.Stopwatch]::StartNew()
$script:ExpiresAtMs = 0.0
$script:Resources = New-Object Collections.Generic.List[object]
$script:Approved = New-Object Collections.Generic.List[object]
$script:Executed = New-Object Collections.Generic.List[object]
$script:BoundSchedules = New-Object Collections.Generic.List[object]

function Test-Integer {
    param([object]$Value, [long]$Minimum = 1)
    ($Value -is [int] -or $Value -is [long] -or $Value -is [short] -or $Value -is [byte]) -and $Value -ge $Minimum
}

function Invoke-WireRequest {
    param([string]$Method, [string]$Path, [object]$Body, [int]$TimeoutSec = 120)
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; TimeoutSec = $TimeoutSec;
        WebSession = $script:Session; MaximumRedirection = 0 }
    $script:LastAttempt = @{ method = $Method; path = $Path }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    try { Invoke-RestMethod @parameters } catch {
        $status = 'unknown'
        if ($_.Exception.Response) { $status = [string]$_.Exception.Response.StatusCode }
        # Do not echo response bodies, cookies, passwords or proofs. Never retry a mutation:
        # a lost response may follow a committed operation, including refresh rotation.
        throw "Aetheris API $Method $Path 失败（$status）；未重试，请核查服务状态后人工处理"
    }
}

function Set-SessionExpiry {
    param([object]$Login)
    if ($null -eq $Login -or -not [string]::Equals($Login.username, $Username, [StringComparison]::Ordinal) -or $Login.role -cne 'admin' -or
        -not (Test-Integer $Login.expiresIn) -or $Login.expiresIn -gt 86400000) {
        throw '登录/刷新响应主体或有效期不合法'
    }
    if ($null -eq $script:Session.Cookies.GetCookies($baseUri)['aetheris_access']) {
        throw '登录/刷新未建立 access Cookie'
    }
    $script:ExpiresAtMs = $script:SessionClock.Elapsed.TotalMilliseconds + $Login.expiresIn
}

function Invoke-AetherisApi {
    param([Parameter(Mandatory)][string]$Method, [Parameter(Mandatory)][string]$Path,
        [object]$Body, [int]$TimeoutSec = 120)
    if ($script:SessionClock.Elapsed.TotalMilliseconds + 30000 -ge $script:ExpiresAtMs) {
        $refreshed = Invoke-WireRequest -Method POST -Path '/api/auth/refresh' -TimeoutSec 30
        Set-SessionExpiry $refreshed
    }
    Invoke-WireRequest -Method $Method -Path $Path -Body $Body -TimeoutSec $TimeoutSec
}

function Get-CheckedEntity {
    param([object]$Response, [bool]$Workflow, [string]$Name)
    $entity = if ($Workflow) { $Response.task } else { $Response }
    if ($null -eq $entity -or -not (Test-Integer $entity.id) -or [string]::IsNullOrWhiteSpace($Name) -or
        -not [string]::Equals($entity.name, $Name, [StringComparison]::Ordinal)) {
        throw '保存/详情响应必须包含正确名称与正整数 ID；workflow 必须是 TaskView.task'
    }
    $entity
}

function Test-DefinitionEqual {
    param([object]$Entity, [hashtable]$Expected)
    foreach ($key in $Expected.Keys) {
        $actualJson = ConvertTo-Json -InputObject $Entity.$key -Compress -Depth 10
        $expectedJson = ConvertTo-Json -InputObject $Expected[$key] -Compress -Depth 10
        if (-not [string]::Equals($actualJson, $expectedJson, [StringComparison]::Ordinal)) { return $false }
    }
    return $true
}

function Save-Definition {
    param([string]$Collection, [object]$Existing, [hashtable]$Body, [string]$CronField,
        [string]$Cron, [bool]$Workflow = $false)
    # Do not silently clear an existing schedule or enable a disabled resource.
    $Body[$CronField] = if ($BindSchedules) { $Cron } elseif ($null -ne $Existing) { $Existing.$CronField } else { $null }
    $Body.status = if ($RunTasks -or $BindSchedules -or $null -eq $Existing) { 'enabled' } else { $Existing.status }
    if ($null -eq $Existing) {
        $response = Invoke-AetherisApi -Method POST -Path $Collection -Body $Body
        $entity = Get-CheckedEntity $response $Workflow $Body.name
        Write-Host "CREATED 定义：$($Body.name)"
    } else {
        if (-not (Test-Integer $Existing.id)) { throw '既有资源 ID 不合法' }
        if (Test-DefinitionEqual $Existing $Body) {
            $entity = $Existing
            Write-Host "UNCHANGED 定义：$($Body.name)"
        } else {
            $response = Invoke-AetherisApi -Method PUT -Path "$Collection/$($Existing.id)" -Body $Body
            $entity = Get-CheckedEntity $response $Workflow $Body.name
            if ($entity.id -ne $Existing.id) { throw '保存响应 ID 与既有资源不符' }
            Write-Host "UPDATED 定义：$($Body.name)"
        }
    }
    $script:Resources.Add(@{ Path = "$Collection/$($entity.id)"; Id = $entity.id; Name = $Body.name;
        Expected = $Body; Workflow = $Workflow; State = $null })
    $entity
}

function Get-ApprovalState {
    param([string]$Path)
    $s = Invoke-AetherisApi -Method GET -Path "$Path/approval"
    if ($null -eq $s -or -not (Test-Integer $s.executionRevision) -or
        -not (Test-Integer $s.scheduleGeneration) -or -not (Test-Integer $s.bindingGeneration 0) -or
        $s.definitionSha256 -isnot [string] -or $s.definitionSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $s.approved -isnot [bool] -or $s.cronBound -isnot [bool]) { throw '批准状态响应不合法' }
    $s
}

function Assert-SameVersion {
    param([object]$Before, [object]$After)
    if ($Before.executionRevision -ne $After.executionRevision -or
        $Before.definitionSha256 -cne $After.definitionSha256 -or
        $Before.bindingGeneration -ne $After.bindingGeneration -or
        $Before.scheduleGeneration -ne $After.scheduleGeneration) { throw '定义或调度版本发生变化，停止自动操作，请重新审阅' }
}

function Approve-SavedDefinition {
    param([object]$Resource)
    $before = Get-ApprovalState $Resource.Path
    $detail = Get-CheckedEntity (Invoke-AetherisApi -Method GET -Path $Resource.Path) $Resource.Workflow $Resource.Name
    if ($detail.id -ne $Resource.Id -or -not (Test-DefinitionEqual $detail $Resource.Expected)) {
        throw "当前定义与本次审阅内容不一致：$($Resource.Name)"
    }
    if ($Resource.Workflow) {
        $expectedIds = @($dependencies | Where-Object { [string]::Equals($_[0], $Resource.Name, [StringComparison]::Ordinal) } | ForEach-Object { $savedTasks[$_[1]].id } | Sort-Object)
        $actualIds = @(
            foreach ($edge in @(Invoke-AetherisApi -Method GET -Path "$($Resource.Path)/dependencies")) {
                if (-not (Test-Integer $edge.taskId) -or $edge.taskId -ne $Resource.Id -or -not (Test-Integer $edge.upstreamTaskId)) {
                    throw '依赖响应不合法，停止批准'
                }
                $edge.upstreamTaskId
            }
        )
        if (($expectedIds -join ',') -cne (($actualIds | Sort-Object) -join ',')) {
            throw '当前依赖与本次五条审阅依赖不一致，停止批准；不会删除未知依赖'
        }
    }
    $checked = Get-ApprovalState $Resource.Path
    Assert-SameVersion $before $checked
    $approved = Invoke-AetherisApi -Method POST -Path "$($Resource.Path)/approve" -Body @{ expectedRevision = $checked.executionRevision }
    Assert-SameVersion $checked $approved
    if ($approved.approved -isnot [bool] -or -not $approved.approved) { throw '服务未确认批准' }
    $Resource.State = $approved
    $script:Approved.Add($Resource.Path)
    Write-Host "APPROVED 定义：$($Resource.Name)"
}

function Get-RequiredDataSource {
    param(
        [Parameter(Mandatory)][long]$Id,
        [Parameter(Mandatory)][string]$ExpectedType,
        [Parameter(Mandatory)][string]$Purpose,
        [switch]$RequireWrite
    )
    $source = Invoke-AetherisApi -Method GET -Path "/api/metadata/datasources/$Id"
    if ($null -eq $source) {
        throw "缺少$Purpose数据源：ID $Id"
    }
    if ($source.type -ne $ExpectedType) {
        throw "$Purpose 数据源 ID $Id 类型应为 $ExpectedType，实际为 $($source.type)"
    }
    if (-not (Test-Integer $source.id) -or $source.id -ne $Id -or $source.managed -isnot [bool] -or $source.managed -or
        -not [string]::Equals($source.status, 'active', [StringComparison]::Ordinal)) {
        throw "$Purpose 必须是配置状态 active、非平台 managed 的独立业务数据源；空值、未知或停用状态不可用"
    }
    if ($RequireWrite -and ($source.writeConfigured -isnot [bool] -or -not $source.writeConfigured)) {
        throw "$Purpose 缺少独立 WRITE 账号配置"
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
        $checkedTask = Get-CheckedEntity $candidate $true $candidate.task.name
        if ([string]::Equals($checkedTask.name, $Definition.name, [StringComparison]::Ordinal)) {
            if ($null -ne $existing) { throw '同名工作流不唯一，停止保存' }
            $existing = $candidate
        }
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
    Save-Definition -Collection '/api/workflow/tasks' -Existing $existing.task -Body $body -CronField 'cronExpression' -Cron $Definition.cron -Workflow $true
}

function Save-QualityRule {
    param([Parameter(Mandatory)][hashtable]$Definition)

    $existing = $null
    foreach ($candidate in @($script:QualityRules)) {
        foreach ($rule in @($candidate)) {
            if ([string]::Equals($rule.name, $Definition.name, [StringComparison]::Ordinal) -or
                ($Definition.replaces -and [string]::Equals($rule.name, $Definition.replaces, [StringComparison]::Ordinal))) {
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
    Save-Definition -Collection '/api/quality/rules' -Existing $existing -Body $body -CronField 'cronExpression' -Cron $Definition.cron
}

function Save-IngestionTask {
    param([Parameter(Mandatory)][hashtable]$Definition)

    $existing = $null
    foreach ($candidate in @($script:IngestionTasks)) {
        foreach ($task in @($candidate)) {
            if ([string]::Equals($task.name, $Definition.name, [StringComparison]::Ordinal) -or
                ($Definition.replaces -and [string]::Equals($task.name, $Definition.replaces, [StringComparison]::Ordinal))) {
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
    Save-Definition -Collection '/api/ingestion/tasks' -Existing $existing -Body $body -CronField 'scheduleCron' -Cron $Definition.cron
}

try {
$securePassword = Read-Host '请输入本机 Aetheris 管理员密码' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $loginBody = @{ username = $Username; password = $plainPassword }
    $login = Invoke-WireRequest -Method POST -Path '/api/auth/login' -Body $loginBody -TimeoutSec 30
} finally {
    $plainPassword = $null
    $loginBody = $null
    if ($securePassword) { $securePassword.Dispose() }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
Set-SessionExpiry $login
$me = Invoke-AetherisApi -Method GET -Path '/api/auth/me'
if (-not (Test-Integer $me.id) -or -not [string]::Equals($me.username, $Username, [StringComparison]::Ordinal) -or $me.role -cne 'admin' -or $me.status -cne 'active') {
    throw '当前会话必须与显式 Username 一致，且账号为 active 管理员'
}

$script:ReadSource = Get-RequiredDataSource -Id $ReadDataSourceId -ExpectedType 'spark' -Purpose '只读 Spark'
$script:WriteSource = Get-RequiredDataSource -Id $WriteDataSourceId -ExpectedType 'spark' -Purpose '写入 Spark' -RequireWrite
$script:PostgresSource = Get-RequiredDataSource -Id $PostgresDataSourceId -ExpectedType 'postgresql' -Purpose '独立业务 PostgreSQL' -RequireWrite

Test-DataSource -Id $script:ReadSource.id -Name $script:ReadSource.name
Test-DataSource -Id $script:WriteSource.id -Name $script:WriteSource.name
Test-DataSource -Id $script:PostgresSource.id -Name $script:PostgresSource.name

$preflight = Invoke-AetherisApi -Method POST -Path "/api/metadata/datasources/$($script:ReadSource.id)/query" -Body @{
    sql = 'SELECT dt, COUNT(*) AS row_count FROM ods.ods_order GROUP BY dt ORDER BY dt DESC LIMIT 1'
}
$parsedDate = [datetime]::MinValue
if ($preflight.rowCount -ne 1 -or @($preflight.columns).Count -ne 2 -or
    $preflight.columns[0] -cne 'dt' -or $preflight.columns[1] -cne 'row_count' -or
    @($preflight.rows).Count -ne 1 -or @($preflight.rows[0]).Count -ne 2 -or
    -not (Test-Integer $preflight.rows[0][1]) -or $preflight.rows[0][0] -isnot [string] -or
    $preflight.rows[0][0] -cnotmatch '^\d{4}-\d{2}-\d{2}$' -or
    -not [datetime]::TryParseExact($preflight.rows[0][0], 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$parsedDate)) {
    throw 'ODS 没有形状及日期有效的订单分区，停止保存定义'
}
$businessDate = $parsedDate.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
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

$qualityDefinitions = @(
    @{ name='交易-DWD-订单主键非空'; replaces='订单表空值检查'; type='not_null'; target='dwd.dwd_order'; expression='order_id IS NOT NULL'; severity='high'; cron='0 10 3 * * ?'; description='DWD 订单主键必须非空，违规阈值为 0。' },
    @{ name='交易-DWD-订单主键唯一'; replaces='用户表重复检查'; type='duplicate'; target='dwd.dwd_order'; expression='order_id'; severity='high'; cron='0 15 3 * * ?'; description='DWD 订单按 order_id 全局去重，重复阈值为 0。' },
    @{ name='交易-DWS-GMV非负'; type='range'; target='dws.dws_order_daily'; expression='gmv >= 0'; severity='high'; cron='0 20 3 * * ?'; description='订单日汇总 GMV 不允许出现负值。' },
    @{ name='交易-ADS-指标日期非空'; type='not_null'; target='ads.ads_order_stats'; expression='stat_date IS NOT NULL'; severity='medium'; cron='0 25 3 * * ?'; description='订单指标日报的统计日期必须完整。' }
)
$script:QualityRules = @(Invoke-AetherisApi -Method GET -Path '/api/quality/rules')
foreach ($definition in $qualityDefinitions) { Save-QualityRule -Definition $definition | Out-Null }

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
Write-Host 'REVIEW ONLY：landing DDL 不会执行；运行接入前请由管理员在独立业务 PostgreSQL 准备目标表。'

$ingestionDefinitions = @(
    @{ name='交易-ADS-订单指标同步至PostgreSQL'; replaces='订单数据每日同步'; target='landing.ads_order_stats_snapshot'; cron='0 20 4 * * ?'; sql='SELECT stat_date, month_key, is_weekend, order_cnt, valid_order_cnt, paid_user_cnt, gmv, avg_order_amount, refund_rate, cancel_rate, gmv_mom, gmv_7d_avg FROM ads.ads_order_stats'; description='每日将 Spark ADS 订单核心指标全量同步到 PostgreSQL landing 区，供服务侧低延迟读取。' },
    @{ name='销售-ADS-品类指标同步至PostgreSQL'; replaces='用户日志实时接入'; target='landing.ads_category_stats_snapshot'; cron='0 30 4 * * ?'; sql='SELECT month_key, category_id, category_name, order_cnt, sale_qty, sale_amount, discount_amount, avg_price, amount_rank, amount_share FROM ads.ads_category_stats'; description='每日将 Spark ADS 品类月度指标全量同步到 PostgreSQL landing 区，供报表与接口消费。' }
)
$script:IngestionTasks = @(Invoke-AetherisApi -Method GET -Path '/api/ingestion/tasks')
foreach ($definition in $ingestionDefinitions) { Save-IngestionTask -Definition $definition | Out-Null }

$domainSql = @'
UPDATE metadata.metadata_table SET business_domain_id=4, domain='用户域', updated_at=now()
WHERE schema_name IN ('ods','dwd','dws','ads') AND table_name IN ('ods_user','dwd_user','dws_user_daily','ads_user_profile');
UPDATE metadata.metadata_table SET business_domain_id=5, domain='交易域', updated_at=now()
WHERE schema_name IN ('ods','dwd','dws','ads') AND table_name IN ('ods_order','ods_order_item','dwd_order','dwd_order_item','dws_order_daily','ads_order_stats');
UPDATE metadata.metadata_table SET business_domain_id=6, domain='销售域', updated_at=now()
WHERE schema_name IN ('ods','dwd','dws','ads','dim') AND table_name IN ('ods_product','dwd_product','dws_category_daily','ads_category_stats','dim_category');
'@
Write-Host 'REVIEW ONLY：原业务域 SQL 含历史 ID，必须人工核实，不会自动修改元数据。'

# Complete every definition, dependency and schedule edit before approving any resource.
foreach ($resource in $script:Resources) {
    if ($ApproveDefinitions) { Approve-SavedDefinition $resource }
    else { $resource.State = Get-ApprovalState $resource.Path }
}
if ($RunTasks) {
    foreach ($resource in $script:Resources) {
        $body = if ($resource.Workflow) { @{ bizDate = $businessDate } } else { $null }
        $timeout = if ($resource.Workflow) { 3900 } elseif ($resource.Path.StartsWith('/api/quality/')) { 600 } else { 1800 }
        $result = Invoke-AetherisApi -Method POST -Path "$($resource.Path)/run" -Body $body -TimeoutSec $timeout
        $expected = if ($resource.Path.StartsWith('/api/quality/')) { 'passed' } else { 'success' }
        if ($result.status -cne $expected) { throw "执行未确认成功：$($resource.Name)，已停止且不重试；请检查运行记录" }
        $script:Executed.Add($resource.Path)
        Write-Host "PASS 执行：$($resource.Name)"
    }
}
if ($BindSchedules) {
    foreach ($resource in $script:Resources) {
        $current = Get-ApprovalState $resource.Path
        Assert-SameVersion $resource.State $current
        if (-not $current.approved) { throw '批准已经失效，停止绑定' }
        $bound = Invoke-AetherisApi -Method POST -Path "$($resource.Path)/cron-binding" -Body @{
            expectedRevision = $current.executionRevision; expectedGeneration = $current.scheduleGeneration
        }
        if ($bound.executionRevision -ne $current.executionRevision -or $bound.definitionSha256 -cne $current.definitionSha256 -or
            $bound.approved -isnot [bool] -or -not $bound.approved -or $bound.cronBound -isnot [bool] -or -not $bound.cronBound -or
            -not (Test-Integer $bound.bindingGeneration) -or $bound.bindingGeneration -le $current.bindingGeneration -or
            -not (Test-Integer $bound.scheduleGeneration) -or $bound.scheduleGeneration -le $current.scheduleGeneration) {
            throw '服务未确认当前版本的定时绑定，停止后续操作'
        }
        $script:BoundSchedules.Add($resource.Path)
        Write-Host "BOUND 定时：$($resource.Name)"
    }
}
$summary = [ordered]@{
    status = 'definitions-saved'
    businessDate = $businessDate
    workflowTasks = $definitions.name
    workflowDependencies = $dependencies.Count
    qualityRules = $qualityDefinitions.name
    ingestionTasks = $ingestionDefinitions.name
    approved = @($script:Approved.ToArray())
    executed = @($script:Executed.ToArray())
    boundSchedules = @($script:BoundSchedules.ToArray())
    pendingApproval = @($script:Resources | Where-Object { -not $_.State.approved } | ForEach-Object { $_.Path })
    executionRequested = [bool]$RunTasks
    scheduleBindingRequested = [bool]$BindSchedules
    reviewOnlySql = @{ landingSql = $landingSql; domainSql = $domainSql }
    removedTemporaryTask = $false
}
$summary | ConvertTo-Json -Depth 8
} catch {
    [ordered]@{ status = 'stopped-partial'; saved = @($script:Resources | ForEach-Object { $_.Path });
        approved = @($script:Approved.ToArray()); executed = @($script:Executed.ToArray());
        boundSchedules = @($script:BoundSchedules.ToArray()); lastAttempt = $script:LastAttempt;
        automaticallyRetried = $false } | ConvertTo-Json -Depth 8
    throw
} finally {
    $script:Session.Cookies = New-Object Net.CookieContainer
    $script:SessionClock.Stop()
}

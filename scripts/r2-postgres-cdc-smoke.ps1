[CmdletBinding(SupportsShouldProcess)]
param(
    [ValidateSet("postgres", "mysql")]
    [string]$SourceKind = "postgres",
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$GatewayEndpoint = "http://127.0.0.1:18083",
    [string]$SourceContainer = "",
    [string]$TrinoContainer = "aetheris-r1-lakehouse-trino-1",
    [string]$FlinkJobManagerEndpoint = "http://127.0.0.1:18081",
    [int]$SnapshotTimeoutSeconds = 180,
    [int]$MutationTimeoutSeconds = 180,
    [switch]$ResetSource,
    [switch]$RestartTaskManagerAfterSnapshot,
    [string]$ComposeFilePath = "infrastructure/lakehouse/docker-compose.r1.yml",
    [string]$ReportPath
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $repoRoot $FixtureOutput
$envFile = Join-Path $repoRoot $EnvFilePath
$defaults = if ($SourceKind -eq "mysql") {
    [ordered]@{
        sourceContainer = "aetheris-r1-lakehouse-source-mysql-1"
        rendered = "flink/rendered/003_mysql_cdc.sql"
        init = "mysql/001_source_orders.sql"
        mutation = "mysql/002_cdc_mutations.sql"
        targetTable = "r1_orders_mysql_cdc"
        jobName = "insert-into_lakehouse.bronze.r1_orders_mysql_cdc"
        report = "r2-mysql-cdc-report.json"
    }
} else {
    [ordered]@{
        sourceContainer = "aetheris-r1-lakehouse-source-postgres-1"
        rendered = "flink/rendered/002_postgres_cdc.sql"
        init = "postgres/001_source_orders.sql"
        mutation = "postgres/002_cdc_mutations.sql"
        targetTable = "r1_orders_cdc"
        jobName = "insert-into_lakehouse.bronze.r1_orders_cdc"
        report = "r2-postgres-cdc-report.json"
    }
}
if (-not $SourceContainer) { $SourceContainer = $defaults.sourceContainer }
$report = if ($ReportPath) { Join-Path $repoRoot $ReportPath } else { Join-Path $fixture $defaults.report }
$rendered = Join-Path $fixture $defaults.rendered
$initSql = Join-Path $fixture $defaults.init
$mutationSql = Join-Path $fixture $defaults.mutation

foreach ($path in @($envFile, $rendered, $initSql, $mutationSql)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required R2 fixture file is missing: $path" }
}
if ($SnapshotTimeoutSeconds -lt 1 -or $MutationTimeoutSeconds -lt 1) { throw "Timeout values must be positive" }

function Invoke-SourceSql {
    param([string]$Path)
    $sql = Get-Content -Raw -LiteralPath $Path
    if ($SourceKind -eq "mysql") {
        $sql | & docker exec -i $SourceContainer sh -lc 'mysql --protocol=TCP -h 127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD"'
    } else {
        $sql | & docker exec -i $SourceContainer sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    }
    if ($LASTEXITCODE -ne 0) { throw "$SourceKind fixture execution failed: $Path" }
}

function Get-CdcRowCount {
    $query = "SELECT count(*) FROM $($defaults.targetTable)"
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& docker exec $TrinoContainer trino --server http://localhost:8080 --catalog iceberg --schema bronze --output-format TSV_HEADER --execute $query 2>&1)
        $dockerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($dockerExitCode -ne 0) { return -1 }
    foreach ($line in $lines) {
        $text = ([string]$line).Trim()
        if ($text -match '^\d+$') { return [int]$text }
    }
    return -1
}

function Get-CdcJobId {
    $body = Invoke-RestMethod -Method Get -Uri "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/overview"
    $job = @($body.jobs | Where-Object {
        $_.name -eq $defaults.jobName -and $_.state -in @('RUNNING', 'RESTARTING', 'CREATED', 'INITIALIZING')
    } | Sort-Object start-time -Descending | Select-Object -First 1)
    if ($job.Count -eq 0) { return $null }
    return [string]$job[0].jid
}

function Wait-CdcCheckpoint {
    param([string]$JobId, [int]$TimeoutSeconds)
    if (-not $JobId) { throw "Cannot inspect checkpoint state without a Flink job id" }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $body = Invoke-RestMethod -Method Get -Uri "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/$JobId/checkpoints"
            $completed = $body.latest.completed
            if ($completed -and $completed.id -ne $null) {
                return [ordered]@{
                    id = [long]$completed.id
                    status = "completed"
                    triggerTimestamp = $completed.trigger_timestamp
                    latestAckTimestamp = $completed.latest_ack_timestamp
                }
            }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink CDC job did not complete a checkpoint before timeout"
}

function Restart-CdcTaskManager {
    $compose = Join-Path $repoRoot $ComposeFilePath
    if (-not (Test-Path -LiteralPath $compose -PathType Leaf)) { throw "R1 Compose file is missing: $compose" }
    & docker compose --profile r1 --env-file $envFile -f $compose restart flink-taskmanager | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Flink TaskManager restart failed" }
}

function Wait-CdcJobRunning {
    param([int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $jobId = Get-CdcJobId
            if ($jobId) { return $jobId }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink CDC job did not return to a running state after TaskManager restart"
}

function Stop-CdcRemote {
    param([string]$Session, [string]$Operation)
    if ($Operation -and $Session) {
        try {
            Invoke-RestMethod -Method Post -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$Session/operations/$Operation/cancel" -ContentType "application/json" -Body "{}" | Out-Null
        } catch { }
    }
    $jobId = $null
    try { $jobId = Get-CdcJobId } catch { }
    if ($jobId) {
        try {
            $body = '{"state":"CANCELED"}'
            curl.exe -s -X PATCH "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/$jobId" -H "Content-Type: application/json" -d $body | Out-Null
        } catch { }
    }
    if ($Session -and $Operation) {
        try { Invoke-RestMethod -Method Delete -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$Session/operations/$Operation/close" | Out-Null } catch { }
    }
    if ($Session) {
        try { Invoke-RestMethod -Method Delete -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$Session" | Out-Null } catch { }
    }
    return $jobId
}

function Get-FailureClass {
    param([string]$Message)
    $text = if ($Message) { $Message.ToLowerInvariant() } else { "" }
    if ($text -match "password|credential|authentication|auth") { return "configuration" }
    if ($text -match "docker|gateway|jobmanager|checkpoint|endpoint|connection|timeout|unavailable") { return "infrastructure" }
    if ($text -match "snapshot|mutation|reconcile|row|checksum|cdc") { return "data-contract" }
    return "execution"
}

$session = $null
$operation = $null
$startedAt = [DateTimeOffset]::UtcNow
$result = [ordered]@{
    status = "failed"
    sourceKind = $SourceKind
    fixture = "aetheris-r1-$SourceKind-iceberg-v1"
    sourceContainer = $SourceContainer
    targetTable = $defaults.targetTable
    startedAt = $startedAt.ToString("O")
    snapshotRowCount = $null
    mutationApplied = $false
    cdcVerification = $null
    checkpoint = $null
    restart = [ordered]@{
        requested = [bool]$RestartTaskManagerAfterSnapshot
        completed = $false
        checkpointAfterRestart = $null
        jobIdAfterRestart = $null
    }
    jobIdBeforeRestart = $null
    failureClass = $null
    jobId = $null
    error = $null
}
try {
    if ($ResetSource) {
        if (-not $PSCmdlet.ShouldProcess($SourceContainer, "reset isolated PostgreSQL source fixture")) {
            throw "ResetSource was not confirmed for $SourceKind"
        }
        Invoke-SourceSql $initSql
    }

    $submission = (& pwsh -NoProfile -File (Join-Path $repoRoot "scripts/r1-submit-flink-sql.ps1") `
        -GatewayEndpoint $GatewayEndpoint -SqlFile $rendered -EnvFile $envFile `
        -KeepInsertRunning -TimeoutSeconds $SnapshotTimeoutSeconds | Select-Object -Last 1) | ConvertFrom-Json
    $session = [string]$submission.session
    $operation = [string]$submission.operation
    if (-not $session -or -not $operation -or $submission.status -ne "running") {
        throw "Flink SQL Gateway did not return a running CDC handle"
    }

    $deadline = (Get-Date).AddSeconds($SnapshotTimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $count = Get-CdcRowCount
    } while ($count -ne 5 -and (Get-Date) -lt $deadline)
    $result.snapshotRowCount = $count
    if ($count -ne 5) { throw "CDC initial snapshot did not expose 5 rows before timeout (count=$count)" }

    # A mutation is not accepted until the running job has produced a completed
    # checkpoint. This is required even when the optional process-restart
    # exercise is not requested; otherwise the report could claim CDC safety
    # from a snapshot-only observation.
    $result.jobIdBeforeRestart = Get-CdcJobId
    $result.checkpoint = Wait-CdcCheckpoint -JobId $result.jobIdBeforeRestart -TimeoutSeconds $SnapshotTimeoutSeconds

    if ($RestartTaskManagerAfterSnapshot) {
        Restart-CdcTaskManager
        $result.restart.jobIdAfterRestart = Wait-CdcJobRunning -TimeoutSeconds $SnapshotTimeoutSeconds
        $result.restart.checkpointAfterRestart = Wait-CdcCheckpoint `
            -JobId $result.restart.jobIdAfterRestart -TimeoutSeconds $SnapshotTimeoutSeconds
        $result.restart.completed = $true
    }

    if (-not $PSCmdlet.ShouldProcess($SourceContainer, "apply deterministic PostgreSQL CDC mutations")) {
        throw "CDC mutation was not confirmed"
    }
    Invoke-SourceSql $mutationSql
    $result.mutationApplied = $true

    $deadline = (Get-Date).AddSeconds($MutationTimeoutSeconds)
    $verification = $null
    do {
        Start-Sleep -Seconds 2
        & pwsh -NoProfile -File (Join-Path $repoRoot "scripts/r2-trino-cdc-read.ps1") `
            -FixtureOutput $FixtureOutput -Container $TrinoContainer -Table $defaults.targetTable | Out-Null
        & py -3.12 (Join-Path $repoRoot "scripts/r1-fixture.py") verify-cdc `
            --fixture $fixture --result (Join-Path $fixture "results/r2-cdc.json") `
            --report (Join-Path $fixture "r2-cdc-report.json") --source-kind $SourceKind | Out-Null
        if (Test-Path -LiteralPath (Join-Path $fixture "r2-cdc-report.json")) {
            $verification = Get-Content -Raw -LiteralPath (Join-Path $fixture "r2-cdc-report.json") | ConvertFrom-Json
        }
    } while (($null -eq $verification -or $verification.status -ne "passed") -and (Get-Date) -lt $deadline)
    $result.cdcVerification = $verification
    if ($null -eq $verification -or $verification.status -ne "passed") {
        throw "PostgreSQL CDC mutation did not reconcile before timeout"
    }
    $result.status = "passed"
} catch {
    $result.error = $_.Exception.Message
    $result.failureClass = Get-FailureClass $result.error
    throw
} finally {
    $result.jobId = Stop-CdcRemote -Session $session -Operation $operation
    $result.finishedAt = [DateTimeOffset]::UtcNow.ToString("O")
    $result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $report -Encoding UTF8
}

Write-Output ($result | ConvertTo-Json -Depth 12 -Compress)

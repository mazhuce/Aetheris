[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$GatewayEndpoint = "http://127.0.0.1:18083",
    [string]$FlinkJobManagerEndpoint = "http://127.0.0.1:18081",
    [string]$ProjectName = "aetheris-r1-lakehouse",
    [string]$SourceContainer = "",
    [string]$TrinoContainer = "",
    [string]$MetadataContainer = "",
    [string]$MetadataDbContainer = "aetheris-postgres-1",
    [string]$ReportPath = "artifacts/r4-event-time/r4-event-time-quality-report.json",
    [int]$TimeoutSeconds = 240,
    [switch]$SkipMetadataCallback
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$reportFile = Join-Path $repoRoot $ReportPath
$SourceContainer = if ($SourceContainer) { $SourceContainer } else { "$ProjectName-source-postgres-1" }
$TrinoContainer = if ($TrinoContainer) { $TrinoContainer } else { "$ProjectName-trino-1" }
$runKey = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$database = "r4_quality"
$table = "event_time_quality_$runKey"
$sourceTable = "event_time_quality_$runKey"
$slot = "aetheris_r4_quality_$runKey"
$report = [ordered]@{
    schemaVersion = 1
    gate = "R4-flink-event-time-quality-runtime-gate"
    status = "failed"
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    source = [ordered]@{ kind = "postgres-cdc"; table = $sourceTable; slot = $slot }
    watermark = [ordered]@{ expression = "event_time - INTERVAL '5' SECOND"; allowedLatenessSeconds = 5; observedAt = $null }
    job = [ordered]@{ id = $null; state = $null; checkpointId = $null; canceled = $false }
    counts = [ordered]@{ accepted = $null; deadLetter = $null; total = $null }
    metadataCallback = [ordered]@{ status = if ($SkipMetadataCallback) { "skipped" } else { "not_started" }; tableId = $null; failedClosed = $false }
    cleanup = [ordered]@{ sourceRowsRemoved = $false; replicationSlotRemoved = $false; icebergTableRemoved = $false; metadataRowsRemoved = $false; jobStopped = $false; error = $null }
    evidence = [ordered]@{}
    error = $null
}

function Invoke-Docker {
    param([string[]]$Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed: $($output -join ' ')" }
    return @($output | ForEach-Object { [string]$_ })
}

function Invoke-SourceSql {
    param([string]$Sql)
    $Sql | & docker exec -i $SourceContainer sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "source PostgreSQL fixture SQL failed" }
}

function Invoke-TrinoScalar {
    param([string]$Query)
    $lines = @(Invoke-Docker @("exec", $TrinoContainer, "trino", "--server", "http://localhost:8080", "--catalog", "iceberg", "--schema", $database, "--output-format", "TSV_HEADER", "--execute", $Query))
    foreach ($line in $lines) {
        $text = ([string]$line).Trim()
        if ($text -match '^[0-9]+$') { return [long]$text }
    }
    return $null
}

function Invoke-TrinoRows {
    param([string]$Query)
    return @(Invoke-Docker @("exec", $TrinoContainer, "trino", "--server", "http://localhost:8080", "--catalog", "iceberg", "--schema", $database, "--output-format", "TSV_HEADER", "--execute", $Query) |
            Where-Object { $_ -notmatch '^Sep \d{1,2}, \d{4} .* org\.jline\.utils\.Log' -and $_ -notmatch '^WARNING: Unable to create a system terminal' })
}

function Get-JobId {
    $body = Invoke-RestMethod -Method Get -Uri "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/overview"
    $jobs = @($body.jobs | Where-Object { $_.name -like "*${table}*" -and ([string]$_.state).ToUpperInvariant() -eq "RUNNING" } | Sort-Object start-time -Descending)
    if ($jobs.Count -eq 0) { return $null }
    return [string]$jobs[0].jid
}

function Wait-JobId {
    param([int]$Seconds)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { $id = Get-JobId; if ($id) { return $id } } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "R4 event-time Flink job did not appear in JobManager"
}

function Wait-Counts {
    param([int]$Seconds)
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $job = Invoke-RestMethod -Method Get -Uri "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/$($report.job.id)"
        $report.job.state = [string]$job.state
        if ($job.state -in @("FAILED", "CANCELED", "FINISHED")) { throw "Flink event-time job reached terminal state $($job.state) before reconciliation" }
        try {
            $accepted = Invoke-TrinoScalar "SELECT count(*) FROM $table WHERE route = 'accepted'"
            $dead = Invoke-TrinoScalar "SELECT count(*) FROM $table WHERE route = 'dead_letter'"
            $total = Invoke-TrinoScalar "SELECT count(*) FROM $table"
            if ($accepted -eq 2 -and $dead -eq 1 -and $total -eq 3) { return [ordered]@{ accepted = $accepted; deadLetter = $dead; total = $total } }
        } catch { }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "Flink event-time route did not produce exactly two accepted and one dead-letter rows"
}

function New-IdentityHeader {
    param([string]$Container)
    $lines = Invoke-Docker @("inspect", $Container, "--format", "{{range .Config.Env}}{{println .}}{{end}}")
    $line = $lines | Where-Object { $_ -like "AETHERIS_INTERNAL_TOKEN=*" } | Select-Object -First 1
    if (-not $line) { throw "metadata internal token is not configured" }
    $token = ([string]$line).Substring(24).Trim()
    $payload = "-|admin|$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($token))
    $signature = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($encoded)) | ForEach-Object { $_.ToString("x2") })
    return "$encoded.$signature"
}

function Invoke-MetadataJson {
    param([string]$Identity, [ValidateSet("GET", "POST")][string]$Method, [string]$Path, [hashtable]$Body = $null)
    $curl = 'curl -fsS -X ' + $Method + ' -H "X-Aetheris-Identity: ' + $Identity + '"'
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress -Depth 12
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
        $curl += ' -H "Content-Type: application/json" --data-binary @-'
        $command = "printf '%s' '$encoded' | base64 -d | $curl http://127.0.0.1:8082$Path"
    } else { $command = "$curl http://127.0.0.1:8082$Path" }
    $raw = (Invoke-Docker @("exec", $MetadataContainer, "sh", "-lc", $command) | Out-String).Trim()
    if (-not $raw) { throw "metadata endpoint returned empty response: $Path" }
    return $raw | ConvertFrom-Json
}

$session = $null
$operation = $null
$tableId = $null
try {
    if ($TimeoutSeconds -lt 30) { throw "TimeoutSeconds must be at least 30" }
    # The table is intentionally append-only: this keeps CDC event envelopes visible
    # so a late event cannot be hidden by an Iceberg upsert.
    Invoke-SourceSql @"
CREATE SCHEMA IF NOT EXISTS r4_fixture;
DROP TABLE IF EXISTS r4_fixture.$sourceTable;
CREATE TABLE r4_fixture.$sourceTable (
  id BIGINT PRIMARY KEY,
  payload TEXT NOT NULL,
  event_time TIMESTAMPTZ NOT NULL
);
"@

    $sql = @"
CREATE CATALOG lakehouse WITH (
  'type' = 'iceberg', 'catalog-type' = 'rest', 'uri' = 'http://catalog:8181',
  'warehouse' = 's3://warehouse/', 's3.endpoint' = 'http://object-store:9000',
  's3.path-style-access' = 'true', 's3.region' = 'us-east-1'
);
USE CATALOG lakehouse;
CREATE DATABASE IF NOT EXISTS $database;
SET 'execution.checkpointing.interval' = '1 s';
SET 'execution.checkpointing.storage' = 'filesystem';
SET 'state.checkpoints.dir' = 's3://warehouse/checkpoints/';
SET 'state.savepoints.dir' = 's3://warehouse/savepoints/';
SET 'execution.runtime-mode' = 'streaming';
DROP TABLE IF EXISTS $database.$table;
CREATE TABLE $database.$table (
  id BIGINT,
  payload STRING,
  event_time TIMESTAMP(3),
  route STRING,
  watermark_at TIMESTAMP(3)
) WITH ('format-version' = '2');
CREATE TEMPORARY TABLE r4_event_source (
  id BIGINT,
  payload STRING,
  event_time TIMESTAMP(3),
  WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
) WITH (
  'connector' = 'postgres-cdc', 'hostname' = 'source-postgres', 'port' = '5432',
  'username' = '`${R1_SOURCE_POSTGRES_USER}', 'password' = '`${R1_SOURCE_POSTGRES_PASSWORD}',
  'database-name' = 'r1_source', 'schema-name' = 'r4_fixture', 'table-name' = '$sourceTable',
  'slot.name' = '$slot', 'decoding.plugin.name' = 'pgoutput', 'scan.startup.mode' = 'initial'
);
INSERT INTO $database.$table
SELECT id, payload, event_time,
  CASE WHEN CURRENT_WATERMARK(event_time) IS NOT NULL
         AND event_time < CURRENT_WATERMARK(event_time) THEN 'dead_letter' ELSE 'accepted' END,
  CAST(CURRENT_WATERMARK(event_time) AS TIMESTAMP(3))
FROM r4_event_source;
"@
    $envValues = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot $EnvFilePath)) {
        if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') { $envValues[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'") }
    }
    foreach ($key in @("R1_SOURCE_POSTGRES_USER", "R1_SOURCE_POSTGRES_PASSWORD")) {
        if ([string]::IsNullOrWhiteSpace([string]$envValues[$key])) { throw "R1 source credential $key is missing" }
        $sql = $sql.Replace("`${$key}", ([string]$envValues[$key]).Replace("'", "''"))
    }
    $sessionBody = Invoke-RestMethod -Method Post -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions" -ContentType "application/json" -Body "{}"
    $session = [string]$sessionBody.sessionHandle
    if (-not $session) { throw "SQL Gateway did not return a session" }
    $statements = $sql -split "(?m);\s*(?=(?:CREATE|USE|SET|DROP|INSERT)\b)" | Where-Object { $_.Trim() }
    foreach ($statement in $statements) {
        $operationBody = Invoke-RestMethod -Method Post -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$session/statements" -ContentType "application/json" -Body (@{ statement = $statement.Trim() } | ConvertTo-Json -Compress)
        $operation = [string]$operationBody.operationHandle
        if ($statement.Trim() -match '(?is)^INSERT\s+INTO') { $keepRunning = $true; break }
        do {
            Start-Sleep -Milliseconds 500
            $statusBody = Invoke-RestMethod -Method Get -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$session/operations/$operation/status"
            $operationStatus = if ($statusBody.status.type) { [string]$statusBody.status.type } else { [string]$statusBody.status }
        } while ($operationStatus -notin @("FINISHED", "ERROR", "FAILED") -and $operationStatus -ne "CANCELED")
        if ($operationStatus -ne "FINISHED") { throw "SQL statement failed: $operationStatus" }
    }
    $jobId = Wait-JobId -Seconds 90
    $report.job.id = $jobId
    Invoke-SourceSql "INSERT INTO r4_fixture.$sourceTable (id,payload,event_time) VALUES (1,'on-time-1','2026-09-23 00:00:00+00'),(2,'on-time-2','2026-09-23 00:01:00+00');"
    Start-Sleep -Seconds 8
    Invoke-SourceSql "INSERT INTO r4_fixture.$sourceTable (id,payload,event_time) VALUES (3,'late-1','2026-09-23 00:00:10+00');"
    $counts = Wait-Counts -Seconds $TimeoutSeconds
    $report.counts = $counts
    $watermarked = Invoke-TrinoScalar "SELECT count(*) FROM $table WHERE watermark_at IS NOT NULL"
    if ($watermarked -lt 2) { throw "Flink did not persist a non-null watermark for the later events" }
    $report.evidence.watermarkedRows = $watermarked
    $watermarkLines = @(Invoke-TrinoRows "SELECT CAST(max(watermark_at) AS VARCHAR) FROM $table")
    $watermarkLine = $watermarkLines | Where-Object { $_ -match '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}' } | Select-Object -Last 1
    if (-not $watermarkLine) { throw "Trino did not return the observed Flink watermark" }
    $report.watermark.observedAt = ([string]$watermarkLine).Trim().Replace(' ', 'T') + 'Z'
    $report.evidence.rows = @(Invoke-TrinoRows "SELECT route, count(*) FROM $table GROUP BY route ORDER BY route")
    $checkpointDeadline = (Get-Date).AddSeconds(60)
    do {
        $checkpoints = Invoke-RestMethod -Method Get -Uri "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/$jobId/checkpoints"
        if ($null -ne $checkpoints.latest.completed.id) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $checkpointDeadline)
    if ($null -eq $checkpoints.latest.completed.id) { throw "Flink event-time job did not complete a checkpoint" }
    $report.job.checkpointId = [long]$checkpoints.latest.completed.id
    $report.status = if ($SkipMetadataCallback) { "partial" } else { "passed" }
    if (-not $SkipMetadataCallback) {
        if (-not $MetadataContainer) { throw "MetadataContainer is required unless -SkipMetadataCallback is supplied" }
        $identity = New-IdentityHeader $MetadataContainer
        $metadataTable = Invoke-MetadataJson $identity POST "/api/metadata/lakehouse/tables" @{
            catalogName = "iceberg"; namespaceName = $database; tableName = $table
            location = "s3://warehouse/$database/$table"; formatVersion = 2; layer = "gold"
            retentionDays = 7; propertiesJson = '{"acceptance":"r4-event-time"}'
        }
        $tableId = [long]$metadataTable.id
        $report.metadataCallback.tableId = $tableId
        $observedAt = [DateTimeOffset]::UtcNow.ToString("O")
        $failed = Invoke-MetadataJson $identity POST "/api/metadata/lakehouse/tables/$tableId/quality-observations" @{
            layer = "gold"; ruleKey = "r4.event_time.dead_letter"; status = "failed"
            violationCount = [long]$counts.deadLetter; lateDataCount = [long]$counts.deadLetter; deadLetterCount = [long]$counts.deadLetter
            watermarkAt = $report.watermark.observedAt; blocksGold = $true; runId = $runKey; observedAt = $observedAt; detailsJson = '{"source":"flink"}'
        }
        $blocked = Invoke-MetadataJson $identity GET "/api/metadata/lakehouse/tables/$tableId/gold-quality-gate"
        $report.metadataCallback.failedClosed = (-not [bool]$blocked.allowed)
        if (-not $report.metadataCallback.failedClosed) { throw "Gold quality gate did not fail closed for dead-letter observation" }
        $report.metadataCallback.status = "passed"
    }
} catch {
    $report.error = $_.Exception.Message
    $report.status = "failed"
} finally {
    if ($operation -and $session) {
        try { Invoke-RestMethod -Method Post -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$session/operations/$operation/cancel" -ContentType "application/json" -Body "{}" | Out-Null; $report.job.canceled = $true } catch { }
    }
    if ($session) { try { Invoke-RestMethod -Method Delete -Uri "$($GatewayEndpoint.TrimEnd('/'))/v1/sessions/$session" | Out-Null } catch { } }
    if ($report.job.id) {
        try {
            $jobEndpoint = "$($FlinkJobManagerEndpoint.TrimEnd('/'))/jobs/$($report.job.id)"
            $jobState = [string](Invoke-RestMethod -Method Get -Uri $jobEndpoint).state
            if ($jobState -notin @("CANCELED", "FAILED", "FINISHED")) {
                Invoke-RestMethod -Method Patch -Uri $jobEndpoint -ContentType "application/json" -Body '{"state":"CANCELED"}' | Out-Null
            }
            $deadline = (Get-Date).AddSeconds(30)
            do {
                $jobState = [string](Invoke-RestMethod -Method Get -Uri $jobEndpoint).state
                if ($jobState -in @("CANCELED", "FAILED", "FINISHED")) { break }
                Start-Sleep -Seconds 1
            } while ((Get-Date) -lt $deadline)
            $report.job.state = $jobState
            $report.job.canceled = $jobState -eq "CANCELED"
            $report.cleanup.jobStopped = $jobState -in @("CANCELED", "FAILED", "FINISHED")
            if (-not $report.cleanup.jobStopped) { throw "Flink job did not reach a terminal state during cleanup" }
        } catch { $report.cleanup.error = $_.Exception.Message }
    }
    try { Invoke-SourceSql "DROP TABLE IF EXISTS r4_fixture.$sourceTable;"; $report.cleanup.sourceRowsRemoved = $true } catch { $report.cleanup.error = $_.Exception.Message }
    try {
        $slotCleanup = "SELECT pg_drop_replication_slot('$slot') FROM pg_replication_slots WHERE slot_name='$slot';"
        Invoke-SourceSql $slotCleanup
        $report.cleanup.replicationSlotRemoved = $true
    } catch { $report.cleanup.error = $_.Exception.Message }
    if ($report.cleanup.jobStopped) {
        try { Invoke-TrinoRows "DROP TABLE IF EXISTS $table" | Out-Null; $report.cleanup.icebergTableRemoved = $true } catch { $report.cleanup.error = $_.Exception.Message }
    }
    if ($tableId -and $MetadataContainer -and -not $SkipMetadataCallback) {
        try {
            $dbEnv = Invoke-Docker @("inspect", $MetadataDbContainer, "--format", "{{range .Config.Env}}{{println .}}{{end}}")
            $db = (($dbEnv | Where-Object { $_ -like "POSTGRES_DB=*" } | Select-Object -First 1).ToString().Substring(12))
            $user = (($dbEnv | Where-Object { $_ -like "POSTGRES_USER=*" } | Select-Object -First 1).ToString().Substring(14))
            $sqlCleanup = "DELETE FROM metadata.lakehouse_table WHERE id=$tableId;"
            Invoke-Docker @("exec", $MetadataDbContainer, "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", $user, "-d", $db, "-c", $sqlCleanup) | Out-Null
            $report.cleanup.metadataRowsRemoved = $true
        } catch { $report.cleanup.error = $_.Exception.Message }
    }
    if ($report.cleanup.error) {
        $report.status = "failed"
        if (-not $report.error) { $report.error = "cleanup failed: $($report.cleanup.error)" }
    }
    $report.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFile) | Out-Null
    $serializedReport = $report | ConvertTo-Json -Depth 50
    Set-Content -LiteralPath $reportFile -Value $serializedReport -Encoding UTF8
}

Write-Output ($report | ConvertTo-Json -Depth 50 -Compress)
if ($report.status -eq "failed") { exit 1 }
if ($report.status -eq "partial") { exit 2 }

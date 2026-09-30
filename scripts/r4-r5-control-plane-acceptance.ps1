[CmdletBinding()]
param(
    [string]$MetadataContainer = "aetheris-metadata-service-1",
    [string]$ReportPath = "artifacts/r4-r5-control-plane/runtime-acceptance-report.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$reportFile = Join-Path $repoRoot $ReportPath
$report = [ordered]@{
    schemaVersion = 2
    gate = "R4-R5-lakehouse-control-plane-runtime-gate"
    status = "failed"
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    tableId = $null
    snapshotIds = @()
    refIds = @()
    schemaChangeId = $null
    quality = [ordered]@{ blockedOnFailure = $false; reopenedAfterPass = $false }
    evidence = [ordered]@{ schemaPolicy = [ordered]@{} }
    cleanup = [ordered]@{ tableRowsRemoved = $false; error = $null }
    error = $null
}

function Invoke-Docker {
    param([string[]]$Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed: $($output -join ' ')" }
    return @($output)
}

function New-IdentityHeader {
    $lines = Invoke-Docker @("inspect", $MetadataContainer, "--format", "{{range .Config.Env}}{{println .}}{{end}}")
    $line = $lines | Where-Object { $_ -like "AETHERIS_INTERNAL_TOKEN=*" } | Select-Object -First 1
    if (-not $line) { throw "metadata internal token is not configured" }
    $token = ([string]$line).Substring(24).Trim()
    $payload = "-|admin|$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($token))
    $signature = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($encoded)) | ForEach-Object { $_.ToString("x2") })
    return "$encoded.$signature"
}

$identity = New-IdentityHeader

function Invoke-MetadataJson {
    param([ValidateSet("GET", "POST", "DELETE")][string]$Method, [string]$Path, [hashtable]$Body = $null)
    $curl = 'curl -fsS -X ' + $Method + ' -H "X-Aetheris-Identity: ' + $identity + '"'
    if ($null -ne $Body) {
        $json = ($Body | ConvertTo-Json -Compress -Depth 12)
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
        $curl += ' -H "Content-Type: application/json" --data-binary @-'
        $command = "printf '%s' '$encoded' | base64 -d | $curl http://127.0.0.1:8082$Path"
    } else {
        $command = "$curl http://127.0.0.1:8082$Path"
    }
    $raw = (Invoke-Docker @("exec", $MetadataContainer, "sh", "-lc", $command) | Out-String).Trim()
    if (-not $raw) { throw "metadata endpoint returned an empty response: $Path" }
    return $raw | ConvertFrom-Json
}

function Invoke-MetadataStatus {
    param([ValidateSet("GET", "POST", "DELETE")][string]$Method, [string]$Path, [hashtable]$Body = $null)
    $null = Invoke-MetadataJson -Method $Method -Path $Path -Body $Body
}

$tableName = "r4_r5_acceptance_$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
$tablePath = $null
try {
    $table = Invoke-MetadataJson POST "/api/metadata/lakehouse/tables" @{
        catalogName = "iceberg"; namespaceName = "acceptance"; tableName = $tableName
        location = "s3://warehouse/acceptance/$tableName"; formatVersion = 2; layer = "gold"
        retentionDays = 30; propertiesJson = '{"acceptance":"r4-r5"}'
    }
    $report.tableId = [long]$table.id
    $tablePath = "/api/metadata/lakehouse/tables/$($report.tableId)"

    foreach ($snapshot in @(
        @{ snapshotId = "1001"; operation = "append"; committedAt = "2026-09-20T12:00:00Z"; rowCount = 5; fileCount = 1; totalBytes = 512; schemaVersion = 1; metadataJson = '{}' },
        @{ snapshotId = "1002"; parentSnapshotId = "1001"; operation = "append"; committedAt = "2026-09-20T12:01:00Z"; rowCount = 6; fileCount = 2; totalBytes = 768; schemaVersion = 2; metadataJson = '{}' }
    )) {
        $created = Invoke-MetadataJson POST "$tablePath/snapshots" $snapshot
        $report.snapshotIds += [string]$created.snapshotId
    }

    $branch = Invoke-MetadataJson POST "/api/metadata/lakehouse/tables/$($report.tableId)/refs" @{ refName = "main"; refType = "branch"; snapshotId = "1002"; protected = $false }
    $tag = Invoke-MetadataJson POST "/api/metadata/lakehouse/tables/$($report.tableId)/refs" @{ refName = "release_1"; refType = "tag"; snapshotId = "1001"; protected = $true }
    $report.refIds = @([long]$branch.id, [long]$tag.id)

    $schema = Invoke-MetadataJson POST "$tablePath/schema-changes" @{ operation = "add_column"; columnName = "ingested_at"; toType = "timestamp"; reason = "R4 acceptance" }
    $report.schemaChangeId = [long]$schema.id
    $plan = Invoke-MetadataJson GET "/api/metadata/lakehouse/schema-changes/$($report.schemaChangeId)/plan?tableId=$($report.tableId)"
    $report.evidence.schemaPlan = [ordered]@{ decision = [string]$schema.decision; status = [string]$schema.status; readyForExecution = [bool]$plan.readyForExecution; statement = [string]$plan.statement }

    # Destructive changes must be reviewed before a plan is executable. The
    # acceptance intentionally exercises metadata state transitions only; it
    # never dispatches destructive SQL to the maintenance runner.
    $rename = Invoke-MetadataJson POST "$tablePath/schema-changes" @{
        operation = "rename_column"; columnName = "ingested_at"; newColumnName = "ingested_time"; reason = "R4 rename approval acceptance"
    }
    $renameApproved = Invoke-MetadataJson POST "/api/metadata/lakehouse/schema-changes/$($rename.id)/review?tableId=$($report.tableId)" @{ approved = $true }
    $renamePlan = Invoke-MetadataJson GET "/api/metadata/lakehouse/schema-changes/$($rename.id)/plan?tableId=$($report.tableId)"
    $report.evidence.schemaPolicy.rename = [ordered]@{
        decision = [string]$rename.decision
        proposedStatus = [string]$rename.status
        reviewedStatus = [string]$renameApproved.status
        readyForExecution = [bool]$renamePlan.readyForExecution
        destructive = [bool]$renamePlan.destructive
        statement = [string]$renamePlan.statement
    }
    if ([string]$rename.decision -ne "approval_required" -or [string]$rename.status -ne "proposed" -or
        [string]$renameApproved.status -ne "approved" -or -not [bool]$renamePlan.readyForExecution -or
        -not [bool]$renamePlan.destructive) {
        throw "rename_column did not follow approval_required -> approved policy"
    }

    $drop = Invoke-MetadataJson POST "$tablePath/schema-changes" @{
        operation = "drop_column"; columnName = "legacy_note"; reason = "R4 drop rejection acceptance"
    }
    $dropRejected = Invoke-MetadataJson POST "/api/metadata/lakehouse/schema-changes/$($drop.id)/review?tableId=$($report.tableId)" @{ approved = $false }
    $dropPlanRejected = $false
    try {
        $null = Invoke-MetadataJson GET "/api/metadata/lakehouse/schema-changes/$($drop.id)/plan?tableId=$($report.tableId)"
    } catch {
        $dropPlanRejected = $true
    }
    $report.evidence.schemaPolicy.drop = [ordered]@{
        decision = [string]$drop.decision
        proposedStatus = [string]$drop.status
        reviewedStatus = [string]$dropRejected.status
        planRejected = $dropPlanRejected
    }
    if ([string]$drop.decision -ne "approval_required" -or [string]$drop.status -ne "proposed" -or
        [string]$dropRejected.status -ne "rejected" -or -not $dropPlanRejected) {
        throw "drop_column did not fail closed after rejection"
    }

    $narrow = Invoke-MetadataJson POST "$tablePath/schema-changes" @{
        operation = "narrow_type"; columnName = "amount"; fromType = "bigint"; toType = "int"; reason = "R4 narrow type isolation acceptance"
    }
    $narrowIsolated = Invoke-MetadataJson POST "/api/metadata/lakehouse/schema-changes/$($narrow.id)/isolate?tableId=$($report.tableId)"
    $narrowPlanRejected = $false
    try {
        $null = Invoke-MetadataJson GET "/api/metadata/lakehouse/schema-changes/$($narrow.id)/plan?tableId=$($report.tableId)"
    } catch {
        $narrowPlanRejected = $true
    }
    $report.evidence.schemaPolicy.narrow = [ordered]@{
        decision = [string]$narrow.decision
        proposedStatus = [string]$narrow.status
        isolatedStatus = [string]$narrowIsolated.status
        planRejected = $narrowPlanRejected
    }
    if ([string]$narrow.decision -ne "isolate" -or [string]$narrow.status -ne "proposed" -or
        [string]$narrowIsolated.status -ne "isolated" -or -not $narrowPlanRejected) {
        throw "narrow_type did not enter isolation or fail closed"
    }

    $sameTime = "2026-09-20T12:02:00Z"
    $null = Invoke-MetadataJson POST "$tablePath/quality-observations" @{ layer = "gold"; ruleKey = "orders.not_null"; status = "passed"; observedAt = $sameTime; blocksGold = $true; detailsJson = '{}' }
    $null = Invoke-MetadataJson POST "$tablePath/quality-observations" @{ layer = "gold"; ruleKey = "orders.not_null"; status = "failed"; violationCount = 1; observedAt = $sameTime; blocksGold = $true; detailsJson = '{}' }
    $blocked = Invoke-MetadataJson GET "$tablePath/gold-quality-gate"
    $report.quality.blockedOnFailure = (-not [bool]$blocked.allowed) -and @($blocked.blockingRuleKeys).Count -eq 1
    if (-not $report.quality.blockedOnFailure) { throw "Gold quality gate did not fail closed after a failed observation" }
    $null = Invoke-MetadataJson POST "$tablePath/quality-observations" @{ layer = "gold"; ruleKey = "orders.not_null"; status = "passed"; observedAt = "2026-09-20T12:03:00Z"; blocksGold = $true; detailsJson = '{}' }
    $reopened = Invoke-MetadataJson GET "$tablePath/gold-quality-gate"
    $report.quality.reopenedAfterPass = [bool]$reopened.allowed

    $currentTable = Invoke-MetadataJson GET $tablePath
    $report.evidence.table = [ordered]@{ formatVersion = [int]$currentTable.formatVersion; layer = [string]$currentTable.layer; currentSnapshotId = [string]$currentTable.currentSnapshotId }
    if ([string]$currentTable.currentSnapshotId -ne "1002") { throw "current snapshot pointer did not advance to 1002" }
    $report.evidence.snapshotCount = @((Invoke-MetadataJson GET "$tablePath/snapshots")).Count
    $report.status = "passed"
} catch {
    $report.error = $_.Exception.Message
} finally {
    if ($report.tableId) {
        try {
            $envLines = Invoke-Docker @("inspect", "aetheris-postgres-1", "--format", "{{range .Config.Env}}{{println .}}{{end}}")
            $db = ($envLines | Where-Object { $_ -like "POSTGRES_DB=*" } | Select-Object -First 1).ToString().Substring(12)
            $user = ($envLines | Where-Object { $_ -like "POSTGRES_USER=*" } | Select-Object -First 1).ToString().Substring(14)
            # Schema-change and maintenance records intentionally use RESTRICT;
            # remove those request records first, then let table-level CASCADE
            # remove snapshots, refs and quality observations.
            $sql = "BEGIN; DELETE FROM metadata.lakehouse_schema_change WHERE lakehouse_table_id=$($report.tableId); DELETE FROM metadata.lakehouse_maintenance_job WHERE lakehouse_table_id=$($report.tableId); DELETE FROM metadata.lakehouse_table WHERE id=$($report.tableId); COMMIT;"
            Invoke-Docker @("exec", "aetheris-postgres-1", "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", $user, "-d", $db, "-c", $sql) | Out-Null
            $report.cleanup.tableRowsRemoved = $true
        } catch {
            $report.cleanup.error = $_.Exception.Message
            if (-not $report.error) { $report.error = "cleanup failed: $($_.Exception.Message)" }
            $report.status = "failed"
        }
    }
    $report.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFile) | Out-Null
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportFile -Encoding UTF8

    # Publish only a bounded, secret-free summary for the acceptance page. The
    # full report remains the audit artifact; this file is safe for static web
    # delivery and is refreshed on every runtime acceptance.
    try {
        $uiSummaryFile = Join-Path $repoRoot "apps/web/public/acceptance/r4-r5-control-plane.json"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $uiSummaryFile) | Out-Null
        $renamePolicy = if ($null -eq $report.evidence.schemaPolicy.rename) {
            [ordered]@{ decision = "unknown"; status = "unknown"; readyForExecution = $false; destructive = $false }
        } else {
            [ordered]@{
                decision = [string]$report.evidence.schemaPolicy.rename.decision
                status = [string]$report.evidence.schemaPolicy.rename.reviewedStatus
                readyForExecution = [bool]$report.evidence.schemaPolicy.rename.readyForExecution
                destructive = [bool]$report.evidence.schemaPolicy.rename.destructive
            }
        }
        $dropPolicy = if ($null -eq $report.evidence.schemaPolicy.drop) {
            [ordered]@{ decision = "unknown"; status = "unknown"; planRejected = $false }
        } else {
            [ordered]@{
                decision = [string]$report.evidence.schemaPolicy.drop.decision
                status = [string]$report.evidence.schemaPolicy.drop.reviewedStatus
                planRejected = [bool]$report.evidence.schemaPolicy.drop.planRejected
            }
        }
        $narrowPolicy = if ($null -eq $report.evidence.schemaPolicy.narrow) {
            [ordered]@{ decision = "unknown"; status = "unknown"; planRejected = $false }
        } else {
            [ordered]@{
                decision = [string]$report.evidence.schemaPolicy.narrow.decision
                status = [string]$report.evidence.schemaPolicy.narrow.isolatedStatus
                planRejected = [bool]$report.evidence.schemaPolicy.narrow.planRejected
            }
        }
        $uiSummary = [ordered]@{
            schemaVersion = 1
            gate = $report.gate
            status = $report.status
            generatedAtUtc = $report.finishedAtUtc
            source = $ReportPath.Replace("\", "/")
            quality = [ordered]@{
                blockedOnFailure = [bool]$report.quality.blockedOnFailure
                reopenedAfterPass = [bool]$report.quality.reopenedAfterPass
            }
            schemaPolicy = [ordered]@{
                rename = $renamePolicy
                drop = $dropPolicy
                narrow = $narrowPolicy
            }
        }
        $uiSummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $uiSummaryFile -Encoding UTF8
    } catch {
        Write-Warning "Unable to refresh frontend acceptance summary: $($_.Exception.Message)"
    }
}

Write-Output ($report | ConvertTo-Json -Depth 20 -Compress)
if ($report.status -ne "passed") { exit 1 }

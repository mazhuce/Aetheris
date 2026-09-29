param(
    [string]$WorkflowContainer = "aetheris-workflow-service-1",
    [string]$PostgresContainer = "aetheris-postgres-1",
    [string]$LakehouseNetwork = "aetheris-r1-lakehouse_default",
    [string]$FlinkEndpoint = "http://flink-sql-gateway:8083",
    [string]$ReportPath = "artifacts/r3-lifecycle/r3-managed-stream-report.json",
    [int]$TimeoutSeconds = 120,
    [switch]$SkipNetworkFaultInjection
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Invoke-Docker {
    param([string[]]$Arguments)
    $lines = @(& docker @Arguments 2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments[0]) failed: $($lines -join ' ')" }
    return $lines
}

function New-IdentityHeader {
    $lines = Invoke-Docker @("inspect", $WorkflowContainer, "--format", "{{range .Config.Env}}{{println .}}{{end}}")
    $line = $lines | Where-Object { $_ -like "AETHERIS_INTERNAL_TOKEN=*" } | Select-Object -First 1
    if (-not $line) { throw "workflow internal token is not configured" }
    $token = ([string]$line).Substring("AETHERIS_INTERNAL_TOKEN=".Length).Trim()
    $payload = "-|admin|$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($token))
    $signature = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($encoded)) | ForEach-Object { $_.ToString("x2") })
    return "$encoded.$signature"
}

function Invoke-Workflow {
    param([string]$Identity, [string]$Method, [string]$Path, [object]$Body = $null)
    $command = 'curl -sS -X ' + $Method + ' -H "X-Aetheris-Identity: ' + $Identity + '"'
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress -Depth 12
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
        $command += ' -H "Content-Type: application/json" --data-binary @-'
        $command = "printf '%s' '$encoded' | base64 -d | $command"
    }
    $command += ' -w "HTTP_CODE:%{http_code}" http://127.0.0.1:8088' + $Path
    $raw = (Invoke-Docker @("exec", $WorkflowContainer, "sh", "-lc", $command) | Out-String).Trim()
    if ($raw -notmatch 'HTTP_CODE:(\d{3})$') { throw "workflow API did not return HTTP code: $Path" }
    $code = [int]$Matches[1]
    $bodyText = $raw.Substring(0, $raw.LastIndexOf("HTTP_CODE:")).Trim()
    $data = if ($bodyText) { $bodyText | ConvertFrom-Json } else { $null }
    return @{ code = $code; data = $data }
}

function Read-Attempt {
    param([long]$InstanceId)
    $sql = "SELECT row_to_json(x) FROM (SELECT ea.id, ea.status, ea.remote_handle, ea.flink_job_id, ea.flink_latest_checkpoint_id, ea.flink_lifecycle_state, ea.flink_savepoint_path, ea.flink_upgrade_definition_digest, ea.lease_expires_at, ti.status AS instance_status, ti.lease_expires_at AS instance_lease FROM workflow.execution_attempt ea JOIN workflow.task_instance ti ON ti.id=ea.instance_id WHERE ea.instance_id=$InstanceId ORDER BY ea.id DESC LIMIT 1) x"
    $raw = (Invoke-Docker @("exec", $PostgresContainer, "psql", "-U", "aetheris", "-d", "aetheris", "-Atc", $sql) | Out-String).Trim()
    if (-not $raw) { return $null }
    return $raw | ConvertFrom-Json
}

function Read-TaskDefinition {
    param([long]$TaskId)
    $sql = "SELECT row_to_json(x) FROM (SELECT definition_version, task_sql FROM workflow.task WHERE id=$TaskId) x"
    $raw = (Invoke-Docker @("exec", $PostgresContainer, "psql", "-U", "aetheris", "-d", "aetheris", "-Atc", $sql) | Out-String).Trim()
    if (-not $raw) { throw "task definition $TaskId was not found" }
    return $raw | ConvertFrom-Json
}

function Wait-Attempt {
    param([long]$InstanceId, [scriptblock]$Predicate, [string]$Description)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $row = Read-Attempt $InstanceId
        if ($null -ne $row -and (& $Predicate $row)) { return $row }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description; last state: $($row | ConvertTo-Json -Compress)"
}

$report = [ordered]@{
    schemaVersion = 2
    gate = "R3-managed-flink-stream-runtime-gate"
    generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
    status = "failed"
    configuration = [ordered]@{
        flinkEndpoint = $FlinkEndpoint
        maxActiveStreams = 1
        networkFaultInjection = (-not $SkipNetworkFaultInjection)
    }
    phases = [ordered]@{}
    cleanup = [ordered]@{}
}
$taskIds = [Collections.Generic.List[long]]::new()
$instanceId = $null
$networkFaultActive = $false
try {
    if ($TimeoutSeconds -lt 30) { throw "TimeoutSeconds must be at least 30" }
    $identity = New-IdentityHeader
    $suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $sql = @"
SET 'execution.checkpointing.interval' = '1 s';
SET 'parallelism.default' = '1';
CREATE TEMPORARY TABLE r3_acceptance_source (id BIGINT) WITH ('connector'='datagen','rows-per-second'='1');
CREATE TEMPORARY TABLE r3_acceptance_sink (id BIGINT) WITH ('connector'='blackhole');
INSERT INTO r3_acceptance_sink SELECT id FROM r3_acceptance_source
"@
    $definition = @{
        name = "r3-managed-$suffix"; taskType = "flink"; flinkMode = "streaming"
        flinkEndpoint = $FlinkEndpoint; taskSql = $sql
        timeoutSeconds = 1; maxRetries = 0; status = "enabled"
    }
    $created = Invoke-Workflow $identity "POST" "/api/workflow/tasks" $definition
    if ($created.code -ne 200 -or -not $created.data.task.id) { throw "create failed: $($created | ConvertTo-Json -Compress -Depth 8)" }
    $taskId = [long]$created.data.task.id
    $taskIds.Add($taskId)
    $started = Invoke-Workflow $identity "POST" "/api/workflow/tasks/$taskId/run" @{ bizDate = "2026-09-23" }
    if ($started.code -ne 200 -or -not $started.data.id) { throw "run failed: $($started | ConvertTo-Json -Compress -Depth 8)" }
    $instanceId = [long]$started.data.id
    $running = Wait-Attempt $instanceId { param($row) $row.flink_job_id -match '^[0-9a-f]{32}$' -and $row.status -eq "running" } "automatic Job ID binding"
    $report.phases.submit = @{
        taskId = $taskId; instanceId = $instanceId; attemptId = $running.id
        remoteHandle = $running.remote_handle; jobId = $running.flink_job_id
        instanceStatus = $running.instance_status
    }
    if ($running.instance_status -ne "running") { throw "Gateway submission was misclassified as terminal" }
    $checkpoint = Wait-Attempt $instanceId { param($row) $null -ne $row.flink_latest_checkpoint_id -and $row.flink_latest_checkpoint_id -gt 0 } "completed checkpoint"
    $report.phases.checkpoint = @{ id = $checkpoint.flink_latest_checkpoint_id; jobId = $checkpoint.flink_job_id }
    $firstLease = [DateTimeOffset]::Parse([string]$checkpoint.lease_expires_at)
    Start-Sleep -Seconds 8
    $renewed = Read-Attempt $instanceId
    $secondLease = [DateTimeOffset]::Parse([string]$renewed.lease_expires_at)
    if ($secondLease -le $firstLease) { throw "stream lease was not renewed" }
    $report.phases.leaseRenewal = @{ first = $firstLease.ToString("o"); second = $secondLease.ToString("o") }

    $duplicate = Invoke-Workflow $identity "POST" "/api/workflow/tasks/$taskId/run" @{ bizDate = "2026-09-23" }
    if ($duplicate.code -ne 409) { throw "duplicate claim expected HTTP 409, got $($duplicate.code)" }
    $definition.name = "r3-managed-capacity-$suffix"
    $secondTask = Invoke-Workflow $identity "POST" "/api/workflow/tasks" $definition
    if ($secondTask.code -ne 200 -or -not $secondTask.data.task.id) { throw "second task create failed" }
    $secondTaskId = [long]$secondTask.data.task.id
    $taskIds.Add($secondTaskId)
    $capacity = Invoke-Workflow $identity "POST" "/api/workflow/tasks/$secondTaskId/run" @{ bizDate = "2026-09-23" }
    if ($capacity.code -ne 409) { throw "capacity admission expected HTTP 409, got $($capacity.code)" }
    $report.phases.admission = @{ duplicateHttp = $duplicate.code; capacityHttp = $capacity.code }

    $attemptPath = "/api/workflow/tasks/instances/$instanceId/attempts/$($running.id)"
    $pause = Invoke-Workflow $identity "POST" "$attemptPath/flink-pause" @{ targetDirectory = "s3://warehouse/savepoints/r3-managed-$suffix" }
    if ($pause.code -ne 200 -or -not $pause.data.requestId) { throw "pause request failed: $($pause | ConvertTo-Json -Compress -Depth 8)" }
    $pauseRequestId = [string]$pause.data.requestId
    $pauseResult = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $pauseResult = Invoke-Workflow $identity "GET" "$attemptPath/flink-savepoint/$pauseRequestId"
        if ($pauseResult.code -eq 200 -and $pauseResult.data.status -eq "COMPLETED" -and $pauseResult.data.location) { break }
        if ($pauseResult.data.status -in @("FAILED", "CANCELED", "CANCELLED")) { throw "pause savepoint failed" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if (-not $pauseResult.data.location) { throw "pause savepoint timed out" }
    $paused = Wait-Attempt $instanceId { param($row) $row.flink_lifecycle_state -eq "paused" -and $row.flink_savepoint_path } "paused state"
    $report.phases.pause = @{ oldJobId = $running.flink_job_id; requestId = $pauseRequestId; savepoint = $paused.flink_savepoint_path; state = $paused.flink_lifecycle_state }
    $resume = Invoke-Workflow $identity "POST" "$attemptPath/flink-resume"
    if ($resume.code -ne 200) { throw "resume failed: $($resume | ConvertTo-Json -Compress -Depth 8)" }
    $resumed = Wait-Attempt $instanceId { param($row) $row.flink_job_id -match '^[0-9a-f]{32}$' -and $row.flink_job_id -ne $running.flink_job_id -and $row.flink_lifecycle_state -eq "running" } "resumed Job ID"
    $resumedCheckpoint = Wait-Attempt $instanceId { param($row) $row.flink_job_id -eq $resumed.flink_job_id -and $row.flink_latest_checkpoint_id -gt 0 } "resumed checkpoint"
    $preUpgradeDefinition = Read-TaskDefinition $taskId
    if ($preUpgradeDefinition.definition_version -ne 1 -or $preUpgradeDefinition.task_sql -ne $sql) {
        throw "plain resume changed the task definition"
    }
    $report.phases.resume = @{ jobId = $resumed.flink_job_id; checkpoint = $resumedCheckpoint.flink_latest_checkpoint_id; fromSavepoint = $paused.flink_savepoint_path }

    $replacementSql = $sql.Replace("SELECT id FROM r3_acceptance_source", "SELECT id + 0 AS id FROM r3_acceptance_source")
    $upgrade = Invoke-Workflow $identity "POST" "$attemptPath/flink-upgrade" @{
        targetDirectory = "s3://warehouse/savepoints/r3-managed-upgrade-$suffix"
        replacementSql = $replacementSql
    }
    if ($upgrade.code -ne 202 -or -not $upgrade.data.requestId) { throw "upgrade request failed: $($upgrade | ConvertTo-Json -Compress -Depth 8)" }
    $upgradeRequestId = [string]$upgrade.data.requestId
    $upgradeResult = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $upgradeResult = Invoke-Workflow $identity "GET" "$attemptPath/flink-savepoint/$upgradeRequestId"
        if ($upgradeResult.code -eq 200 -and $upgradeResult.data.status -eq "COMPLETED" -and $upgradeResult.data.location) { break }
        if ($upgradeResult.data.status -in @("FAILED", "CANCELED", "CANCELLED")) { throw "upgrade savepoint failed" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if (-not $upgradeResult.data.location) { throw "upgrade savepoint timed out" }
    $stopped = Wait-Attempt $instanceId { param($row) $row.flink_lifecycle_state -eq "stopped" -and $row.flink_upgrade_definition_digest } "stopped upgrade state"
    $upgradeResume = Invoke-Workflow $identity "POST" "$attemptPath/flink-resume"
    if ($upgradeResume.code -ne 200 -or -not $upgradeResume.data.upgraded) { throw "upgrade resume failed: $($upgradeResume | ConvertTo-Json -Compress -Depth 8)" }
    $upgraded = Wait-Attempt $instanceId { param($row) $row.flink_job_id -match '^[0-9a-f]{32}$' -and $row.flink_job_id -ne $resumed.flink_job_id } "replacement Job ID"
    $upgradedCheckpoint = Wait-Attempt $instanceId { param($row) $row.flink_job_id -eq $upgraded.flink_job_id -and $row.flink_latest_checkpoint_id -gt 0 } "replacement checkpoint"
    $publishedDefinition = Read-TaskDefinition $taskId
    if ($publishedDefinition.definition_version -ne 2 -or $publishedDefinition.task_sql -ne $replacementSql) {
        throw "replacement SQL was not published as task definition version 2"
    }
    $report.phases.upgrade = @{
        oldJobId = $resumed.flink_job_id; jobId = $upgraded.flink_job_id
        requestId = $upgradeRequestId; savepoint = $stopped.flink_savepoint_path
        definitionDigest = $stopped.flink_upgrade_definition_digest
        definitionVersion = $publishedDefinition.definition_version
        replacementPublished = $true
        checkpoint = $upgradedCheckpoint.flink_latest_checkpoint_id
    }

    if (-not $SkipNetworkFaultInjection) {
        $networks = (Invoke-Docker @("inspect", $WorkflowContainer, "--format", "{{json .NetworkSettings.Networks}}") | Out-String).Trim() | ConvertFrom-Json
        if (-not ($networks.PSObject.Properties.Name -contains $LakehouseNetwork)) {
            throw "workflow container is not attached to expected isolated network $LakehouseNetwork"
        }
        $null = Invoke-Docker @("network", "disconnect", $LakehouseNetwork, $WorkflowContainer)
        $networkFaultActive = $true
        $unknown = Wait-Attempt $instanceId { param($row) $row.status -eq "unknown" -and $row.instance_status -eq "unknown" } "network outage to be marked unknown"
        if ($unknown.flink_job_id -ne $upgraded.flink_job_id) { throw "unknown state lost the persisted Job ID" }
        $report.phases.networkUnknown = @{
            attemptStatus = $unknown.status; instanceStatus = $unknown.instance_status
            jobId = $unknown.flink_job_id; wasNotSuccess = $unknown.status -ne "succeeded"
        }
        $null = Invoke-Docker @("network", "connect", $LakehouseNetwork, $WorkflowContainer)
        $networkFaultActive = $false
        $recovered = Wait-Attempt $instanceId { param($row) $row.status -eq "running" -and $row.instance_status -eq "running" -and $row.flink_job_id -eq $upgraded.flink_job_id } "automatic network recovery"
        $report.phases.networkRecovery = @{ status = $recovered.status; jobId = $recovered.flink_job_id }
    } else {
        $report.phases.networkUnknown = @{ skipped = $true }
    }

    $cancel = Invoke-Workflow $identity "POST" "/api/workflow/tasks/instances/$instanceId/cancel"
    if ($cancel.code -ne 200) { throw "cancel failed: $($cancel | ConvertTo-Json -Compress -Depth 8)" }
    $terminal = Wait-Attempt $instanceId { param($row) $row.status -eq "cancelled" -and $row.instance_status -eq "cancelled" } "JobManager cancellation reconciliation"
    $report.phases.cancel = @{ attemptStatus = $terminal.status; instanceStatus = $terminal.instance_status; jobId = $terminal.flink_job_id }
    $report.status = "passed"
} catch {
    $report.error = $_.Exception.Message
} finally {
    try {
        if ($networkFaultActive) {
            $null = Invoke-Docker @("network", "connect", $LakehouseNetwork, $WorkflowContainer)
            $networkFaultActive = $false
        }
        if ($null -ne $instanceId) {
            $row = Read-Attempt $instanceId
            if ($row -and $row.status -notin @("cancelled", "failed", "succeeded") -and
                    $row.flink_lifecycle_state -notin @("paused", "stopped") -and $row.flink_job_id) {
                $identity = New-IdentityHeader
                $null = Invoke-Workflow $identity "POST" "/api/workflow/tasks/instances/$instanceId/cancel"
                $null = Wait-Attempt $instanceId { param($current) $current.status -in @("cancelled", "failed") } "cleanup cancellation"
            }
        }
        foreach ($id in $taskIds) {
            $identity = New-IdentityHeader
            $removed = Invoke-Workflow $identity "DELETE" "/api/workflow/tasks/$id"
            if ($removed.code -notin @(200, 204)) { throw "delete task $id returned HTTP $($removed.code)" }
        }
        $report.cleanup.tasksDeleted = $taskIds.Count
        $report.cleanup.completed = $true
    } catch {
        $report.cleanup.completed = $false
        $report.cleanup.error = $_.Exception.Message
        $report.status = "failed"
    }
    $full = Join-Path (Get-Location) $ReportPath
    New-Item -ItemType Directory -Force -Path (Split-Path $full) | Out-Null
    $report | ConvertTo-Json -Depth 20 | Set-Content -Path $full -Encoding utf8
}
$report | ConvertTo-Json -Depth 20 -Compress
if ($report.status -ne "passed") { exit 1 }

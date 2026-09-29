[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r3-lifecycle",
    [string]$GatewayEndpoint = "http://127.0.0.1:18083",
    [string]$JobManagerEndpoint = "http://127.0.0.1:18081",
    [string]$RecoveryJobManagerEndpoint = "",
    [string]$ComposeFilePath = "infrastructure/lakehouse/docker-compose.r1.yml",
    [string]$ComposeOverridePath = "",
    [string]$ProjectName = "aetheris-r1-lakehouse",
    [string]$SourceContainer = "aetheris-r1-lakehouse-source-postgres-1",
    [string]$NetworkName = "",
    [int]$TimeoutSeconds = 180,
    [switch]$ResetSource,
    [switch]$SkipNetworkFaultInjection,
    [switch]$SkipFaultInjection,
    [string]$ReportPath = "artifacts/r3-lifecycle/r3-flink-lifecycle-report.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot $EnvFilePath
$fixture = Join-Path $repoRoot $FixtureOutput
$compose = Join-Path $repoRoot $ComposeFilePath
$composeOverride = if ($ComposeOverridePath) { Join-Path $repoRoot $ComposeOverridePath } else { $null }
$reportFile = Join-Path $repoRoot $ReportPath
$gateway = $GatewayEndpoint.TrimEnd('/')
$jobManager = $JobManagerEndpoint.TrimEnd('/')
$recoveryJobManager = if ($RecoveryJobManagerEndpoint) { $RecoveryJobManagerEndpoint.TrimEnd('/') } else { $jobManager }
$composeArgs = @("compose", "--project-name", $ProjectName, "--profile", "r1", "-f", $compose)
if ($composeOverride) { $composeArgs += @("-f", $composeOverride) }
$composeArgs += @("--env-file", $envFile)

foreach ($required in @($envFile, $compose, $composeOverride)) {
    if (-not $required) { continue }
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "R3 required file is missing: $required" }
}
if ($TimeoutSeconds -lt 30) { throw "TimeoutSeconds must be at least 30" }

function Invoke-Compose {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & docker @composeArgs @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exitCode -ne 0) { throw "docker compose $($Arguments -join ' ') failed: $($output -join ' ')" }
    return @($output)
}

function Invoke-Docker {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & docker @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exitCode -ne 0) { throw "docker $($Arguments -join ' ') failed: $($output -join ' ')" }
    return @($output)
}

function Read-OperationStatus {
    param([object]$Body)
    if ($null -eq $Body) { return "" }
    if ($Body.status -and $Body.status.type) { return ([string]$Body.status.type).ToUpperInvariant() }
    if ($Body.status) { return ([string]$Body.status).ToUpperInvariant() }
    if ($Body.type) { return ([string]$Body.type).ToUpperInvariant() }
    return ""
}

function Read-JobOverview {
    param([string]$Endpoint = $jobManager)
    $body = Invoke-RestMethod -Method Get -Uri "$Endpoint/jobs/overview"
    return @($body.jobs | Where-Object { $_.name -match "r3_orders_lifecycle" } |
        Sort-Object { [DateTime]$_.'start-time' } -Descending)
}

function Wait-JobRunning {
    param([string]$PreviousJobId = "")
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $jobs = @(Read-JobOverview | Where-Object { $_.state -in @("RUNNING", "RESTARTING", "CREATED", "INITIALIZING") })
            $candidate = $jobs | Where-Object { -not $PreviousJobId -or $_.jid -ne $PreviousJobId } | Select-Object -First 1
            if ($candidate) {
                $state = ([string]$candidate.state).ToUpperInvariant()
                if ($state -eq "RUNNING") { return [ordered]@{ jobId = [string]$candidate.jid; state = $state; name = [string]$candidate.name } }
            }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "R3 streaming Flink Job did not reach RUNNING before timeout"
}

function Wait-Checkpoint {
    param([string]$JobId, [long]$MinimumId = -1)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $body = Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$JobId/checkpoints"
            $completed = $body.latest.completed
            if ($completed -and $null -ne $completed.id -and [long]$completed.id -gt $MinimumId) {
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
    throw "Flink Job $JobId did not complete a checkpoint before timeout"
}

function Wait-JobRunningById {
    param([string]$JobId)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $job = Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$JobId"
            if (([string]$job.state).ToUpperInvariant() -eq "RUNNING") {
                return [ordered]@{ jobId = $JobId; state = "RUNNING"; name = [string]$job.name }
            }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink Job $JobId did not return to RUNNING before timeout"
}

function Wait-JobStable {
    param([string]$JobId, [int]$StableSeconds = 5)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $stableSince = $null
    do {
        try {
            $job = Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$JobId"
            $state = ([string]$job.state).ToUpperInvariant()
            $vertices = @($job.vertices)
            $allRunning = $vertices.Count -gt 0 -and (@($vertices | Where-Object { ([string]$_.status).ToUpperInvariant() -ne "RUNNING" }).Count -eq 0)
            if ($state -eq "RUNNING" -and $allRunning) {
                if ($null -eq $stableSince) { $stableSince = Get-Date }
                if (((Get-Date) - $stableSince).TotalSeconds -ge $StableSeconds) {
                    return [ordered]@{ jobId = $JobId; state = "RUNNING"; stableSeconds = $StableSeconds; vertices = $vertices.Count }
                }
            } else {
                $stableSince = $null
            }
        } catch { $stableSince = $null }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Flink Job $JobId did not remain RUNNING with all vertices active for $StableSeconds seconds"
}

function Wait-Endpoint {
    param([string]$Endpoint = $gateway)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $info = Invoke-RestMethod -Method Get -Uri "$Endpoint/v1/info"
            if ($info) { return $info }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink SQL Gateway did not become reachable before timeout"
}

function Wait-JobManagerEndpoint {
    param([string]$Endpoint)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $overview = Invoke-RestMethod -Method Get -Uri "$Endpoint/overview"
            if ($overview) { return $overview }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink JobManager endpoint did not become reachable before timeout"
}

function Wait-SourceContainerReady {
    param([string]$Container = $SourceContainer)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $running = ((Invoke-Docker @("inspect", "-f", "{{.State.Running}}", $Container)) | Select-Object -Last 1).ToString().Trim()
            if ($running -eq "true") {
                Invoke-Docker @("exec", $Container, "sh", "-lc", 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"') | Out-Null
                return
            }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "R3 source container did not become ready before fixture reset"
}

function Start-StreamingSql {
    param([string]$SqlFile)
    $output = & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/r1-submit-flink-sql.ps1") `
        -GatewayEndpoint $GatewayEndpoint -JobManagerEndpoint $JobManagerEndpoint -SqlFile $SqlFile `
        -EnvFile $envFile -KeepInsertRunning -TimeoutSeconds $TimeoutSeconds | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw "Flink SQL Gateway streaming submission failed: $output" }
    try { $submission = $output | ConvertFrom-Json } catch { throw "Flink streaming submission did not return JSON evidence: $output" }
    if ($submission.status -ne "running" -or -not $submission.session -or -not $submission.operation) {
        throw "Flink streaming submission returned incomplete handle evidence"
    }
    return $submission
}

function Trigger-Savepoint {
    param([string]$JobId, [string]$Label)
    $target = "s3://warehouse/savepoints/r3-$Label"
    $body = @{ "targetDirectory" = $target; "drain" = $false; "formatType" = "CANONICAL" } |
        ConvertTo-Json -Compress
    $request = Invoke-RestMethod -Method Post -Uri "$jobManager/jobs/$JobId/stop" `
        -ContentType "application/json" -Body $body
    $requestId = [string]$request.'request-id'
    if (-not $requestId) { $requestId = [string]$request.requestId }
    if (-not $requestId) { throw "Flink stop-with-savepoint did not return request id" }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $statusBody = Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$JobId/savepoints/$requestId"
            $status = if ($statusBody.status.id) { ([string]$statusBody.status.id).ToUpperInvariant() } else { ([string]$statusBody.status).ToUpperInvariant() }
            $location = [string]$statusBody.operation.location
            if ($status -eq "COMPLETED" -and $location) {
                return [ordered]@{ requestId = $requestId; status = $status; location = $location; target = $target }
            }
            if ($status -eq "COMPLETED" -and -not $location -and $statusBody.operation.'failure-cause') {
                throw "Flink savepoint $requestId completed without a location: $($statusBody.operation.'failure-cause'.class)"
            }
            if ($status -in @("FAILED", "CANCELED", "CANCELLED")) {
                throw "Flink savepoint $requestId ended with status $status"
            }
        } catch {
            if ($_.Exception.Message -match "ended with status|completed without a location") { throw }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink savepoint $requestId did not complete before timeout"
}

function Wait-JobGone {
    param([string]$JobId)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $state = ([string](Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$JobId").state).ToUpperInvariant()
            if ($state -in @("FINISHED", "CANCELED", "SUSPENDED")) { return $state }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Flink Job $JobId did not reach a stopped state after savepoint"
}

function Stop-RemoteJob {
    param([string]$JobId, [string]$Session, [string]$Operation)
    if ($JobId) {
        try {
            $payload = '{"state":"CANCELED"}'
            Invoke-RestMethod -Method Patch -Uri "$jobManager/jobs/$JobId" `
                -ContentType "application/json" -Body $payload | Out-Null
        } catch { }
    }
    if ($Session -and $Operation) {
        try { Invoke-RestMethod -Method Post -Uri "$gateway/v1/sessions/$Session/operations/$Operation/cancel" -ContentType "application/json" -Body "{}" | Out-Null } catch { }
        try { Invoke-RestMethod -Method Delete -Uri "$gateway/v1/sessions/$Session/operations/$Operation/close" | Out-Null } catch { }
    }
    if ($Session) { try { Invoke-RestMethod -Method Delete -Uri "$gateway/v1/sessions/$Session" | Out-Null } catch { } }
}

function Sha256Text {
    param([string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return ([Security.Cryptography.SHA256]::Create().ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join ""
}

$result = [ordered]@{
    schemaVersion = 1
    gate = "R3-flink-lifecycle-runtime-gate"
    status = "failed"
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    fixture = "aetheris-r3-flink-lifecycle-v1"
    phases = [ordered]@{}
    reconciliation = [ordered]@{
        attempted = $false
        outageInjected = $false
        remoteUnavailableObserved = $false
        unknownWasNotSuccess = $false
        networkOutageInjected = $false
        networkRecoveryObserved = $false
        mode = $null
    }
    cleanup = [ordered]@{ completed = $false }
    error = $null
}
$sessionHandles = [System.Collections.Generic.List[object]]::new()
$currentJobId = $null
$jobManagerStopped = $false
$taskManagerDisconnected = $false
$taskManagerNetwork = $null
$taskManagerContainer = $null
$baseSqlFile = Join-Path $fixture "flink/rendered/004_r3_flink_lifecycle.sql"
$sourceSqlFile = Join-Path $fixture "postgres/001_source_orders.sql"
$resumeSqlFile = Join-Path $fixture "flink/rendered/004_r3_flink_lifecycle-resume.sql"
$upgradeSqlFile = Join-Path $fixture "flink/rendered/004_r3_flink_lifecycle-upgrade.sql"

try {
    if (-not (Test-Path -LiteralPath (Join-Path $fixture "manifest.json") -PathType Leaf)) {
        & py -3.12 (Join-Path $repoRoot "scripts/r1-fixture.py") generate --output $fixture
        if ($LASTEXITCODE -ne 0) { throw "R3 fixture generation failed" }
    }
    if (-not (Test-Path -LiteralPath $baseSqlFile -PathType Leaf)) {
        & py -3.12 (Join-Path $repoRoot "scripts/r1-fixture.py") render --fixture $fixture --env-file $envFile
        if ($LASTEXITCODE -ne 0) { throw "R3 fixture SQL rendering failed" }
    }
    if ($ResetSource) {
        Wait-SourceContainerReady
        if (-not $PSCmdlet.ShouldProcess($SourceContainer, "reset the isolated PostgreSQL source fixture")) { throw "ResetSource was not confirmed" }
        $sourceSql = Get-Content -Raw -LiteralPath $sourceSqlFile
        $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        try { $sourceSql | & docker exec -i $SourceContainer sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $previous }
        if ($code -ne 0) { throw "R3 source fixture reset failed" }
    }
    Wait-Endpoint | Out-Null

    $baseSubmission = Start-StreamingSql $baseSqlFile
    $sessionHandles.Add($baseSubmission)
    $baseJob = Wait-JobRunning
    $currentJobId = $baseJob.jobId
    $result.phases.submit = [ordered]@{ status = "passed"; session = $baseSubmission.session; operation = $baseSubmission.operation; jobId = $currentJobId; jobState = $baseJob.state }
    $result.phases.checkpoint = Wait-Checkpoint $currentJobId

    Invoke-Compose @("restart", "flink-taskmanager") | Out-Null
    $afterTaskManager = Wait-JobRunningById $currentJobId
    $stableAfterTaskManager = Wait-JobStable $currentJobId
    $result.phases.taskManagerRestart = [ordered]@{ status = "passed"; jobId = $currentJobId; stable = $stableAfterTaskManager; checkpoint = Wait-Checkpoint $currentJobId ([long]$result.phases.checkpoint.id) }
    Wait-JobStable $currentJobId | Out-Null

    $pauseSavepoint = Trigger-Savepoint $currentJobId "pause"
    $pauseTerminalState = Wait-JobGone $currentJobId
    $result.phases.pause = [ordered]@{ status = "passed"; jobId = $currentJobId; savepoint = $pauseSavepoint; terminalState = $pauseTerminalState }

    $resumeSql = "SET 'execution.savepoint.path' = '$($pauseSavepoint.location.Replace("'", "''"))';`n" + (Get-Content -Raw -LiteralPath $baseSqlFile)
    Set-Content -LiteralPath $resumeSqlFile -Value $resumeSql -Encoding UTF8
    $resumeSubmission = Start-StreamingSql $resumeSqlFile
    $sessionHandles.Add($resumeSubmission)
    $resumedJob = Wait-JobRunning -PreviousJobId $currentJobId
    $currentJobId = $resumedJob.jobId
    $resumeStable = Wait-JobStable $currentJobId
    $result.phases.resume = [ordered]@{ status = "passed"; session = $resumeSubmission.session; operation = $resumeSubmission.operation; savepoint = $pauseSavepoint.location; jobId = $currentJobId; stable = $resumeStable; checkpoint = Wait-Checkpoint $currentJobId }

    $upgradeSql = "-- R3 replacement SQL v2`n" + (Get-Content -Raw -LiteralPath $baseSqlFile)
    $upgradeSql = "SET 'execution.savepoint.path' = '__SAVEPOINT__';`n" + $upgradeSql
    Set-Content -LiteralPath $upgradeSqlFile -Value $upgradeSql -Encoding UTF8
    $upgradeSavepoint = Trigger-Savepoint $currentJobId "upgrade"
    $upgradeTerminalState = Wait-JobGone $currentJobId
    $upgradeText = (Get-Content -Raw -LiteralPath $upgradeSqlFile).Replace("__SAVEPOINT__", $upgradeSavepoint.location.Replace("'", "''"))
    Set-Content -LiteralPath $upgradeSqlFile -Value $upgradeText -Encoding UTF8
    $upgradeSubmission = Start-StreamingSql $upgradeSqlFile
    $sessionHandles.Add($upgradeSubmission)
    $upgradedJob = Wait-JobRunning -PreviousJobId $currentJobId
    $currentJobId = $upgradedJob.jobId
    $upgradeStable = Wait-JobStable $currentJobId
    $result.phases.upgrade = [ordered]@{ status = "passed"; oldJobId = $result.phases.resume.jobId; oldTerminalState = $upgradeTerminalState; session = $upgradeSubmission.session; operation = $upgradeSubmission.operation; savepoint = $upgradeSavepoint.location; replacementSqlSha256 = Sha256Text $upgradeText; jobId = $currentJobId; stable = $upgradeStable; checkpoint = Wait-Checkpoint $currentJobId }

    if (-not $SkipNetworkFaultInjection) {
        $network = if ($NetworkName) { $NetworkName } else { "$ProjectName`_default" }
        $tmOutput = @(Invoke-Compose @("ps", "-q", "flink-taskmanager") | Where-Object { $_ -and $_.ToString().Trim() })
        $tmContainer = [string]($tmOutput | Select-Object -Last 1)
        if (-not $tmContainer -or $tmContainer.Length -lt 12) { throw "R3 could not resolve the isolated TaskManager container" }
        Invoke-Docker @("network", "inspect", $network) | Out-Null
        $taskManagerNetwork = $network
        $beforeNetworkCheckpoint = [long]$result.phases.upgrade.checkpoint.id
        Invoke-Docker @("network", "disconnect", $network, $tmContainer) | Out-Null
        $taskManagerDisconnected = $true
        $result.reconciliation.networkOutageInjected = $true
        Start-Sleep -Seconds 8
        $duringNetwork = [ordered]@{ jobId = $currentJobId; taskManagerContainer = $tmContainer; network = $network; observed = $true }
        Invoke-Docker @("network", "connect", $network, $tmContainer) | Out-Null
        $taskManagerDisconnected = $false
        $networkRunning = Wait-JobRunningById $currentJobId
        $networkStable = Wait-JobStable $currentJobId
        $networkCheckpoint = Wait-Checkpoint $currentJobId $beforeNetworkCheckpoint
        $result.reconciliation.networkRecoveryObserved = $true
        $result.phases.networkInterruption = [ordered]@{
            status = "passed"
            network = $network
            taskManagerContainer = $tmContainer
            during = $duringNetwork
            recoveredJobId = $networkRunning.jobId
            stable = $networkStable
            checkpoint = $networkCheckpoint
        }
    }

    if (-not $SkipFaultInjection) {
        $result.reconciliation.attempted = $true
        $result.reconciliation.outageInjected = $true
        Invoke-Compose @("stop", "flink-jobmanager") | Out-Null
        $jobManagerStopped = $true
        $unknownObserved = $false
        try { Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$currentJobId" -TimeoutSec 5 | Out-Null } catch { $unknownObserved = $true }
        $result.reconciliation.remoteUnavailableObserved = $unknownObserved
        $result.reconciliation.unknownWasNotSuccess = $unknownObserved
        if (-not $unknownObserved) { throw "JobManager outage probe unexpectedly returned a remote success response" }
        Invoke-Compose @("start", "flink-jobmanager") | Out-Null
        $jobManagerStopped = $false
        Wait-JobManagerEndpoint -Endpoint $recoveryJobManager | Out-Null
        $recovered = $false
        try { $recovered = (@(Read-JobOverview -Endpoint $recoveryJobManager | Where-Object { $_.jid -eq $currentJobId -and $_.state -eq "RUNNING" }).Count -gt 0) } catch { $recovered = $false }
        $result.reconciliation.mode = if ($recovered) { "auto_reconciled" } else { "manual_review_required" }
        if (-not $unknownObserved) { throw "unknown reconciliation evidence was not recorded" }
    }
    $result.status = "passed"
} catch {
    $result.error = $_.Exception.Message
} finally {
    if ($taskManagerDisconnected -and $taskManagerNetwork -and $taskManagerContainer) {
        try { Invoke-Docker @("network", "connect", $taskManagerNetwork, $taskManagerContainer) | Out-Null } catch { }
    }
    if ($jobManagerStopped) { try { Invoke-Compose @("start", "flink-jobmanager") | Out-Null; Wait-Endpoint | Out-Null } catch { } }
    foreach ($handle in $sessionHandles) { Stop-RemoteJob -JobId $currentJobId -Session ([string]$handle.session) -Operation ([string]$handle.operation) }
    $result.cleanup.completed = $true
    $result.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFile) | Out-Null
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportFile -Encoding UTF8
}

Write-Output ($result | ConvertTo-Json -Depth 20 -Compress)
if ($result.status -ne "passed") { exit 1 }

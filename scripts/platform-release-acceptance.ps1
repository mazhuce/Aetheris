[CmdletBinding()]
param(
    [string]$ReportPath = "artifacts/platform-release/platform-release-acceptance-report.json",
    [switch]$OfflineOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$reportFile = if ([IO.Path]::IsPathRooted($ReportPath)) { $ReportPath } else { Join-Path $repoRoot $ReportPath }

function Invoke-PythonCheck {
    param([string]$Name, [string[]]$Arguments)
    $output = @(& py -3 @Arguments 2>&1)
    $code = $LASTEXITCODE
    return [ordered]@{
        name = $Name
        status = if ($code -eq 0) { "passed" } else { "failed" }
        exitCode = $code
        output = (($output | ForEach-Object { $_.ToString() }) -join " ").Substring(0, [Math]::Min(500, (($output | ForEach-Object { $_.ToString() }) -join " ").Length))
    }
}

function Read-ReportStatus {
    param([string]$Name, [string]$RelativePath, [string]$StatusField = "status")
    $path = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return [ordered]@{ name = $Name; path = $RelativePath; status = "missing" }
    }
    try {
        $body = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
        $status = [string]$body.$StatusField
        return [ordered]@{ name = $Name; path = $RelativePath; status = if ($status) { $status } else { "invalid" } }
    } catch {
        return [ordered]@{ name = $Name; path = $RelativePath; status = "invalid" }
    }
}

function Read-FirstReportStatus {
    param([string]$Name, [string[]]$RelativePaths)
    foreach ($relativePath in $RelativePaths) {
        $path = Join-Path $repoRoot $relativePath
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            return Read-ReportStatus $Name $relativePath
        }
    }
    return [ordered]@{
        name = $Name
        path = $RelativePaths[0]
        status = "missing"
        candidates = @($RelativePaths)
    }
}

$offlineChecks = @(
    (Invoke-PythonCheck "r1-release-validator" @("scripts/validate-lakehouse-r1.py", "--mode", "release")),
    (Invoke-PythonCheck "r3-ha-compose-contract" @("scripts/validate-r3-ha.py")),
    (Invoke-PythonCheck "script-regression-suite" @("-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py"))
)
$runtimeReports = @(
    (Read-FirstReportStatus "r1-empty-rebuild" @("artifacts/r1-empty-fixture/acceptance-manifest.json", "artifacts/r1-fixture/acceptance-manifest.json"))
    (Read-ReportStatus "r2-cdc" "artifacts/r1-fixture/r2-cdc-acceptance-report.json"),
    (Read-ReportStatus "r3-managed-lifecycle" "artifacts/r3-lifecycle/r3-managed-stream-report.json"),
    (Read-ReportStatus "r4-event-time-quality" "artifacts/r4-event-time/r4-event-time-quality-report.json"),
    (Read-ReportStatus "r4-r5-control-plane" "artifacts/r4-r5-control-plane/runtime-acceptance-report.json"),
    (Read-ReportStatus "r5-maintenance-runner" "artifacts/r5-maintenance-runner/r5-maintenance-runtime-report.json"),
    (Read-ReportStatus "datasource-nine-local-runtime" "artifacts/datasource-smoke/runtime-acceptance-report.json")
)
$haReport = Read-FirstReportStatus "r3-ha-runtime" @(
    "artifacts/r3-ha/r3-ha-lifecycle-report.json",
    "artifacts/r3-ha-lifecycle/r3-ha-lifecycle-report.json"
)
$externalReport = Read-ReportStatus "datasource-nine-external-runtime" "artifacts/datasource-smoke/external-9-runtime-report.json"

$docker = [ordered]@{ available = $false; status = if ($OfflineOnly) { "skipped" } else { "unavailable" } }
if (-not $OfflineOnly) {
    $dockerOutput = @(& docker version --format "{{.Server.Version}}" 2>&1)
    if ($LASTEXITCODE -eq 0 -and $dockerOutput) {
        $docker.available = $true
        $docker.status = "available"
        $docker.serverVersion = [string]($dockerOutput | Select-Object -Last 1)
    } else {
        $docker.diagnostic = "Docker runtime probe failed; see host service status"
    }
}

$offlineFailed = @($offlineChecks | Where-Object { $_.status -ne "passed" })
$runtimeFailed = @($runtimeReports | Where-Object { $_.status -ne "passed" })
$additionalReports = @($haReport, $externalReport)
$additionalFailed = @($additionalReports | Where-Object { $_.status -eq "failed" })
$manualReview = @($additionalReports | Where-Object { $_.status -notin @("passed", "failed") })
$overallStatus = if ($offlineFailed.Count -gt 0 -or $runtimeFailed.Count -gt 0 -or $additionalFailed.Count -gt 0) { "failed" }
    elseif ($manualReview.Count -gt 0 -or (-not $OfflineOnly -and -not $docker.available)) { "manual_review_required" }
    else { "passed" }

$result = [ordered]@{
    schemaVersion = 1
    gate = "platform-release-acceptance"
    status = $overallStatus
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    offlineChecks = @($offlineChecks)
    runtimeReports = @($runtimeReports)
    additionalGates = [ordered]@{ r3Ha = $haReport; datasourceNine = $externalReport }
    docker = $docker
    manualReviewRequired = @($manualReview | ForEach-Object { $_.name })
    failedAdditionalGates = @($additionalFailed | ForEach-Object { $_.name })
    note = "manual_review_required means a required real-runtime report is missing or Docker is unavailable; it is not a success state"
    finishedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFile) | Out-Null
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportFile -Encoding UTF8
Write-Output ($result | ConvertTo-Json -Depth 20 -Compress)
if ($overallStatus -eq "failed") { exit 1 }

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
        $raw = Get-Content -Raw -LiteralPath $path
        $body = $raw | ConvertFrom-Json
        $status = [string]$body.$StatusField
        $timestamp = $null
        foreach ($field in @("finishedAtUtc", "generatedAtUtc", "generatedAt", "startedAtUtc")) {
            $match = [regex]::Match($raw, '"' + $field + '": "([^"]+)"')
            if ($match.Success) {
                try { $timestamp = ([DateTimeOffset]::Parse($match.Groups[1].Value)).ToUniversalTime().ToString("O") } catch { $timestamp = $match.Groups[1].Value }
                break
            }
        }
        if (-not $timestamp) {
            $phaseMatches = [regex]::Matches($raw, '"finishedAt": "([^"]+)"')
            $parsed = @($phaseMatches | ForEach-Object { try { [DateTimeOffset]::Parse($_.Groups[1].Value) } catch { $null } } | Where-Object { $_ })
            if ($parsed.Count -gt 0) { $timestamp = ($parsed | Sort-Object | Select-Object -Last 1).ToUniversalTime().ToString("O") }
        }
        if (-not $timestamp) {
            $startMatch = [regex]::Match($raw, '"startedAt": "([^"]+)"')
            if ($startMatch.Success) {
                try { $timestamp = ([DateTimeOffset]::Parse($startMatch.Groups[1].Value)).ToUniversalTime().ToString("O") } catch { $timestamp = $startMatch.Groups[1].Value }
            }
        }        return [ordered]@{ name = $Name; path = $RelativePath; status = if ($status) { $status } else { "invalid" }; generatedAtUtc = $timestamp }
    } catch {
        return [ordered]@{ name = $Name; path = $RelativePath; status = "invalid" }
    }
}

function Read-LatestReportStatus {
    param([string]$Name, [string]$Directory, [string]$FilePattern, [string[]]$FallbackPaths)
    $directoryPath = Join-Path $repoRoot $Directory
    if (Test-Path -LiteralPath $directoryPath -PathType Container) {
        $files = @(Get-ChildItem -LiteralPath $directoryPath -Filter $FilePattern -File -Recurse | Sort-Object LastWriteTimeUtc -Descending)
        foreach ($file in $files) {
            try {
                $body = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
                if ([string]$body.status -eq "passed") {
                    $relativePath = [IO.Path]::GetRelativePath($repoRoot, $file.FullName).Replace("\", "/")
                    return Read-ReportStatus $Name $relativePath
                }
            } catch {
                continue
            }
        }
    }
    return Read-FirstReportStatus $Name $FallbackPaths
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
    (Read-LatestReportStatus "r1-empty-rebuild" "artifacts" "acceptance-manifest.json" @("artifacts/r1-empty-fixture/acceptance-manifest.json", "artifacts/r1-fixture/acceptance-manifest.json"))
    (Read-LatestReportStatus "r2-cdc" "artifacts/r2-cdc" "r2-cdc*.json" @("artifacts/r2-cdc/r2-cdc-acceptance-report.json")),
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
# The nine-type local runtime report is the required datasource gate. The
# alternate Hive/SQL Server/Oracle manifest remains supplementary because it
# needs customer-provided external services rather than the local fixture stack.
$datasourceNineGate = Read-ReportStatus "datasource-nine-verified-runtime" "artifacts/datasource-smoke/runtime-acceptance-report.json"
$externalReport = Read-ReportStatus "datasource-nine-external-runtime" "artifacts/datasource-smoke/external-9-runtime-report.json"
$datasourceNineBody = $null
$datasourceNinePath = Join-Path $repoRoot "artifacts/datasource-smoke/runtime-acceptance-report.json"
if (Test-Path -LiteralPath $datasourceNinePath -PathType Leaf) {
    try { $datasourceNineBody = Get-Content -Raw -LiteralPath $datasourceNinePath | ConvertFrom-Json } catch { $datasourceNineBody = $null }
}

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
$additionalReports = @($haReport, $datasourceNineGate)
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
    additionalGates = [ordered]@{ r3Ha = $haReport; datasourceNine = $datasourceNineGate }
    supplementaryGates = [ordered]@{ externalDatasourceManifest = $externalReport }
    docker = $docker
    manualReviewRequired = @($manualReview | ForEach-Object { $_.name })
    failedAdditionalGates = @($additionalFailed | ForEach-Object { $_.name })
    note = "manual_review_required means a required real-runtime report is missing or Docker is unavailable; it is not a success state"
    finishedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFile) | Out-Null
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportFile -Encoding UTF8

# Publish a bounded summary for the acceptance page. It deliberately exposes
# statuses only; the full release report remains the audit artifact.
try {
    $milestoneDefinitions = @(
        @{ code = "R1"; reportName = "r1-empty-rebuild" }
        @{ code = "R2"; reportName = "r2-cdc" }
        @{ code = "R3"; reportName = "r3-managed-lifecycle" }
        @{ code = "R4"; reportName = "r4-event-time-quality" }
        @{ code = "R5"; reportName = "r5-maintenance-runner" }
    )
    $uiMilestones = @($milestoneDefinitions | ForEach-Object {
        $definition = $_
        $matched = @($runtimeReports | Where-Object { $_.name -eq $definition.reportName } | Select-Object -First 1)
        [ordered]@{
            code = $definition.code
            status = if ($matched.Count -eq 1) { [string]$matched[0].status } else { "missing" }
            evidence = if ($matched.Count -eq 1) { [string]$matched[0].path } else { $null }
            evidenceGeneratedAtUtc = if ($matched.Count -eq 1) { [string]$matched[0].generatedAtUtc } else { $null }
        }
    })
    $uiSummary = [ordered]@{
        schemaVersion = 1
        gate = $result.gate
        status = $result.status
        generatedAtUtc = $result.finishedAtUtc
        source = $ReportPath.Replace("\", "/")
        milestones = $uiMilestones
        datasourceNineStatus = [string]$datasourceNineGate.status
        datasourceNineTypes = @($datasourceNineBody.testedTypes | ForEach-Object { [string]$_ })
        datasourceNineFinishedAtUtc = if ($datasourceNineBody.finishedAtUtc -is [datetime]) { $datasourceNineBody.finishedAtUtc.ToUniversalTime().ToString("O") } else { [string]$datasourceNineBody.finishedAtUtc }
        datasourceRuntimeResults = @($datasourceNineBody.results | ForEach-Object { [ordered]@{ type = [string]$_.type; status = [string]$_.status } })
        externalDatasourceStatus = [string]$externalReport.status
        dockerStatus = [string]$docker.status
        manualReviewRequired = @($result.manualReviewRequired)
    }
    $uiSummaryFile = Join-Path $repoRoot "apps/web/public/acceptance/platform-release.json"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $uiSummaryFile) | Out-Null
    $uiSummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $uiSummaryFile -Encoding UTF8
} catch {
    Write-Warning "Unable to refresh frontend release summary: $($_.Exception.Message)"
}
Write-Output ($result | ConvertTo-Json -Depth 20 -Compress)
if ($overallStatus -eq "failed") { exit 1 }

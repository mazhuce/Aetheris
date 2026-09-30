[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$GatewayEndpoint = "http://127.0.0.1:18083",
    [string]$TrinoContainer = "aetheris-r1-lakehouse-trino-1",
    [string]$ProjectName = "aetheris-r1-lakehouse",
    [string]$FlinkJobManagerEndpoint = "http://127.0.0.1:18081",
    [int]$SnapshotTimeoutSeconds = 180,
    [int]$MutationTimeoutSeconds = 180,
    [switch]$ResetSource,
    [switch]$RestartTaskManagerAfterSnapshot,
    [string]$ComposeFilePath = "infrastructure/lakehouse/docker-compose.r1.yml",
    [string]$ReportPath = "artifacts/r2-cdc/r2-cdc-acceptance-report.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $repoRoot $FixtureOutput
$report = Join-Path $repoRoot $ReportPath
$startedAt = [DateTimeOffset]::UtcNow
$result = [ordered]@{
    status = "failed"
    startedAt = $startedAt.ToString("O")
    sources = [ordered]@{}
    passedSources = @()
    failedSources = @()
    error = $null
}

function Invoke-SourceAcceptance {
    param([ValidateSet("postgres", "mysql")][string]$SourceKind)
    $sourceReport = Join-Path $FixtureOutput ("r2-{0}-cdc-report.json" -f $SourceKind)
    $arguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        (Join-Path $repoRoot "scripts/r2-postgres-cdc-smoke.ps1"),
        "-SourceKind", $SourceKind,
        "-EnvFilePath", $EnvFilePath,
        "-FixtureOutput", $FixtureOutput,
        "-GatewayEndpoint", $GatewayEndpoint,
        "-TrinoContainer", $TrinoContainer,
        "-ProjectName", $ProjectName,
        "-FlinkJobManagerEndpoint", $FlinkJobManagerEndpoint,
        "-SnapshotTimeoutSeconds", $SnapshotTimeoutSeconds,
        "-MutationTimeoutSeconds", $MutationTimeoutSeconds,
        "-ComposeFilePath", $ComposeFilePath,
        "-ReportPath", $sourceReport,
        "-Confirm:$false"
    )
    if ($ResetSource) { $arguments += "-ResetSource" }
    if ($RestartTaskManagerAfterSnapshot) { $arguments += "-RestartTaskManagerAfterSnapshot" }

    $output = $null
    $exitCode = 0
    try {
        $output = (& pwsh @arguments | Out-String).Trim()
        $exitCode = $LASTEXITCODE
    } catch {
        $output = $_.Exception.Message
        $exitCode = 1
    }
    $path = Join-Path $repoRoot $sourceReport
    $evidence = $null
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        try { $evidence = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json } catch { }
    }
    if ($null -eq $evidence) {
        $evidence = [ordered]@{ status = "failed"; sourceKind = $SourceKind; error = $output }
    }
    if ($exitCode -ne 0 -or $evidence.status -ne "passed") {
        throw "R2 $SourceKind CDC acceptance failed; see $sourceReport"
    }
    return $evidence
}

try {
    foreach ($source in @("postgres", "mysql")) {
        try {
            $evidence = Invoke-SourceAcceptance -SourceKind $source
            $result.sources[$source] = $evidence
        } catch {
            $result.sources[$source] = [ordered]@{ status = "failed"; error = $_.Exception.Message }
            throw
        }
    }
    $result.status = "passed"
    $result.passedSources = @("postgres", "mysql")
} catch {
    $result.error = $_.Exception.Message
    $result.failedSources = @($result.sources.Keys | Where-Object { $result.sources[$_].status -ne "passed" })
} finally {
    $result.finishedAt = [DateTimeOffset]::UtcNow.ToString("O")
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $report) | Out-Null
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $report -Encoding UTF8
}

$result | ConvertTo-Json -Depth 20 -Compress
if ($result.status -ne "passed") { exit 1 }

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r3-ha",
    [string]$ReportPath = "artifacts/r3-ha/r3-ha-lifecycle-report.json",
    [string]$ProjectName = "aetheris-r3-ha",
    [string]$ComposeFilePath = "infrastructure/lakehouse/docker-compose.r1.yml",
    [string]$ComposeOverridePath = "infrastructure/lakehouse/docker-compose.r3-ha.yml",
    [int]$TimeoutSeconds = 240,
    [switch]$ResetSource,
    [switch]$SkipNetworkFaultInjection,
    [switch]$SkipFaultInjection
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $repoRoot "scripts/r3-flink-lifecycle-acceptance.ps1"
$validator = Join-Path $repoRoot "scripts/validate-r3-ha-report.py"
$report = Join-Path $repoRoot $ReportPath
$fixture = Join-Path $repoRoot $FixtureOutput

foreach ($path in @($runner, $validator, (Join-Path $repoRoot $EnvFilePath),
        (Join-Path $repoRoot $ComposeFilePath), (Join-Path $repoRoot $ComposeOverridePath))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "R3 HA required file is missing: $path" }
}
if ($TimeoutSeconds -lt 60) { throw "TimeoutSeconds must be at least 60 for HA failover" }

$args = @(
    "-EnvFilePath", $EnvFilePath,
    "-FixtureOutput", $FixtureOutput,
    "-ReportPath", $ReportPath,
    "-ProjectName", $ProjectName,
    "-ComposeFilePath", $ComposeFilePath,
    "-ComposeOverridePath", $ComposeOverridePath,
    "-GatewayEndpoint", "http://127.0.0.1:18186",
    "-JobManagerEndpoint", "http://127.0.0.1:18184",
    "-RecoveryJobManagerEndpoint", "http://127.0.0.1:18185",
    "-SourceContainer", "$ProjectName-source-postgres-1",
    "-NetworkName", "$ProjectName`_default",
    "-TimeoutSeconds", "$TimeoutSeconds"
)
if ($ResetSource) { $args += "-ResetSource" }
if ($SkipNetworkFaultInjection) { $args += "-SkipNetworkFaultInjection" }
if ($SkipFaultInjection) { $args += "-SkipFaultInjection" }

$exitCode = 0
try {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $runner @args
    $exitCode = $LASTEXITCODE
} finally {
    if (Test-Path -LiteralPath $report -PathType Leaf) {
        & py -3 $validator --report $report --project $ProjectName
        if ($LASTEXITCODE -ne 0) { $exitCode = 1 }
    } else {
        $exitCode = 1
    }
}
exit $exitCode

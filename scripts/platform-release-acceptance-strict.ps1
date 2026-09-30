[CmdletBinding()]
param(
    [switch]$OfflineOnly,
    [string]$ReportPath = "artifacts/platform-release/platform-release-acceptance-report.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$aggregate = Join-Path $repoRoot "scripts/platform-release-acceptance.ps1"
$evidence = Join-Path $repoRoot "scripts/validate-runtime-evidence.py"
$reportValidator = Join-Path $repoRoot "scripts/validate-platform-release-report.py"
$report = if ([IO.Path]::IsPathRooted($ReportPath)) { $ReportPath } else { Join-Path $repoRoot $ReportPath }

$args = @("-ReportPath", $ReportPath)
if ($OfflineOnly) { $args += "-OfflineOnly" }
& pwsh -NoProfile -ExecutionPolicy Bypass -File $aggregate @args
if ($LASTEXITCODE -eq 1) { exit 1 }

$r1Candidates = @(
    (Join-Path $repoRoot "artifacts/r1-empty-fixture/acceptance-manifest.json")
    (Join-Path $repoRoot "artifacts/r1-fixture/acceptance-manifest.json")
)
$r1Evidence = $r1Candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $r1Evidence) { $r1Evidence = $r1Candidates[0] }
& py -3 $evidence --r1 $r1Evidence --r2 (Join-Path $repoRoot "artifacts/r2-cdc/r2-cdc-acceptance-report.json")
if ($LASTEXITCODE -ne 0) { exit 1 }
& py -3 $reportValidator --report $report
if ($LASTEXITCODE -ne 0) { exit 1 }

$body = Get-Content -Raw -LiteralPath $report | ConvertFrom-Json
if ($body.status -ne "passed") {
    Write-Output "Strict platform gate did not pass: $($body.status)"
    exit 2
}
Write-Output "Strict platform release acceptance passed"

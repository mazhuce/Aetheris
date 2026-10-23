[CmdletBinding()]
param(
    [switch]$OfflineOnly,
    [string]$ReportPath = "artifacts/platform-release/platform-release-acceptance-report.json",
    [string]$ContextPath = "",
    [string]$EvidenceManifestPath = "",
    [ValidateRange(0.01, 24)][double]$MaxAgeHours = 24,
    [string]$SummaryPath = "apps/web/public/acceptance/platform-release.json",
    [switch]$ValidateExisting
)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ValidateExisting) {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "platform-release-acceptance.ps1") -ReportPath $ReportPath -ContextPath $ContextPath -EvidenceManifestPath $EvidenceManifestPath -MaxAgeHours $MaxAgeHours -SummaryPath $SummaryPath -OfflineOnly:$OfflineOnly
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
# Re-open and rehash the exact context/manifest/report paths cited by the aggregate.
$python = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python3" }
$pythonPrefix = if ($python -eq "py") { @("-3", "-B") } else { @("-B") }
& $python @pythonPrefix (Join-Path $PSScriptRoot "release-evidence.py") --root $repoRoot validate --report $ReportPath --max-age-hours $MaxAgeHours
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "Strict platform release acceptance passed"
exit 0

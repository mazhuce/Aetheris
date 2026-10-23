[CmdletBinding()]
param(
    [string]$ReportPath = "artifacts/platform-release/platform-release-acceptance-report.json",
    [switch]$OfflineOnly,
    [string]$ContextPath = "",
    [string]$EvidenceManifestPath = "",
    [ValidateRange(0.01, 24)][double]$MaxAgeHours = 24,
    [string]$SummaryPath = "apps/web/public/acceptance/platform-release.json"
)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$helper = Join-Path $PSScriptRoot "release-evidence.py"
$python = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python3" }
$pythonPrefix = if ($python -eq "py") { @("-3", "-B") } else { @("-B") }
$reportFile = if ([IO.Path]::IsPathRooted($ReportPath)) { $ReportPath } else { Join-Path $repoRoot $ReportPath }

# Historical reports remain readable, but no longer qualify as current execution.
if (-not $ContextPath -or -not $EvidenceManifestPath) {
    Write-Error "Current context and explicit evidence manifest are required. Use release-evidence.py create-context, then run each producer."
    exit 2
}
$checksFile = Join-Path $repoRoot ("artifacts/platform-release/checks-" + [guid]::NewGuid().ToString("N") + ".json")
$checkArguments = @($helper, "--root", $repoRoot, "checks", "--context", $ContextPath, "--output", $checksFile)
if ($OfflineOnly) { $checkArguments += "--offline-only" }
& $python @pythonPrefix @checkArguments
# Still emit a failed aggregate when a required offline check fails.
& $python @pythonPrefix $helper --root $repoRoot aggregate --context $ContextPath --manifest $EvidenceManifestPath --checks $checksFile --output $reportFile --max-age-hours $MaxAgeHours --summary $SummaryPath
$code = $LASTEXITCODE
if (Test-Path -LiteralPath $reportFile) { Get-Content -Raw -LiteralPath $reportFile | Write-Output }
# Keep the checks file: strict validation reopens and hashes the exact evidence.
exit $code

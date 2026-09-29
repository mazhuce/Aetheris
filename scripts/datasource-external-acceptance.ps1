[CmdletBinding()]
param(
    [string]$ManifestPath = "configs/datasource-smoke-9.example.json",
    [string]$MetadataBaseUrl = "http://127.0.0.1:8082",
    [string]$AuthorizationHeader = "",
    [string]$ReportPath = "artifacts/datasource-smoke/external-9-runtime-report.json",
    [int]$TimeoutSeconds = 60,
    [string[]]$RequiredTypes = @("postgresql", "mysql", "mariadb", "hive2", "sqlserver", "oracle", "clickhouse", "trino", "sqlite")
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$smoke = Join-Path $repoRoot "scripts/datasource-smoke.ps1"
$manifest = if ([IO.Path]::IsPathRooted($ManifestPath)) { $ManifestPath } else { Join-Path $repoRoot $ManifestPath }
$report = if ([IO.Path]::IsPathRooted($ReportPath)) { $ReportPath } else { Join-Path $repoRoot $ReportPath }
if (-not (Test-Path -LiteralPath $smoke -PathType Leaf)) { throw "Datasource smoke script is missing: $smoke" }
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "Datasource manifest is missing: $manifest" }
if ($RequiredTypes.Count -lt 7) { throw "At least seven required datasource types must be declared" }

$arguments = @("-ManifestPath", $manifest, "-MetadataBaseUrl", $MetadataBaseUrl, "-TimeoutSeconds", "$TimeoutSeconds", "-RequireAll")
if ($AuthorizationHeader.Trim()) { $arguments += @("-AuthorizationHeader", $AuthorizationHeader) }
$raw = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File $smoke @arguments 2>&1)
$jsonLine = $raw | Where-Object { $_ -and $_.ToString().Trim().StartsWith("{") } | Select-Object -Last 1
if (-not $jsonLine) { throw "Datasource smoke did not return a JSON report" }
$summary = $jsonLine.ToString() | ConvertFrom-Json
$actual = @($summary.results | ForEach-Object { [string]$_.type })
$missing = @($RequiredTypes | Where-Object { $_ -notin $actual })
$failed = @($summary.results | Where-Object { $_.status -ne "passed" })
$summary | Add-Member -NotePropertyName requiredTypes -NotePropertyValue @($RequiredTypes) -Force
$summary | Add-Member -NotePropertyName requiredTypeCount -NotePropertyValue $RequiredTypes.Count -Force
$summary | Add-Member -NotePropertyName missingRequiredTypes -NotePropertyValue @($missing) -Force
$summary | Add-Member -NotePropertyName externalGate -NotePropertyValue "real-connection-required" -Force
$summary | Add-Member -NotePropertyName status -NotePropertyValue $(if ($missing.Count -eq 0 -and $failed.Count -eq 0) { "passed" } else { "failed" }) -Force
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $report) | Out-Null
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $report -Encoding UTF8
Write-Output ($summary | ConvertTo-Json -Depth 12 -Compress)
if ($summary.status -ne "passed") { exit 1 }

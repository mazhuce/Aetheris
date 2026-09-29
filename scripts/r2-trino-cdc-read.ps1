[CmdletBinding()]
param(
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$Container = "aetheris-r1-lakehouse-trino-1",
    [string]$Table = "r1_orders_cdc"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$resultPath = Join-Path $repoRoot (Join-Path $FixtureOutput "results/r2-cdc.json")
if ($Table -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Table must be a simple identifier" }
$sql = "SELECT id, category, CAST(amount AS DECIMAL(18,2)) AS amount FROM $Table ORDER BY id"
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $output = @(& docker exec $Container trino --server http://localhost:8080 --catalog iceberg --schema bronze --output-format JSON --execute $sql 2>&1)
    $dockerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
if ($dockerExitCode -ne 0) { throw "Trino CDC read failed" }
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($line in $output) {
    $text = ([string]$line).Trim()
    if ($text.StartsWith("{") -and $text.EndsWith("}")) { $rows.Add(($text | ConvertFrom-Json)) }
}
if ($rows.Count -eq 0) { throw "Trino returned no machine-readable CDC rows" }
$payload = [ordered]@{ columns = @("id", "category", "amount"); rows = @($rows) }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultPath) | Out-Null
$payload | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Output ($payload | ConvertTo-Json -Depth 8 -Compress)

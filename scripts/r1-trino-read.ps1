[CmdletBinding()]
param(
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$Container = "aetheris-r1-lakehouse-trino-1"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$resultPath = Join-Path $repoRoot (Join-Path $FixtureOutput "results/trino.json")
$sql = "SELECT category, count(*) AS order_count, cast(sum(amount) AS decimal(18,2)) AS total_amount FROM r1_orders GROUP BY category ORDER BY category"
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $output = @(& docker exec $Container trino --server http://localhost:8080 --catalog iceberg --schema bronze --output-format JSON --execute $sql 2>&1)
    $dockerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
if ($dockerExitCode -ne 0) { throw "Trino read failed" }
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($line in $output) {
    $text = ([string]$line).Trim()
    if ($text.StartsWith("{") -and $text.EndsWith("}")) { $rows.Add(($text | ConvertFrom-Json)) }
}
if ($rows.Count -eq 0) { throw "Trino returned no machine-readable rows" }
$payload = [ordered]@{ columns = @("category", "order_count", "total_amount"); rows = @($rows) }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultPath) | Out-Null
$payload | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Output ($payload | ConvertTo-Json -Depth 8 -Compress)

[CmdletBinding()]
param(
    [string]$EnvFile = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$SparkImage = "apache/spark:3.5.6",
    [string]$Network = "aetheris-r1-lakehouse_default"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$connectorPath = (Resolve-Path (Join-Path $repoRoot "infrastructure/lakehouse/connectors")).Path
$resultPath = Join-Path $repoRoot (Join-Path $FixtureOutput "results/spark.json")
$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot $EnvFile)) {
    if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') { $values[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'") }
}
foreach ($key in @("R1_MINIO_ACCESS_KEY", "R1_MINIO_SECRET_KEY")) {
    if ([string]::IsNullOrWhiteSpace([string]$values[$key])) { throw "R1 object-store value $key is missing" }
}

$jars = "/opt/connectors/iceberg-spark-runtime-3.5_2.12-1.7.1.jar,/opt/connectors/iceberg-aws-bundle-1.7.1.jar"
$sql = "SELECT to_json(named_struct('category', category, 'order_count', CAST(count(*) AS BIGINT), 'total_amount', CAST(sum(amount) AS DECIMAL(18,2)))) AS row_json FROM lakehouse.bronze.r1_orders GROUP BY category ORDER BY category"
$dockerArgs = @(
    "run", "--rm", "--network", $Network,
    "-e", "AWS_REGION=us-east-1", "-e", "AWS_DEFAULT_REGION=us-east-1",
    "-e", ("AWS_ACCESS_KEY_ID=" + $values.R1_MINIO_ACCESS_KEY),
    "-e", ("AWS_SECRET_ACCESS_KEY=" + $values.R1_MINIO_SECRET_KEY),
    "-v", ($connectorPath + ":/opt/connectors:ro"), $SparkImage,
    "/opt/spark/bin/spark-sql", "--master", "local[2]", "--jars", $jars,
    "--conf", "spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
    "--conf", "spark.sql.catalog.lakehouse=org.apache.iceberg.spark.SparkCatalog",
    "--conf", "spark.sql.catalog.lakehouse.catalog-impl=org.apache.iceberg.rest.RESTCatalog",
    "--conf", "spark.sql.catalog.lakehouse.uri=http://catalog:8181",
    "--conf", "spark.sql.catalog.lakehouse.warehouse=s3://warehouse/",
    "--conf", "spark.sql.catalog.lakehouse.io-impl=org.apache.iceberg.aws.s3.S3FileIO",
    "--conf", "spark.sql.catalog.lakehouse.s3.endpoint=http://object-store:9000",
    "--conf", "spark.sql.catalog.lakehouse.s3.path-style-access=true",
    "--conf", ("spark.sql.catalog.lakehouse.s3.access-key-id=" + $values.R1_MINIO_ACCESS_KEY),
    "--conf", ("spark.sql.catalog.lakehouse.s3.secret-access-key=" + $values.R1_MINIO_SECRET_KEY),
    "--conf", "spark.sql.catalog.lakehouse.s3.region=us-east-1",
    "--conf", "spark.sql.cli.print.header=false", "-e", $sql
)
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $output = @(& docker @dockerArgs 2>&1)
    $dockerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
if ($dockerExitCode -ne 0) { throw "Spark read failed" }
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($line in $output) {
    $text = ([string]$line).Trim()
    if ($text.StartsWith("{") -and $text.EndsWith("}")) { $rows.Add(($text | ConvertFrom-Json)) }
}
if ($rows.Count -eq 0) { throw "Spark returned no machine-readable rows" }
$payload = [ordered]@{ columns = @("category", "order_count", "total_amount"); rows = @($rows) }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultPath) | Out-Null
$payload | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Output ($payload | ConvertTo-Json -Depth 8 -Compress)

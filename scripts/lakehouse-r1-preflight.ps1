[CmdletBinding()]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [switch]$Start,
    [switch]$RequireRunning
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "infrastructure/lakehouse/docker-compose.r1.yml"
$envFile = Join-Path $repoRoot $EnvFilePath
$project = "aetheris-r1-lakehouse"

if (-not (Test-Path -LiteralPath $composeFile)) { throw "R1 compose file is missing: $composeFile" }
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "R1 env file is missing. Copy configs/lakehouse/.env.r1.example to $EnvFilePath and replace secrets."
}

$envText = Get-Content -Raw -LiteralPath $envFile
if ($Start -and ($envText -match "replace-with|PENDING_CAPTURE")) {
    throw "Refusing to start R1 profile with example or pending secret values."
}

$composeArgs = @(
    "compose", "--project-name", $project,
    "-f", $composeFile,
    "--env-file", $envFile
)

Write-Host "Validating isolated R1 compose configuration"
& docker @composeArgs config --quiet
if ($LASTEXITCODE -ne 0) { throw "R1 compose config validation failed" }

if ($Start) {
    Write-Host "Starting only project $project"
    & docker @composeArgs up -d
    if ($LASTEXITCODE -ne 0) { throw "R1 profile failed to start" }
}

function Get-EnvValue([string]$name, [string]$fallback) {
    $match = [regex]::Match($envText, "(?m)^$([regex]::Escape($name))=(.*)$")
    if ($match.Success -and $match.Groups[2].Value.Trim()) { return $match.Groups[2].Value.Trim() }
    return $fallback
}

$endpoints = @(
    @{ name = "object-store"; url = "http://127.0.0.1:$(Get-EnvValue R1_MINIO_PORT 19000)/minio/health/live" },
    @{ name = "catalog"; url = "http://127.0.0.1:$(Get-EnvValue R1_CATALOG_PORT 18181)/v1/config" },
    @{ name = "trino"; url = "http://127.0.0.1:$(Get-EnvValue R1_TRINO_PORT 18080)/v1/info" },
    @{ name = "flink"; url = "http://127.0.0.1:$(Get-EnvValue R1_FLINK_UI_PORT 18081)/overview" }
)

$results = foreach ($endpoint in $endpoints) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $endpoint.url -TimeoutSec 5
        [pscustomobject]@{ name = $endpoint.name; status = "up"; http = [int]$response.StatusCode }
    } catch {
        [pscustomobject]@{ name = $endpoint.name; status = "down"; http = $null }
    }
}

$results | ConvertTo-Json -Compress
if ($RequireRunning -and ($results | Where-Object status -ne "up")) {
    throw "One or more R1 endpoints are not healthy"
}

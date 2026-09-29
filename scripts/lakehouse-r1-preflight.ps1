[CmdletBinding()]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$ProjectName = "aetheris-r1-lakehouse",
    [switch]$Start,
    [switch]$RequireRunning,
    [int]$TimeoutSeconds = 180,
    [int]$PollIntervalSeconds = 2
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "infrastructure/lakehouse/docker-compose.r1.yml"
$envFile = Join-Path $repoRoot $EnvFilePath
$project = $ProjectName

if (-not (Test-Path -LiteralPath $composeFile)) { throw "R1 compose file is missing: $composeFile" }
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "R1 env file is missing. Copy configs/lakehouse/.env.r1.example to $EnvFilePath and replace secrets."
}
if ($TimeoutSeconds -lt 1) { throw "TimeoutSeconds must be positive" }
if ($PollIntervalSeconds -lt 1 -or $PollIntervalSeconds -gt 30) { throw "PollIntervalSeconds must be between 1 and 30" }

$envText = Get-Content -Raw -LiteralPath $envFile
if ($Start -and ($envText -match "replace-with|PENDING_CAPTURE")) {
    throw "Refusing to start R1 profile with example or pending secret values."
}

$composeArgs = @(
    "compose", "--project-name", $project,
    "--profile", "r1",
    "-f", $composeFile,
    "--env-file", $envFile
)

Write-Host "Validating isolated R1 compose configuration"
& docker @composeArgs config --quiet
if ($LASTEXITCODE -ne 0) { throw "R1 compose config validation failed" }

if ($Start) {
    $connectorDir = Join-Path $repoRoot "infrastructure/lakehouse/connectors"
    & py -3 (Join-Path $repoRoot "scripts/fetch-r1-connectors.py") --output $connectorDir
    if ($LASTEXITCODE -ne 0) { throw "R1 connector provisioning failed" }
    Write-Host "Starting only project $project"
    & docker @composeArgs up -d
    if ($LASTEXITCODE -ne 0) { throw "R1 profile failed to start" }
}

function Get-EnvValue([string]$name, [string]$fallback) {
    $override = [Environment]::GetEnvironmentVariable($name)
    if ($override -and $override.Trim()) { return $override.Trim() }
    $match = [regex]::Match($envText, "(?m)^$([regex]::Escape($name))=(.*)$")
    if ($match.Success -and $match.Groups[2].Value.Trim()) { return $match.Groups[2].Value.Trim() }
    return $fallback
}

$endpoints = @(
    @{ name = "source-postgres"; url = "tcp://127.0.0.1:$(Get-EnvValue R1_SOURCE_POSTGRES_PORT 15432)" },
    @{ name = "source-mysql"; url = "tcp://127.0.0.1:$(Get-EnvValue R1_SOURCE_MYSQL_PORT 13306)" },
    @{ name = "object-store"; url = "http://127.0.0.1:$(Get-EnvValue R1_MINIO_PORT 19000)/minio/health/live" },
    @{ name = "catalog"; url = "http://127.0.0.1:$(Get-EnvValue R1_CATALOG_PORT 18181)/v1/config" },
    @{ name = "trino"; url = "http://127.0.0.1:$(Get-EnvValue R1_TRINO_PORT 18080)/v1/info" },
    @{ name = "flink"; url = "http://127.0.0.1:$(Get-EnvValue R1_FLINK_UI_PORT 18081)/overview" },
    @{ name = "flink-sql-gateway"; url = "http://127.0.0.1:$(Get-EnvValue R1_FLINK_SQL_GATEWAY_PORT 18083)/v1/info" }
)

function Test-TcpEndpoint([string]$hostName, [int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($hostName, $port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(2000)) { return $false }
        $client.EndConnect($pending)
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Invoke-R1EndpointProbe {
    return @($endpoints | ForEach-Object {
        $endpoint = $_
        try {
            if ($endpoint.url.StartsWith("tcp://")) {
                $uri = [uri]$endpoint.url
                if (-not (Test-TcpEndpoint -hostName $uri.Host -port $uri.Port)) { throw "TCP endpoint is unavailable" }
                [pscustomobject]@{ name = $endpoint.name; status = "up"; http = $null }
            } else {
                $response = Invoke-WebRequest -UseBasicParsing -Uri $endpoint.url -TimeoutSec 3
                [pscustomobject]@{ name = $endpoint.name; status = "up"; http = [int]$response.StatusCode }
            }
        } catch {
            [pscustomobject]@{ name = $endpoint.name; status = "down"; http = $null }
        }
    })
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $results = Invoke-R1EndpointProbe
    $pending = @($results | Where-Object status -ne "up")
    if ($pending.Count -eq 0 -or (-not $Start -and -not $RequireRunning)) { break }
    Start-Sleep -Seconds $PollIntervalSeconds
} while ((Get-Date) -lt $deadline)

$results | ConvertTo-Json -Compress
if ($RequireRunning -and $pending.Count -gt 0) {
    $names = ($pending | ForEach-Object name) -join ", "
    throw "R1 endpoints did not become healthy before timeout: $names"
}

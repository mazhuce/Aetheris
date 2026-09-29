[CmdletBinding()]
param(
    [string]$ComposeFilePath = "infrastructure/datasources/docker-compose.smoke.yml",
    [string]$ProjectName = "aetheris-datasource-smoke",
    [string]$MetadataContainer = "aetheris-metadata-service-1",
    [string]$PlatformPostgresContainer = "aetheris-postgres-1",
    [string]$R1EnvFile = "configs/lakehouse/.env.r1",
    [string]$ReportPath = "artifacts/datasource-smoke/runtime-acceptance-report.json",
    [int]$TimeoutSeconds = 120,
    [switch]$StartSmokeStack,
    [switch]$KeepStack
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $repoRoot $ComposeFilePath
$r1Env = Join-Path $repoRoot $R1EnvFile
$reportFile = Join-Path $repoRoot $ReportPath
if (-not (Test-Path -LiteralPath $compose -PathType Leaf)) { throw "Datasource smoke compose is missing: $compose" }
if (-not (Test-Path -LiteralPath $r1Env -PathType Leaf)) { throw "R1 env file is missing: $r1Env" }
if ($TimeoutSeconds -lt 30) { throw "TimeoutSeconds must be at least 30" }

function Invoke-Docker {
    param([string[]]$Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed: $($output -join ' ')" }
    return @($output)
}

function Get-DockerEngineDiagnostics {
    $diagnostics = [ordered]@{
        context = $null
        serverApiVersion = $null
        engineStatus = "unknown"
        error = $null
    }
    try {
        $contextOutput = & docker context show 2>&1
        if ($LASTEXITCODE -eq 0) { $diagnostics.context = (($contextOutput | Out-String).Trim()) }
    } catch { }
    try {
        $versionOutput = & docker version --format '{{.Server.APIVersion}}' 2>&1
        if ($LASTEXITCODE -eq 0) {
            $diagnostics.serverApiVersion = (($versionOutput | Out-String).Trim())
            $diagnostics.engineStatus = "available"
        } else {
            $diagnostics.error = (($versionOutput | Out-String).Trim())
        }
    } catch {
        $diagnostics.error = $_.Exception.Message
    }
    if ($diagnostics.error -and $diagnostics.error.Length -gt 800) {
        $diagnostics.error = $diagnostics.error.Substring(0, 800)
    }
    return $diagnostics
}

function Read-KeyValueFile([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$matches[1]] = $matches[2].Trim()
        }
    }
    return $values
}

function Read-ContainerEnv([string]$Container) {
    $lines = Invoke-Docker @("inspect", $Container, "--format", "{{range .Config.Env}}{{println .}}{{end}}")
    $values = @{}
    foreach ($line in $lines) {
        if ($line -match '^([^=]+)=(.*)$') { $values[$matches[1]] = $matches[2] }
    }
    return $values
}

function SqlLiteral([string]$Value) {
    if ($null -eq $Value) { return "NULL" }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Wait-SmokeHealthy {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $rows = @(Invoke-Docker @("ps", "--filter", "label=com.docker.compose.project=$ProjectName", "--format", "{{.Names}}\t{{.Status}}"))
        $healthy = @($rows | Where-Object { $_ -match 'datasource-smoke-(mariadb|clickhouse|cockroachdb)-1\s+Up .*healthy' })
        if ($healthy.Count -eq 3) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Datasource smoke services did not all become healthy"
}

function Invoke-MetadataJson([string]$Path, [hashtable]$Body) {
    $json = ($Body | ConvertTo-Json -Compress -Depth 8).Replace("'", "'\\''")
    $command = 'curl -fsS -H "X-Internal-Token: $AETHERIS_INTERNAL_TOKEN" -H "Content-Type: application/json" --data-raw ''__PAYLOAD__'' http://127.0.0.1:8082__PATH__'
    $command = $command.Replace("__PAYLOAD__", $json).Replace("__PATH__", $Path)
    $raw = (Invoke-Docker @("exec", $MetadataContainer, "sh", "-lc", $command) | Out-String).Trim()
    if (-not $raw) { throw "metadata internal endpoint returned an empty response" }
    return $raw | ConvertFrom-Json
}

$startedByScript = $false
$createdIds = [System.Collections.Generic.List[int]]::new()
$report = [ordered]@{
    schemaVersion = 1
    gate = "datasource-jdbc-runtime-acceptance"
    status = "failed"
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    testedTypes = @()
    results = @()
    blockingCondition = $null
    retryable = $false
    docker = [ordered]@{ context = $null; serverApiVersion = $null; engineStatus = "unknown"; error = $null }
    cleanup = [ordered]@{ datasourceRowsRemoved = $false; smokeStackStopped = $false }
    error = $null
}

try {
    if ($StartSmokeStack) {
        $env:DATASOURCE_SMOKE_PASSWORD = [Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
        $env:DATASOURCE_SMOKE_ROOT_PASSWORD = [Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
        Invoke-Docker @("compose", "--project-name", $ProjectName, "-f", $compose, "up", "-d") | Out-Null
        $startedByScript = $true
        Wait-SmokeHealthy
    }

    $r1 = Read-KeyValueFile $r1Env
    $smokeEnv = Read-ContainerEnv "$ProjectName-mariadb-1"
    $postgresPassword = $r1["R1_SOURCE_POSTGRES_PASSWORD"]
    $postgresUser = $r1["R1_SOURCE_POSTGRES_USER"]
    $postgresDb = $r1["R1_SOURCE_POSTGRES_DB"]
    $mysqlPassword = if ($r1["R1_SOURCE_MYSQL_PASSWORD"]) { $r1["R1_SOURCE_MYSQL_PASSWORD"] } else { $postgresPassword }
    $mysqlDb = if ($r1["R1_SOURCE_MYSQL_DB"]) { $r1["R1_SOURCE_MYSQL_DB"] } else { "r1_mysql_source" }
    $mariaPassword = $smokeEnv["MARIADB_PASSWORD"]
    if (-not $postgresPassword -or -not $mariaPassword) { throw "Datasource smoke credentials are not available" }

    $sources = @(
        @{ Name = "datasource-smoke-postgresql"; Type = "postgresql"; Host = "host.docker.internal"; Port = 15432; Database = $postgresDb; User = $postgresUser; Password = $postgresPassword },
        @{ Name = "datasource-smoke-mysql"; Type = "mysql"; Host = "host.docker.internal"; Port = 13306; Database = $mysqlDb; User = "r1_mysql_fixture"; Password = $mysqlPassword },
        @{ Name = "datasource-smoke-trino"; Type = "trino"; Host = "host.docker.internal"; Port = 18080; Database = "hive"; User = "aetheris"; Password = "" },
        @{ Name = "datasource-smoke-mariadb"; Type = "mariadb"; Host = "host.docker.internal"; Port = 13308; Database = "datasource_smoke"; User = "smoke"; Password = $mariaPassword },
        @{ Name = "datasource-smoke-clickhouse"; Type = "clickhouse"; Host = "host.docker.internal"; Port = 18123; Database = "datasource_smoke"; User = "default"; Password = "" },
        @{ Name = "datasource-smoke-cockroachdb"; Type = "cockroachdb"; Host = "host.docker.internal"; Port = 26258; Database = "defaultdb"; User = "root"; Password = "" },
        @{ Name = "datasource-smoke-sqlite"; Type = "sqlite"; Host = ""; Port = 0; Database = "/tmp/aetheris-datasource-smoke.sqlite"; User = ""; Password = "" }
    )
    foreach ($source in $sources) {
        $sql = "INSERT INTO metadata.data_source (name,type,host,port,database_name,username,password,status,managed) VALUES (" +
            ((SqlLiteral $source.Name), (SqlLiteral $source.Type), (SqlLiteral $source.Host), $source.Port, (SqlLiteral $source.Database), (SqlLiteral $source.User), (SqlLiteral $source.Password), "'active'", "false" -join ",") + ") RETURNING id"
        $idText = (Invoke-Docker @("exec", $PlatformPostgresContainer, "psql", "-q", "-U", "aetheris", "-d", "aetheris", "-Atc", $sql) | Select-Object -Last 1).ToString().Trim()
        $id = [int]$idText
        $createdIds.Add($id)
        if ($source.Type -eq "sqlite") {
            Invoke-MetadataJson "/internal/datasources/$id/execute" @{ sql = "CREATE TABLE IF NOT EXISTS smoke_probe (id INTEGER)"; timeoutSeconds = 30 } | Out-Null
        }
        $response = Invoke-MetadataJson "/internal/agent/query" @{ datasourceId = $id; sql = "SELECT 1 AS connected" }
        $passed = $null -eq $response.error -and [int]$response.rowCount -eq 1 -and [int]$response.rows[0][0] -eq 1
        $report.results += [ordered]@{ type = $source.Type; status = if ($passed) { "passed" } else { "failed" }; rowCount = $response.rowCount; message = if ($passed) { "SELECT 1" } else { [string]$response.error } }
        if (-not $passed) { throw "Datasource runtime probe failed for $($source.Type)" }
    }
    $report.testedTypes = @($sources | ForEach-Object Type)
    $report.status = "passed"
} catch {
    $report.error = $_.Exception.Message
    if ($report.error -match '(?i)(docker|engine).*(500|internal server error)|internal server error') {
        $report.blockingCondition = "docker-engine-api-unavailable"
        $report.retryable = $true
        $report.docker = Get-DockerEngineDiagnostics
    }
} finally {
    if ($createdIds.Count -gt 0) {
        $idList = ($createdIds -join ",")
        try { Invoke-Docker @("exec", $PlatformPostgresContainer, "psql", "-U", "aetheris", "-d", "aetheris", "-v", "ON_ERROR_STOP=1", "-c", "DELETE FROM metadata.data_source WHERE id IN ($idList)") | Out-Null; $report.cleanup.datasourceRowsRemoved = $true } catch { }
    }
    if ($startedByScript -and -not $KeepStack) {
        try { Invoke-Docker @("compose", "--project-name", $ProjectName, "-f", $compose, "down") | Out-Null; $report.cleanup.smokeStackStopped = $true } catch { }
    }
    $report.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportFile) | Out-Null
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportFile -Encoding UTF8
}

Write-Output ($report | ConvertTo-Json -Depth 12 -Compress)
if ($report.status -ne "passed") { exit 1 }

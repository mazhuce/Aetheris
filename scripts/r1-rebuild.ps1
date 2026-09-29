[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$ProjectName = "aetheris-r1-lakehouse",
    [int]$PortOffset = 0,
    [switch]$ResetVolumes,
    [switch]$AllowVolumeReset,
    [switch]$ForceFixture,
    [switch]$Start,
    [switch]$RunAcceptance,
    [switch]$RequireFreshRebuild,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "infrastructure/lakehouse/docker-compose.r1.yml"
$envFile = Join-Path $repoRoot $EnvFilePath
$envText = if (Test-Path -LiteralPath $envFile) { Get-Content -Raw -LiteralPath $envFile } else { "" }
$project = $ProjectName
$fixturePath = Join-Path $repoRoot $FixtureOutput
$script:R1Phase = "initialization"

function Write-AcceptanceFailure([object]$ErrorRecord) {
    if (-not $RunAcceptance) { return }
    try {
        $failurePath = Join-Path $fixturePath "acceptance-failure.json"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $failurePath) | Out-Null
        [ordered]@{
            schemaVersion = 1
            gate = if ($ResetVolumes) { "R1-empty-rebuild-hard-gate" } else { "R1-cross-engine-runtime-gate" }
            status = "failed"
            project = $project
            fixture = $FixtureOutput
            phase = $script:R1Phase
            generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
            error = [string]$ErrorRecord.Exception.Message
        } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $failurePath -Encoding UTF8
        Write-Host "R1 acceptance failure report: $failurePath"
    } catch {
        Write-Warning "Unable to write R1 acceptance failure report: $($_.Exception.Message)"
    }
}

trap {
    Write-AcceptanceFailure $_
    Write-Error $_
    exit 1
}

if ($PortOffset -ne 0) {
    if ($PortOffset -lt 100 -or $PortOffset -gt 20000) { throw "PortOffset must be between 100 and 20000" }
    $portValues = @{
        R1_MINIO_PORT = 19000
        R1_MINIO_CONSOLE_PORT = 19001
        R1_SOURCE_POSTGRES_PORT = 15432
        R1_SOURCE_MYSQL_PORT = 13306
        R1_CATALOG_PORT = 18181
        R1_TRINO_PORT = 18080
        R1_FLINK_UI_PORT = 18081
        R1_FLINK_SQL_GATEWAY_PORT = 18083
    }
    foreach ($line in ($envText -split "`r?`n")) {
        if ($line -match '^\s*([^#=\s]+)\s*=\s*(\d+)\s*$') {
            $portValues[$matches[1]] = [int]$matches[2]
        }
    }
    foreach ($portName in @(
        "R1_MINIO_PORT", "R1_MINIO_CONSOLE_PORT", "R1_SOURCE_POSTGRES_PORT",
        "R1_SOURCE_MYSQL_PORT", "R1_CATALOG_PORT", "R1_TRINO_PORT",
        "R1_FLINK_UI_PORT", "R1_FLINK_SQL_GATEWAY_PORT"
    )) {
        if ($portValues.ContainsKey($portName)) {
            $offsetPort = [int]$portValues[$portName] + $PortOffset
            # Use PowerShell's process-scope environment provider so all child
            # processes (Docker Compose and the nested acceptance scripts) see
            # the same isolated port mapping.  SetEnvironmentVariable alone can
            # leave the current PowerShell environment cache stale on Windows.
            Set-Item -Path ("Env:" + $portName) -Value ([string]$offsetPort)
        }
    }
}

if ($RequireFreshRebuild -and -not $ResetVolumes) {
    throw "RequireFreshRebuild requires ResetVolumes so stale catalog or object-store state cannot satisfy the hard gate"
}

if (-not (Test-Path -LiteralPath $composeFile)) { throw "R1 compose file is missing: $composeFile" }
if (-not (Test-Path -LiteralPath $envFile)) { throw "R1 env file is missing: $envFile" }
$composeArgs = @("compose", "--project-name", $project, "--profile", "r1", "-f", $composeFile, "--env-file", $envFile)

# Fixture generation is deterministic and does not touch running services.
$fixtureArgs = @("generate", "--output", $fixturePath)
if ($ForceFixture) { $fixtureArgs += "--force" }
python (Join-Path $repoRoot "scripts/r1-fixture.py") @fixtureArgs
if ($LASTEXITCODE -ne 0) {
    throw "R1 fixture generation failed; use -ForceFixture only when replacing the existing deterministic fixture"
}

function Get-EnvValue([string]$name, [string]$fallback) {
    $override = [Environment]::GetEnvironmentVariable($name)
    if ($override -and $override.Trim()) { return $override.Trim() }
    $match = [regex]::Match($envText, "(?m)^$([regex]::Escape($name))=(.*)$")
    if ($match.Success -and $match.Groups[2].Value.Trim()) { return $match.Groups[2].Value.Trim() }
    return $fallback
}

if ($Start -or $RunAcceptance -or $ResetVolumes) {
    if ($envText -match "replace-with|PENDING_CAPTURE") {
        throw "Refusing to change the R1 environment while example or pending values remain in $envFile"
    }
    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw "R1 compose configuration is invalid" }
}

if ($Start -or $RunAcceptance) {
    $script:R1Phase = "compose-validation"
    $connectorDir = Join-Path $repoRoot "infrastructure/lakehouse/connectors"
    & py -3 (Join-Path $repoRoot "scripts/fetch-r1-connectors.py") --output $connectorDir
    if ($LASTEXITCODE -ne 0) { throw "R1 connector provisioning failed" }
    & py -3 (Join-Path $repoRoot "scripts/r1-fixture.py") render --fixture $fixturePath --env-file $envFile
    if ($LASTEXITCODE -ne 0) { throw "R1 Flink SQL credential rendering failed" }
}

if ($ResetVolumes) {
    $script:R1Phase = "volume-reset"
    if (-not $AllowVolumeReset) {
        throw "ResetVolumes requires -AllowVolumeReset because it removes only the named project's volumes"
    }
    if ($PSCmdlet -and -not $PSCmdlet.ShouldProcess("$project volumes", "remove and recreate the isolated R1 environment")) {
        throw "Volume reset was not confirmed"
    }
    & docker @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { throw "R1 volume reset failed" }
}

if ($Start) {
    $script:R1Phase = "compose-start"
    & docker @composeArgs up -d
    if ($LASTEXITCODE -ne 0) { throw "R1 environment failed to start" }
}

if ($RunAcceptance) {
    $script:R1Phase = "preflight"
    if ($TimeoutSeconds -lt 1) { throw "TimeoutSeconds must be positive" }
    if (-not $Start) {
        & docker @composeArgs up -d
        if ($LASTEXITCODE -ne 0) { throw "R1 environment failed to start" }
    }

    # The acceptance path is intentionally explicit and does not remove volumes:
    # it proves the real running chain without silently destroying source data.
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/lakehouse-r1-preflight.ps1") `
        -EnvFilePath $EnvFilePath -ProjectName $project -RequireRunning -TimeoutSeconds $TimeoutSeconds
    if ($LASTEXITCODE -ne 0) { throw "R1 preflight failed" }

    # R1 and R2 intentionally share the isolated PostgreSQL source. CDC smoke
    # mutates that table, so every bounded R1 acceptance must restore the
    # deterministic fixture before Flink reads it. This never touches the
    # platform database or any non-R1 container.
    $sourceFixtureSql = Get-Content -Raw -LiteralPath (Join-Path $fixturePath "postgres/001_source_orders.sql")
    $script:R1Phase = "source-fixture-reset"
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $sourceFixtureSql | & docker @composeArgs exec -T source-postgres sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
        $sourceResetExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($sourceResetExitCode -ne 0) { throw "R1 deterministic source fixture reset failed" }

    $gateway = "http://127.0.0.1:$(Get-EnvValue R1_FLINK_SQL_GATEWAY_PORT 18083)"
    $jobManager = "http://127.0.0.1:$(Get-EnvValue R1_FLINK_UI_PORT 18081)"
    $renderedSql = Join-Path $fixturePath "flink/rendered/001_bronze_orders.sql"
    $script:R1Phase = "flink-submit"
    $flinkOutput = (& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/r1-submit-flink-sql.ps1") `
        -GatewayEndpoint $gateway -JobManagerEndpoint $jobManager -SqlFile $renderedSql -EnvFile $envFile -TimeoutSeconds $TimeoutSeconds
    ) | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Flink bounded fixture submission failed" }
    $flinkSummaryPath = Join-Path $fixturePath "results/flink.json"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $flinkSummaryPath) | Out-Null
    $flinkOutput.Trim() | Set-Content -LiteralPath $flinkSummaryPath -Encoding UTF8
    try { $flinkSummary = $flinkOutput | ConvertFrom-Json } catch { throw "Flink submission did not return JSON evidence" }
    if ($flinkSummary.status -ne "passed") { throw "Flink submission evidence is not passed" }

    $script:R1Phase = "spark-read"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/r1-spark-read.ps1") `
        -EnvFile $EnvFilePath -FixtureOutput $FixtureOutput -Network ("${project}_default")
    if ($LASTEXITCODE -ne 0) { throw "Spark reconciliation read failed" }

    $script:R1Phase = "trino-read"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot "scripts/r1-trino-read.ps1") `
        -FixtureOutput $FixtureOutput -Container "$project-trino-1"
    if ($LASTEXITCODE -ne 0) { throw "Trino reconciliation read failed" }

    $script:R1Phase = "reconciliation"
    $reportPath = Join-Path $fixturePath "reconcile.json"
    & python (Join-Path $repoRoot "scripts/r1-fixture.py") reconcile `
        --fixture $fixturePath `
        --spark-result (Join-Path $fixturePath "results/spark.json") `
        --trino-result (Join-Path $fixturePath "results/trino.json") `
        --report $reportPath
    if ($LASTEXITCODE -ne 0) { throw "R1 cross-engine reconciliation failed" }
    $reconcile = Get-Content -Raw -LiteralPath $reportPath | ConvertFrom-Json
    if ($reconcile.status -ne "passed") { throw "R1 reconciliation report is not passed" }

    function Get-Sha256([string]$path) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Acceptance artifact is missing: $path" }
        return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    }

    $gitCommit = $null
    try {
        $gitCommit = (& git -C $repoRoot rev-parse HEAD 2>$null).Trim()
        if (-not $gitCommit) { $gitCommit = $null }
    } catch { $gitCommit = $null }
    $acceptanceManifest = [ordered]@{
        schemaVersion = 1
        gate = if ($ResetVolumes) { "R1-empty-rebuild-hard-gate" } else { "R1-cross-engine-runtime-gate" }
        status = $reconcile.status
        freshRebuild = [bool]$ResetVolumes
        sourceFixtureReset = $true
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        project = $project
        fixture = $reconcile.fixture
        bronzeTable = $reconcile.bronzeTable
        expectedChecksum = $reconcile.expected.checksum
        engineChecksums = [ordered]@{
            flink = "write-verified-by-gateway"
            spark = $reconcile.engines.spark.checksum
            trino = $reconcile.engines.trino.checksum
        }
        flinkSubmission = $flinkSummary
        gitCommit = $gitCommit
        artifacts = [ordered]@{
            bom = [ordered]@{ path = "configs/lakehouse/r1-bom.toml"; sha256 = Get-Sha256 (Join-Path $repoRoot "configs/lakehouse/r1-bom.toml") }
            connectorAllowlist = [ordered]@{ path = "configs/lakehouse/connector-allowlist.toml"; sha256 = Get-Sha256 (Join-Path $repoRoot "configs/lakehouse/connector-allowlist.toml") }
            connectorManifest = [ordered]@{ path = "infrastructure/lakehouse/connectors/CONNECTOR-MANIFEST.json"; sha256 = Get-Sha256 (Join-Path $repoRoot "infrastructure/lakehouse/connectors/CONNECTOR-MANIFEST.json") }
            compose = [ordered]@{ path = "infrastructure/lakehouse/docker-compose.r1.yml"; sha256 = Get-Sha256 $composeFile }
            tableContract = [ordered]@{ path = "configs/lakehouse/r1-table-contract.toml"; sha256 = Get-Sha256 (Join-Path $repoRoot "configs/lakehouse/r1-table-contract.toml") }
            fixtureManifest = [ordered]@{ path = (Join-Path $FixtureOutput "manifest.json"); sha256 = Get-Sha256 (Join-Path $fixturePath "manifest.json") }
            flinkSubmission = [ordered]@{ path = (Join-Path $FixtureOutput "results/flink.json"); sha256 = Get-Sha256 $flinkSummaryPath }
        }
    }
    $acceptancePath = Join-Path $fixturePath "acceptance-manifest.json"
    $acceptanceManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $acceptancePath -Encoding UTF8
    if ($ResetVolumes) {
        Write-Host "R1 empty-rebuild hard-gate acceptance passed: $reportPath"
    } else {
        Write-Host "R1 cross-engine runtime regression passed (not an empty rebuild): $reportPath"
    }
    Write-Host "R1 acceptance manifest: $acceptancePath"
}

Write-Host "R1 fixture is ready at $fixturePath"
Write-Host "Apply postgres/001_source_orders.sql to the isolated source-postgres database."
Write-Host "Apply mysql/001_source_orders.sql to source-mysql when running the R2 MySQL CDC smoke."
Write-Host "Submit flink/rendered/001_bronze_orders.sql through the approved Flink SQL Gateway."
Write-Host "Run the Spark and Trino SQL files, export JSON results, then run scripts/r1-fixture.py reconcile."

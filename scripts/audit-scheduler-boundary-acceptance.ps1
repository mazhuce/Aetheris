param([string]$Image = 'postgres:16-alpine', [string]$ReportDirectory = 'output/audit-scheduler-boundary')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$reportPath = [IO.Path]::GetFullPath((Join-Path $root $ReportDirectory))
if (-not $reportPath.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Report directory must remain inside this repository'
}
New-Item -ItemType Directory -Force -Path $reportPath | Out-Null
$runId = [Guid]::NewGuid().ToString('N')
$name = 'aetheris-audit-scheduler-' + $runId
$previousPassword = $env:POSTGRES_PASSWORD
$previousTestPassword = $env:AETHERIS_TEST_POSTGRES_PASSWORD
$previousTestUrl = $env:AETHERIS_TEST_POSTGRES_URL
$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$container = $null
$report = [ordered]@{schemaVersion=1; runId=$runId; startedAt=[DateTimeOffset]::UtcNow.ToString('o'); status='failed'; cleanup='not_started'}
function Assert-NativeSuccess([string]$operation) { if ($LASTEXITCODE -ne 0) { throw "$operation failed with exit $LASTEXITCODE" } }
try {
    & docker image inspect $Image --format '{{.Id}}' *> $null
    Assert-NativeSuccess 'Locate existing PostgreSQL image (no automatic pull)'
    $container = (& docker run -d --rm --name $name --label "aetheris.audit.scheduler.run=$runId" `
        --memory 384m --cpus 1 --pids-limit 128 --security-opt no-new-privileges:true `
        --tmpfs /var/lib/postgresql/data -p '127.0.0.1::5432' `
        --env POSTGRES_PASSWORD --env POSTGRES_USER=audit_admin --env POSTGRES_DB=aetheris_audit $Image).Trim()
    Assert-NativeSuccess 'Start isolated PostgreSQL'
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        & docker exec $container pg_isready -U audit_admin -d aetheris_audit *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'PostgreSQL readiness deadline exceeded' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $port = ((& docker port $container 5432/tcp).Trim() -split ':')[-1]
    Assert-NativeSuccess 'Read isolated PostgreSQL port'
    $env:AETHERIS_TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:$port/aetheris_audit"
    $env:AETHERIS_TEST_POSTGRES_PASSWORD = $env:POSTGRES_PASSWORD
    Push-Location $root
    try {
        foreach ($module in @('ingestion','quality')) {
            & mvn -q -o -f (Join-Path $root ("services/$module/pom.xml")) '-Dtest=SchedulerOutboxPostgresIT' test *> (Join-Path $reportPath ("$module-maven.log"))
            Assert-NativeSuccess "$module PostgreSQL migration/outbox/Quartz acceptance"
        }

    } finally { Pop-Location }
    $report.status = 'passed'
} catch {
    $report.error = $_.Exception.Message
} finally {
    if ($container) {
        & docker rm -f $container *> $null
        $removed = $LASTEXITCODE -eq 0
        $remaining = @(& docker ps -a --filter "label=aetheris.audit.scheduler.run=$runId" --format '{{.ID}}')
        $report.cleanup = if ($removed -and $LASTEXITCODE -eq 0 -and $remaining.Count -eq 0) { 'passed' } else { 'failed' }
        if ($report.cleanup -ne 'passed') { $report.status = 'failed' }
    } else { $report.cleanup = 'not_created' }
    $env:POSTGRES_PASSWORD = $previousPassword
    $env:AETHERIS_TEST_POSTGRES_PASSWORD = $previousTestPassword
    $env:AETHERIS_TEST_POSTGRES_URL = $previousTestUrl
    $report.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $reportPath 'report.json') -Encoding utf8
}
$report | ConvertTo-Json -Depth 10
if ($report.status -ne 'passed') { exit 1 }

param([string]$Image='postgres:16-alpine')
$ErrorActionPreference='Stop'
$moduleRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$target=Join-Path $moduleRoot 'target'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$runId=[Guid]::NewGuid().ToString('N')
$name='aetheris-audit-ai-'+$runId
$container=$null
$previousPassword=$env:POSTGRES_PASSWORD
$previousTestPassword=$env:AETHERIS_TEST_POSTGRES_PASSWORD
$previousUrl=$env:AETHERIS_TEST_POSTGRES_URL
$report=[ordered]@{runId=$runId;status='failed';cleanup='not_started';startedAt=[DateTimeOffset]::UtcNow.ToString('o')}
function Assert-Success([string]$operation){if($LASTEXITCODE -ne 0){throw "$operation failed with exit $LASTEXITCODE"}}
try {
    & docker image inspect $Image --format '{{.Id}}' *> $null
    Assert-Success 'Locate existing PostgreSQL image (no automatic pull)'
    $env:POSTGRES_PASSWORD=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
    $container=(& docker run -d --rm --name $name --label "aetheris.audit.ai.run=$runId" --memory 384m --cpus 1 --pids-limit 128 --security-opt no-new-privileges:true --tmpfs /var/lib/postgresql/data -p '127.0.0.1::5432' --env POSTGRES_PASSWORD --env POSTGRES_USER=audit_admin --env POSTGRES_DB=aetheris_audit $Image).Trim()
    Assert-Success 'Start isolated PostgreSQL'
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(45)
    do {
        & docker exec $container pg_isready -U audit_admin -d aetheris_audit *> $null
        if($LASTEXITCODE -eq 0){break}
        if([DateTimeOffset]::UtcNow -gt $deadline){throw 'PostgreSQL readiness timeout'}
        Start-Sleep -Milliseconds 400
    } while($true)
    $port=((& docker port $container 5432/tcp).Trim() -split ':')[-1]
    Assert-Success 'Read isolated PostgreSQL port'
    $env:AETHERIS_TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:$port/aetheris_audit"
    $env:AETHERIS_TEST_POSTGRES_PASSWORD=$env:POSTGRES_PASSWORD
    & mvn -q -o -f (Join-Path $moduleRoot 'pom.xml') '-Dtest=ChatRunStorePostgresIT' test *> (Join-Path $target 'ai-postgres-it.log')
    Assert-Success 'AI PostgreSQL migration/fencing/rollback acceptance'
    $report.status='passed'
} catch {$report.error=$_.Exception.Message}
finally {
    if($container) {
        & docker rm -f $container *> $null
        $removed=$LASTEXITCODE -eq 0
        $remaining=@(& docker ps -a --filter "label=aetheris.audit.ai.run=$runId" --format '{{.ID}}')
        $report.cleanup=if($removed -and $LASTEXITCODE -eq 0 -and $remaining.Count -eq 0){'passed'}else{'failed'}
        if($report.cleanup -ne 'passed'){$report.status='failed'}
    } else {$report.cleanup='not_created'}
    $env:POSTGRES_PASSWORD=$previousPassword
    $env:AETHERIS_TEST_POSTGRES_PASSWORD=$previousTestPassword
    $env:AETHERIS_TEST_POSTGRES_URL=$previousUrl
    $report.finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $target 'ai-postgres-report.json') -Encoding utf8
}
$report | ConvertTo-Json -Depth 6
if($report.status -ne 'passed'){exit 1}

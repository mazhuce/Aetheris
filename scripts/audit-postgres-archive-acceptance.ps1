param([string]$Image = 'postgres:16-alpine', [string]$ReportDirectory = 'output/audit-postgres-archive')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$directory = [IO.Path]::GetFullPath((Join-Path $root $ReportDirectory))
if (-not $directory.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Report directory escapes repository' }
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$runId = [Guid]::NewGuid().ToString('N')
$name = 'aetheris-audit-archive-' + $runId
$saved = @{}
foreach ($key in @('POSTGRES_PASSWORD','POSTGRES_DB','POSTGRES_USER','AETHERIS_POSTGRES_CONTAINER')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:POSTGRES_USER = 'audit_admin'
$env:POSTGRES_DB = 'aetheris_audit'
$report = [ordered]@{schemaVersion=1; runId=$runId; startedAt=[DateTimeOffset]::UtcNow.ToString('o'); status='failed'; cleanup='not_started'}
$container = $null
function Invoke-AuditDocker([string[]]$Arguments) {
    $result = @(& docker @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Isolated archive operation failed (exit $LASTEXITCODE)" }
    return $result
}
function Read-FixtureDigest([string]$Database) {
    return ((Invoke-AuditDocker @('exec',$container,'psql','-U','audit_admin','-d',$Database,'-Atc',
        "SELECT md5(string_agg(id::text || ':' || note || ':' || encode(payload,'hex') || ':' || coalesce(optional,'<NULL>'),'|' ORDER BY id)) FROM audit_archive.sample")) -join '').Trim()
}
try {
    Invoke-AuditDocker @('image','inspect',$Image,'--format','{{.Id}}') | Out-Null
    $container = ((Invoke-AuditDocker @('run','-d','--rm','--name',$name,'--label',"aetheris.audit.run=$runId",
        '--memory','384m','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true',
        '--tmpfs','/var/lib/postgresql/data','--env','POSTGRES_PASSWORD','--env','POSTGRES_USER','--env','POSTGRES_DB',$Image)) -join '').Trim()
    $env:AETHERIS_POSTGRES_CONTAINER = $container
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        & docker exec $container pg_isready -U audit_admin -d aetheris_audit *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Isolated database readiness timed out' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $fixture = Join-Path $directory 'fixture.sql'
    @'
CREATE SCHEMA audit_archive;
CREATE TABLE audit_archive.sample(id BIGSERIAL PRIMARY KEY, note TEXT NOT NULL, payload BYTEA NOT NULL, optional TEXT, CONSTRAINT nonempty CHECK(length(note)>0));
INSERT INTO audit_archive.sample(note,payload,optional) VALUES ('汉字 café 数据',decode('00017fff80','hex'),NULL),('second',decode('ff000a','hex'),'');
CREATE INDEX sample_note_idx ON audit_archive.sample(note);
'@ | Set-Content -LiteralPath $fixture -Encoding utf8
    Invoke-AuditDocker @('cp',$fixture,"${container}:/tmp/archive-fixture.sql") | Out-Null
    Invoke-AuditDocker @('exec',$container,'psql','-v','ON_ERROR_STOP=1','-U','audit_admin','-d','aetheris_audit','-f','/tmp/archive-fixture.sql') | Out-Null
    $expected = Read-FixtureDigest 'aetheris_audit'
    $dump = Join-Path $directory ("$runId.dump")
    & (Join-Path $PSScriptRoot 'backup-postgres.ps1') -OutputFile $dump
    $report.archiveSha256 = (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash.ToLowerInvariant()
    Invoke-AuditDocker @('exec',$container,'createdb','-U','audit_admin','aetheris_restore') | Out-Null
    $env:POSTGRES_DB = 'aetheris_restore'
    & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -BackupFile $dump -ExpectedSha256 $report.archiveSha256
    if ((Read-FixtureDigest 'aetheris_restore') -ne $expected) { throw 'Restored Unicode, binary or NULL content differs' }
    $constraints = ((Invoke-AuditDocker @('exec',$container,'psql','-U','audit_admin','-d','aetheris_restore','-Atc',
        "SELECT count(*) FROM pg_constraint WHERE conrelid='audit_archive.sample'::regclass AND contype IN ('p','c')")) -join '').Trim()
    $indexes = ((Invoke-AuditDocker @('exec',$container,'psql','-U','audit_admin','-d','aetheris_restore','-Atc',
        "SELECT count(*) FROM pg_indexes WHERE schemaname='audit_archive' AND indexname='sample_note_idx'")) -join '').Trim()
    if ($constraints -ne '2' -or $indexes -ne '1') { throw 'Restored constraints or index differ' }
    $next = ((Invoke-AuditDocker @('exec',$container,'psql','-U','audit_admin','-d','aetheris_restore','-Atc',
        "INSERT INTO audit_archive.sample(note,payload) VALUES ('sequence-check',decode('00','hex')) RETURNING id")) -join ' ').Trim()
    if ($next -notmatch '^3(?:\s|$)') { throw 'Restored serial sequence differs' }
    Invoke-AuditDocker @('exec',$container,'psql','-v','ON_ERROR_STOP=1','-U','audit_admin','-d','aetheris_restore','-c',
        'DELETE FROM audit_archive.sample WHERE id=3') | Out-Null
    & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -BackupFile $dump -Clean -Confirm:$false -ExpectedSha256 $report.archiveSha256
    if ((Read-FixtureDigest 'aetheris_restore') -ne $expected) { throw 'Transactional clean restore differs' }
    $corrupt = Join-Path $directory ("$runId-corrupt.dump")
    [IO.File]::WriteAllBytes($corrupt, [Text.Encoding]::ASCII.GetBytes('PGDMPnot-a-valid-archive'))
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -BackupFile $corrupt } catch { $rejected = $true }
    if (-not $rejected -or (Read-FixtureDigest 'aetheris_restore') -ne $expected) { throw 'Invalid archive was not safely rejected' }
    $remaining = Invoke-AuditDocker @('exec',$container,'find','/tmp','-maxdepth','1','-name','aetheris-pg.*','-print')
    if (($remaining -join '').Trim()) { throw 'Archive scripts left temporary files' }
    $report.checks = @('unicode-binary-null','constraints-indexes','serial-sequence','clean-restore','sha256','corrupt-archive-rejected','container-temp-cleanup')
    $report.status = 'passed'
} catch { $report.error = $_.Exception.Message }
finally {
    if ($container) {
        & docker rm -f $container *> $null
        $report.cleanup = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'failed' }
        if ($report.cleanup -ne 'passed') { $report.status = 'failed' }
    } else { $report.cleanup = 'not_created' }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    $report.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $directory 'report.json') -Encoding utf8
}
$report | ConvertTo-Json -Depth 10
if ($report.status -ne 'passed') { exit 1 }

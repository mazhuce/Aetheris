param([string]$OutputDir = './backups')
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$file = Join-Path $OutputDir "aetheris-$stamp.dump"
$container = if ($env:AETHERIS_POSTGRES_CONTAINER) { $env:AETHERIS_POSTGRES_CONTAINER } else { 'aetheris-postgres-1' }
$db = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'aetheris' }
$user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'aetheris' }
docker exec $container pg_dump -Fc -U $user -d $db | Set-Content -Encoding Byte -Path $file
Write-Output "Backup written to $file"

param(
  [Parameter(Mandatory=$true)][string]$BackupFile,
  [switch]$Clean
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { throw "Backup file not found: $BackupFile" }
$container = if ($env:AETHERIS_POSTGRES_CONTAINER) { $env:AETHERIS_POSTGRES_CONTAINER } else { 'aetheris-postgres-1' }
$db = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'aetheris' }
$user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'aetheris' }
$args = @('exec','-i',$container,'pg_restore','-U',$user,'-d',$db,'--exit-on-error')
if ($Clean) { $args += @('--clean','--if-exists') }
Get-Content -LiteralPath $BackupFile -Encoding Byte | docker @args
if ($LASTEXITCODE -ne 0) { throw "pg_restore failed with exit code $LASTEXITCODE" }
Write-Output "Restore completed from $BackupFile"

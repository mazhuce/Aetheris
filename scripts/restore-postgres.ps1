[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [switch]$Clean,
    [string]$ExpectedSha256 = ""
)
$ErrorActionPreference = "Stop"
$file = [IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Backup file not found" }
if ($ExpectedSha256 -and ($ExpectedSha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $ExpectedSha256)) { throw "Backup digest mismatch" }
$stream = [IO.File]::OpenRead($file)
try {
    $header = [byte[]]::new(5)
    if ($stream.Read($header, 0, 5) -ne 5 -or [Text.Encoding]::ASCII.GetString($header) -ne "PGDMP") { throw "Invalid PostgreSQL custom archive header" }
} finally { $stream.Dispose() }
$container = if ($env:AETHERIS_POSTGRES_CONTAINER) { $env:AETHERIS_POSTGRES_CONTAINER } else { "aetheris-postgres-1" }
$db = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "aetheris" }
$user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "aetheris" }
$action = if ($Clean) { "Drop existing archive objects and restore PostgreSQL in one transaction" } else { "Restore PostgreSQL in one transaction" }
if (-not $PSCmdlet.ShouldProcess("$container/$db", $action)) { return }
function Invoke-CheckedDocker([string[]]$Arguments) {
    $result = @(& docker @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Docker restore operation failed (exit $LASTEXITCODE)" }
    return $result
}
$tempDirectory = $null
$cleanupFailed = $false
try {
    $tempDirectory = ((Invoke-CheckedDocker @("exec", $container, "mktemp", "-d", "/tmp/aetheris-pg.XXXXXXXXXXXX")) -join "").Trim()
    if ($tempDirectory -notmatch '^/tmp/aetheris-pg\.[A-Za-z0-9]+$') { $tempDirectory = $null; throw "Invalid container temporary directory" }
    $containerFile = "$tempDirectory/backup.dump"
    Invoke-CheckedDocker @("cp", $file, "${container}:$containerFile") | Out-Null
    Invoke-CheckedDocker @("exec", $container, "pg_restore", "--list", $containerFile) | Out-Null
    $restoreArguments = @("exec", $container, "pg_restore", "-U", $user, "-d", $db, "--exit-on-error", "--single-transaction")
    if ($Clean) { $restoreArguments += @("--clean", "--if-exists") }
    $restoreArguments += $containerFile
    Invoke-CheckedDocker $restoreArguments | Out-Null
} finally {
    if ($tempDirectory) {
        try {
            Invoke-CheckedDocker @("exec", $container, "rm", "-f", "--", "$tempDirectory/backup.dump") | Out-Null
            Invoke-CheckedDocker @("exec", $container, "rmdir", "--", $tempDirectory) | Out-Null
        } catch { $cleanupFailed = $true; Write-Warning "Container restore temporary-file cleanup failed" }
    }
}
if ($cleanupFailed) { throw "Restore completed but temporary-file cleanup failed" }
Write-Output "Restore completed from $file"
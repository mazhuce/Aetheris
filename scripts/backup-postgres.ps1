[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$OutputDir = "./backups",
    [string]$OutputFile = "",
    [switch]$Force
)
$ErrorActionPreference = "Stop"
$container = if ($env:AETHERIS_POSTGRES_CONTAINER) { $env:AETHERIS_POSTGRES_CONTAINER } else { "aetheris-postgres-1" }
$db = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "aetheris" }
$user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "aetheris" }
$file = if ($OutputFile) { [IO.Path]::GetFullPath($OutputFile) } else { [IO.Path]::GetFullPath((Join-Path $OutputDir ("aetheris-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + ".dump"))) }
if ((Test-Path -LiteralPath $file) -and -not $Force) { throw "Backup exists; use -Force to authorize replacement" }
$action = if (Test-Path -LiteralPath $file) { "Overwrite PostgreSQL backup" } else { "Create PostgreSQL backup" }
if (-not $PSCmdlet.ShouldProcess($file, $action)) { return }
function Invoke-CheckedDocker([string[]]$Arguments) {
    $result = @(& docker @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Docker backup operation failed (exit $LASTEXITCODE)" }
    return $result
}
$tempDirectory = $null
$partial = $file + "." + [guid]::NewGuid().ToString("N") + ".partial"
$cleanupFailed = $false
try {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
    $tempDirectory = ((Invoke-CheckedDocker @("exec", $container, "mktemp", "-d", "/tmp/aetheris-pg.XXXXXXXXXXXX")) -join "").Trim()
    if ($tempDirectory -notmatch '^/tmp/aetheris-pg\.[A-Za-z0-9]+$') { $tempDirectory = $null; throw "Invalid container temporary directory" }
    $containerFile = "$tempDirectory/backup.dump"
    Invoke-CheckedDocker @("exec", $container, "pg_dump", "-Fc", "-U", $user, "-d", $db, "-f", $containerFile) | Out-Null
    Invoke-CheckedDocker @("exec", $container, "pg_restore", "--list", $containerFile) | Out-Null
    Invoke-CheckedDocker @("cp", "${container}:$containerFile", $partial) | Out-Null
    $stream = [IO.File]::OpenRead($partial)
    try {
        $header = [byte[]]::new(5)
        if ($stream.Read($header, 0, 5) -ne 5 -or [Text.Encoding]::ASCII.GetString($header) -ne "PGDMP") { throw "Invalid PostgreSQL custom archive header" }
    } finally { $stream.Dispose() }
    # Publish atomically only after dump, archive-list and raw-byte copy succeeded.
    Move-Item -LiteralPath $partial -Destination $file -Force:$Force
} finally {
    if ($tempDirectory) {
        try {
            Invoke-CheckedDocker @("exec", $container, "rm", "-f", "--", "$tempDirectory/backup.dump") | Out-Null
            Invoke-CheckedDocker @("exec", $container, "rmdir", "--", $tempDirectory) | Out-Null
        } catch { $cleanupFailed = $true; Write-Warning "Container backup temporary-file cleanup failed" }
    }
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
}
if ($cleanupFailed) { throw "Backup exists but temporary-file cleanup failed" }
Write-Output "Backup written to $file"
Write-Output ("SHA256 " + (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())
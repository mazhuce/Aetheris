[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^aetheris-r1-empty-gate-[a-z0-9-]+$')]
    [string]$ProjectName,
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$ComposeFilePath = "infrastructure/lakehouse/docker-compose.r1.yml",
    [switch]$RemoveVolumes,
    [switch]$AllowVolumeRemoval
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot $EnvFilePath
$composeFile = Join-Path $repoRoot $ComposeFilePath
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw "R1 env file is missing: $envFile" }
if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) { throw "R1 Compose file is missing: $composeFile" }
if ($RemoveVolumes -and -not $AllowVolumeRemoval) {
    throw "RemoveVolumes requires -AllowVolumeRemoval because it removes only the explicitly named fresh project volumes"
}

$dockerVersion = @(& docker version --format "{{.Server.Version}}" 2>&1)
if ($LASTEXITCODE -ne 0 -or -not $dockerVersion) {
    $diagnostic = (($dockerVersion | ForEach-Object { $_.ToString() }) -join " ").Trim()
    throw "Docker engine unavailable for fresh-project cleanup: $diagnostic"
}

$composeArgs = @("compose", "--project-name", $ProjectName, "--profile", "r1", "-f", $composeFile, "--env-file", $envFile)
$containersBefore = @(& docker @composeArgs ps -aq 2>$null | Where-Object { $_ -and ([string]$_).Trim() })
$volumesBefore = @(& docker volume ls --filter "label=com.docker.compose.project=$ProjectName" --format "{{.Name}}" 2>$null | Where-Object { $_ -and ([string]$_).Trim() })
$action = if ($RemoveVolumes) { "down -v --remove-orphans" } else { "down --remove-orphans" }
$removed = $false
if ($containersBefore.Count -gt 0 -or $volumesBefore.Count -gt 0) {
    if ($PSCmdlet.ShouldProcess($ProjectName, $action)) {
        $downArgs = if ($RemoveVolumes) { @("down", "-v", "--remove-orphans") } else { @("down", "--remove-orphans") }
        & docker @composeArgs @downArgs
        if ($LASTEXITCODE -ne 0) { throw "Fresh R1 project cleanup failed for $ProjectName" }
        $removed = $true
    }
}

[ordered]@{
    schemaVersion = 1
    project = $ProjectName
    status = if ($removed) { "cleaned" } else { "already_clean" }
    dockerServerVersion = ([string]($dockerVersion | Select-Object -Last 1)).Trim()
    containersBefore = @($containersBefore)
    volumesBefore = @($volumesBefore)
    removeVolumes = [bool]$RemoveVolumes
    volumeRemovalAuthorized = [bool]$AllowVolumeRemoval
    scope = "explicit-compose-project-only"
} | ConvertTo-Json -Depth 8

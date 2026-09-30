[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$EnvFilePath = "configs/lakehouse/.env.r1",
    [string]$FixtureOutput = "artifacts/r1-fixture",
    [string]$GatewayEndpoint = "http://127.0.0.1:18083",
    [string]$SourceContainer = "",
    [string]$ProjectName = "aetheris-r1-lakehouse",
    [string]$TrinoContainer = "aetheris-r1-lakehouse-trino-1",
    [string]$FlinkJobManagerEndpoint = "http://127.0.0.1:18081",
    [int]$SnapshotTimeoutSeconds = 180,
    [int]$MutationTimeoutSeconds = 180,
    [switch]$ResetSource,
    [switch]$RestartTaskManagerAfterSnapshot,
    [string]$ComposeFilePath = "infrastructure/lakehouse/docker-compose.r1.yml",
    [string]$ReportPath
)

$arguments = @{
    SourceKind = "mysql"
    EnvFilePath = $EnvFilePath
    FixtureOutput = $FixtureOutput
    GatewayEndpoint = $GatewayEndpoint
    SourceContainer = $SourceContainer
    ProjectName = $ProjectName
    TrinoContainer = $TrinoContainer
    FlinkJobManagerEndpoint = $FlinkJobManagerEndpoint
    SnapshotTimeoutSeconds = $SnapshotTimeoutSeconds
    MutationTimeoutSeconds = $MutationTimeoutSeconds
    ResetSource = $ResetSource
    RestartTaskManagerAfterSnapshot = $RestartTaskManagerAfterSnapshot
    ComposeFilePath = $ComposeFilePath
}
if ($ReportPath) { $arguments.ReportPath = $ReportPath }
& pwsh -NoProfile -File (Join-Path $PSScriptRoot "r2-postgres-cdc-smoke.ps1") @arguments
exit $LASTEXITCODE

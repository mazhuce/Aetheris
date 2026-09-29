[CmdletBinding()]
param(
    [string]$ManifestPath = "configs/datasource-smoke.example.json",
    [string]$MetadataBaseUrl = "http://127.0.0.1:8082",
    [string]$AuthorizationHeader = "",
    [int]$TimeoutSeconds = 40,
    [switch]$RequireAll
)

$ErrorActionPreference = "Stop"
if ($TimeoutSeconds -lt 1) { throw "TimeoutSeconds must be positive" }
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "Datasource smoke manifest is missing: $ManifestPath"
}

$entries = @(Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json)
if ($entries.Count -eq 0) { throw "Datasource smoke manifest is empty" }
$headers = @{}
if ($AuthorizationHeader.Trim()) { $headers.Authorization = $AuthorizationHeader.Trim() }
$endpoint = "$($MetadataBaseUrl.TrimEnd('/'))/api/metadata/datasources/test"
$results = [System.Collections.Generic.List[object]]::new()

foreach ($entry in $entries) {
    $name = [string]$entry.name
    $type = [string]$entry.type
    $required = [bool]$entry.required
    $passwordEnv = [string]$entry.passwordEnv
    $password = if ($passwordEnv.Trim()) { [Environment]::GetEnvironmentVariable($passwordEnv) } else { $null }

    # The manifest contains environment-variable names, never credentials. A
    # missing optional environment is reported as skipped, not sent as an
    # accidental empty password to a remote server.
    if ($passwordEnv.Trim() -and [string]::IsNullOrWhiteSpace($password)) {
        $results.Add([ordered]@{
                name = $name; type = $type; status = if ($required) { "failed" } else { "skipped" }
                required = $required; message = "password environment variable is not set"
            })
        continue
    }

    $body = [ordered]@{
        name = $name
        type = $type
        host = [string]$entry.host
        port = if ($null -eq $entry.port) { $null } else { [int]$entry.port }
        databaseName = [string]$entry.databaseName
        username = [string]$entry.username
        password = if ($null -eq $password) { "" } else { [string]$password }
        jdbcUrl = if ($entry.jdbcUrl) { [string]$entry.jdbcUrl } else { $null }
        connectionParams = if ($entry.connectionParams) { [string]$entry.connectionParams } else { $null }
    }
    try {
        $response = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers `
            -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 8) -TimeoutSec $TimeoutSeconds
        $results.Add([ordered]@{
                name = $name; type = $type; required = $required
                status = if ($response.success) { "passed" } else { "failed" }
                latencyMs = $response.latencyMs
                message = [string]$response.message
            })
    } catch {
        # Never print the request body or exception containing a connection URL;
        # only retain the bounded entry name/type and transport failure class.
        $results.Add([ordered]@{
                name = $name; type = $type; required = $required; status = "failed"
                message = "metadata connection-test request failed"
            })
    }
}

$failed = @($results | Where-Object { $_.status -eq "failed" -or ($RequireAll -and $_.status -eq "skipped") })
$summary = [ordered]@{
    status = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    endpoint = $endpoint
    total = $results.Count
    passed = @($results | Where-Object status -eq "passed").Count
    skipped = @($results | Where-Object status -eq "skipped").Count
    failed = @($results | Where-Object status -eq "failed").Count
    results = @($results)
}
Write-Output ($summary | ConvertTo-Json -Depth 8 -Compress)
if ($failed.Count -gt 0) { exit 1 }

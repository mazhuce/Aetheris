[CmdletBinding()]
param(
    [string]$GatewayEndpoint = "http://127.0.0.1:18083",
    [string]$JobManagerEndpoint = "http://127.0.0.1:18081",
    [Parameter(Mandatory = $true)]
    [string]$SqlFile,
    [string]$EnvFile,
    [switch]$KeepInsertRunning,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

function Split-SqlStatements {
    param([string]$Sql)
    $statements = [System.Collections.Generic.List[string]]::new()
    $buffer = [System.Text.StringBuilder]::new()
    $singleQuote = $false
    $doubleQuote = $false
    $lineComment = $false
    $blockComment = $false
    for ($index = 0; $index -lt $Sql.Length; $index++) {
        $current = $Sql[$index]
        $next = if ($index + 1 -lt $Sql.Length) { $Sql[$index + 1] } else { [char]0 }
        if ($lineComment) {
            [void]$buffer.Append($current)
            if ($current -eq "`n") { $lineComment = $false }
            continue
        }
        if ($blockComment) {
            [void]$buffer.Append($current)
            if ($current -eq '*' -and $next -eq '/') {
                [void]$buffer.Append($next)
                $index++
                $blockComment = $false
            }
            continue
        }
        if (-not $singleQuote -and -not $doubleQuote -and $current -eq '-' -and $next -eq '-') {
            [void]$buffer.Append($current)
            [void]$buffer.Append($next)
            $index++
            $lineComment = $true
            continue
        }
        if (-not $singleQuote -and -not $doubleQuote -and $current -eq '/' -and $next -eq '*') {
            [void]$buffer.Append($current)
            [void]$buffer.Append($next)
            $index++
            $blockComment = $true
            continue
        }
        if ($current -eq "'" -and -not $doubleQuote) {
            if ($singleQuote -and $next -eq "'") {
                [void]$buffer.Append($current)
                [void]$buffer.Append($next)
                $index++
                continue
            }
            $singleQuote = -not $singleQuote
        } elseif ($current -eq '"' -and -not $singleQuote) {
            $doubleQuote = -not $doubleQuote
        }
        if ($current -eq ';' -and -not $singleQuote -and -not $doubleQuote) {
            $statement = $buffer.ToString().Trim()
            if ($statement) { $statements.Add($statement) }
            [void]$buffer.Clear()
            continue
        }
        [void]$buffer.Append($current)
    }
    $last = $buffer.ToString().Trim()
    if ($last) { $statements.Add($last) }
    return $statements
}

function Get-OperationStatus {
    param([object]$Body)
    if ($null -eq $Body) { return "" }
    if ($Body.status -and $Body.status.type) { return ([string]$Body.status.type).ToUpperInvariant() }
    if ($Body.status) { return ([string]$Body.status).ToUpperInvariant() }
    if ($Body.type) { return ([string]$Body.type).ToUpperInvariant() }
    return ""
}

function Get-FlinkJobId {
    param([object]$Body)
    if ($null -eq $Body) { return $null }
    $json = $Body | ConvertTo-Json -Depth 16 -Compress
    $match = [regex]::Match($json, '(?i)(?:job\s*id|jobId)[^0-9a-f]{0,32}([0-9a-f]{32})')
    if ($match.Success) { return $match.Groups[1].Value.ToLowerInvariant() }
    $fallback = [regex]::Match($json, '(?i)\b([0-9a-f]{32})\b')
    if ($fallback.Success) { return $fallback.Groups[1].Value.ToLowerInvariant() }
    return $null
}

function Wait-FlinkJobFinished {
    param([string]$JobId, [datetime]$Deadline)
    $jobManager = $JobManagerEndpoint.TrimEnd('/')
    $state = ""
    do {
        $job = Invoke-RestMethod -Method Get -Uri "$jobManager/jobs/$JobId"
        $state = ([string]$job.state).ToUpperInvariant()
        if ($state -in @("FINISHED", "FAILED", "CANCELED", "SUSPENDED")) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $Deadline)
    if ($state -ne "FINISHED") {
        throw "Flink job $JobId did not finish successfully (state=$state)"
    }
    return $state
}

if (-not (Test-Path -LiteralPath $SqlFile -PathType Leaf)) { throw "Flink SQL file is missing: $SqlFile" }
if ($TimeoutSeconds -lt 1) { throw "TimeoutSeconds must be positive" }
$endpoint = $GatewayEndpoint.TrimEnd('/')
$sqlText = Get-Content -Raw -LiteralPath $SqlFile
if ($EnvFile) {
    $envValues = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') { $envValues[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'") }
    }
    foreach ($key in @("R1_SOURCE_POSTGRES_USER", "R1_SOURCE_POSTGRES_PASSWORD")) {
        if ([string]::IsNullOrWhiteSpace([string]$envValues[$key])) { throw "R1 source credential $key is missing from $EnvFile" }
        $sqlText = $sqlText.Replace("`${$key}", ([string]$envValues[$key]).Replace("'", "''"))
    }
}
$statements = Split-SqlStatements $sqlText
if ($statements.Count -eq 0) { throw "Flink SQL file contains no statements: $SqlFile" }

$session = $null
$keepSession = $false
$results = [System.Collections.Generic.List[object]]::new()
try {
    $sessionBody = Invoke-RestMethod -Method Post -Uri "$endpoint/v1/sessions" -ContentType "application/json" -Body "{}"
    $session = [string]$sessionBody.sessionHandle
    if ([string]::IsNullOrWhiteSpace($session)) { throw "Flink SQL Gateway did not return a session handle" }
    $statementIndex = 0
    foreach ($statement in $statements) {
        $statementIndex++
        $body = @{ statement = $statement } | ConvertTo-Json -Compress
        $operationBody = Invoke-RestMethod -Method Post -Uri "$endpoint/v1/sessions/$session/statements" -ContentType "application/json" -Body $body
        $operation = [string]$operationBody.operationHandle
        if ([string]::IsNullOrWhiteSpace($operation)) { throw "Flink SQL Gateway did not return an operation handle" }
        if ($KeepInsertRunning -and $statement -match '(?is)^\s*INSERT\s+INTO\b') {
            $keepSession = $true
            $statusBody = Invoke-RestMethod -Method Get -Uri "$endpoint/v1/sessions/$session/operations/$operation/status"
            $runningSummary = [ordered]@{
                status = "running"
                statementCount = $statements.Count
                operation = $operation
                session = $session
                operationStatus = Get-OperationStatus $statusBody
            }
            Write-Output ($runningSummary | ConvertTo-Json -Depth 6 -Compress)
            return
        }
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $status = ""
        do {
            Start-Sleep -Milliseconds 500
            $statusBody = Invoke-RestMethod -Method Get -Uri "$endpoint/v1/sessions/$session/operations/$operation/status"
            $status = Get-OperationStatus $statusBody
        } while ($status -notin @("FINISHED", "CANCELED", "ERROR", "FAILED") -and (Get-Date) -lt $deadline)
        if ($status -ne "FINISHED") {
            $detail = ""
            try { $detail = (Invoke-RestMethod -Uri "$endpoint/v1/sessions/$session/operations/$operation/result/0?rowFormat=JSON" | ConvertTo-Json -Depth 8 -Compress) } catch { }
            throw "Flink SQL operation $statementIndex failed or timed out (status=$status, operation=$operation, detail=$detail)"
        }
        $resultEvidence = [ordered]@{ operation = $operation; status = $status }
        if ($statement -match '(?is)^\s*INSERT\s+INTO\b') {
            $resultBody = Invoke-RestMethod -Method Get -Uri "$endpoint/v1/sessions/$session/operations/$operation/result/0?rowFormat=JSON"
            $jobId = Get-FlinkJobId $resultBody
            if ([string]::IsNullOrWhiteSpace($jobId)) {
                throw "Flink INSERT completed without a verifiable Job ID"
            }
            $resultEvidence.jobId = $jobId
            $resultEvidence.jobStatus = Wait-FlinkJobFinished -JobId $jobId -Deadline $deadline
        }
        $results.Add($resultEvidence)
        try { Invoke-RestMethod -Method Delete -Uri "$endpoint/v1/sessions/$session/operations/$operation/close" | Out-Null } catch { }
    }
    $summary = [ordered]@{ status = "passed"; statementCount = $statements.Count; operations = @($results) }
    Write-Output ($summary | ConvertTo-Json -Depth 6 -Compress)
} finally {
    if ($session -and -not $keepSession) {
        try { Invoke-RestMethod -Method Delete -Uri "$endpoint/v1/sessions/$session" | Out-Null } catch { }
    }
}

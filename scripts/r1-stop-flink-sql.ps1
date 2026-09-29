[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [string]$Session,
    [Parameter(Mandatory = $true)]
    [string]$Operation,
    [string]$GatewayEndpoint = "http://127.0.0.1:18083"
)

$ErrorActionPreference = "Stop"
$endpoint = $GatewayEndpoint.TrimEnd('/')
if ($PSCmdlet.ShouldProcess("Flink SQL operation $Operation", "cancel and close")) {
    $cancel = Invoke-RestMethod -Method Post -Uri "$endpoint/v1/sessions/$Session/operations/$Operation/cancel" -ContentType "application/json" -Body "{}"
    try { Invoke-RestMethod -Method Delete -Uri "$endpoint/v1/sessions/$Session/operations/$Operation/close" | Out-Null } catch { }
    try { Invoke-RestMethod -Method Delete -Uri "$endpoint/v1/sessions/$Session" | Out-Null } catch { }
    [ordered]@{ status = "canceled"; session = $Session; operation = $Operation } | ConvertTo-Json -Compress
}

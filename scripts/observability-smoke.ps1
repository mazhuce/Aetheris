param([string]$BaseHost = 'localhost')
$ErrorActionPreference = 'Stop'
$ports = @(8080,8087,8081,8082,8083,8084,8085,8088,8086,8089)
$failed = @()
foreach ($port in $ports) {
  $uri = "http://$BaseHost`:$port/actuator/prometheus"
  try {
    $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 8
    if ($response.StatusCode -ne 200 -or $response.Content -notmatch '(?m)^jvm_') { $failed += $uri }
    else { Write-Output "OK  $uri" }
  } catch { $failed += $uri; Write-Output "FAIL $uri ($($_.Exception.Message))" }
}
try {
  $ready = Invoke-WebRequest -Uri "http://$BaseHost`:9090/-/ready" -UseBasicParsing -TimeoutSec 8
  if ($ready.StatusCode -eq 200) { Write-Output "OK  http://$BaseHost`:9090/-/ready" } else { $failed += 'prometheus-ready' }
} catch { $failed += 'prometheus-ready' }
if ($failed.Count -gt 0) { Write-Error ("Observability smoke failed: " + ($failed -join ', ')); exit 1 }
Write-Output "Observability smoke passed for $($ports.Count) services and Prometheus"

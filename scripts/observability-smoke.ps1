param([string]$BaseHost = 'localhost')
$ErrorActionPreference = 'Stop'
# 服务端口只在容器网络内，宿主机未映射；在容器内探测指标端点更可靠。
$services = @(
  @{ Name = 'gateway';            Container = 'aetheris-gateway-1';            Port = 8080 },
  @{ Name = 'system-service';     Container = 'aetheris-system-service-1';     Port = 8081 },
  @{ Name = 'metadata-service';   Container = 'aetheris-metadata-service-1';   Port = 8082 },
  @{ Name = 'quality-service';    Container = 'aetheris-quality-service-1';    Port = 8083 },
  @{ Name = 'ingestion-service';  Container = 'aetheris-ingestion-service-1';  Port = 8084 },
  @{ Name = 'governance-service'; Container = 'aetheris-governance-service-1'; Port = 8085 },
  @{ Name = 'ai-agent';           Container = 'aetheris-ai-agent-1';           Port = 8086 },
  @{ Name = 'auth-service';       Container = 'aetheris-auth-service-1';       Port = 8087 },
  @{ Name = 'workflow-service';   Container = 'aetheris-workflow-service-1';   Port = 8088 },
  @{ Name = 'serving-service';    Container = 'aetheris-serving-service-1';    Port = 8089 },
  @{ Name = 'learning-service';   Container = 'aetheris-learning-service-1';   Port = 8090 },
  @{ Name = 'mcp-server';         Container = 'aetheris-mcp-server-1';         Port = 8091 }
)
$failed = @()
foreach ($service in $services) {
  $label = "$($service.Name) ($($service.Container):$($service.Port))"
  $output = & docker exec $service.Container sh -c "wget -qO- http://localhost:$($service.Port)/actuator/prometheus" 2>$null
  $text = ($output -join "`n")
  if ($LASTEXITCODE -ne 0 -or $text -notmatch '(?m)^jvm_') {
    $failed += $label
    Write-Output "FAIL $label"
  } else {
    Write-Output "OK   $label"
  }
}
try {
  $ready = Invoke-WebRequest -Uri "http://$BaseHost`:9090/-/ready" -UseBasicParsing -TimeoutSec 8
  if ($ready.StatusCode -eq 200) { Write-Output "OK   prometheus-ready" } else { $failed += 'prometheus-ready' }
} catch { $failed += 'prometheus-ready' }
if ($failed.Count -gt 0) { Write-Error ("Observability smoke failed: " + ($failed -join ', ')); exit 1 }
Write-Output "Observability smoke passed for $($services.Count) services and Prometheus"
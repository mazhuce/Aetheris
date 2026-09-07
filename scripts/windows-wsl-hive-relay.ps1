param(
    [ValidateSet('start', 'stop', 'status', 'health', 'run')]
    [string]$Action = 'start',
    [string]$Distribution = 'Ubuntu',
    [string]$ListenAddress = '',
    [string]$TargetAddress = '',
    [int]$Port = 10000
)

$runtimeDirectory = Join-Path $env:TEMP 'aetheris-hive-relay'
$pidFile = Join-Path $runtimeDirectory 'relay.pid'
$stateFile = Join-Path $runtimeDirectory 'relay.state'

function Get-RelayProcess {
    if (-not (Test-Path -LiteralPath $pidFile)) {
        return $null
    }
    $relayPid = [int](Get-Content -LiteralPath $pidFile -Raw)
    $process = Get-Process -Id $relayPid -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    # A persisted PID can belong to another process after Windows restarts.
    $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $relayPid" -ErrorAction Stop).CommandLine
    if (-not $commandLine -or
        $commandLine.IndexOf($PSCommandPath, [System.StringComparison]::OrdinalIgnoreCase) -lt 0 -or
        $commandLine -notmatch '-Action\s+run') {
        throw 'relay PID 已被其他进程复用；请核对并移除过期的 relay.pid，未操作该进程。'
    }
    return $process
}

function Test-RelayPort([string]$Address, [int]$TargetPort, [int]$TimeoutMs = 3000) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connectTask = $client.ConnectAsync($Address, $TargetPort)
        return $connectTask.Wait($TimeoutMs) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Resolve-ListenAddress {
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop)
    # Docker Desktop's WSL network can reach the WSL adapter while a different
    # Hyper-V Default Switch subnet may be unroutable from its containers.
    $address = $addresses | Where-Object InterfaceAlias -Like '*WSL*' |
        Select-Object -First 1 -ExpandProperty IPAddress
    if (-not $address) {
        $address = $addresses | Where-Object InterfaceAlias -Like '*Default Switch*' |
            Select-Object -First 1 -ExpandProperty IPAddress
    }
    if (-not $address) {
        throw '未找到 WSL/Hyper-V IPv4 地址，请显式指定 -ListenAddress。'
    }
    return $address
}

function Resolve-WslAddress {
    $addresses = (& wsl -d $Distribution -- hostname -I).Trim().Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
    $address = $addresses | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' } | Select-Object -First 1
    if (-not $address) {
        throw "未找到 WSL 发行版 $Distribution 的 IPv4 地址"
    }
    return $address
}

if ($Action -eq 'run') {
    New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
    "$ListenAddress`:$Port -> $TargetAddress`:$Port" | Set-Content -LiteralPath $stateFile -Encoding utf8
    Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

public static class AetherisHiveRelay
{
    public static async Task RunAsync(string listenAddress, string targetAddress, int port)
    {
        var listener = new TcpListener(IPAddress.Parse(listenAddress), port);
        listener.Start();
        while (true)
        {
            var client = await listener.AcceptTcpClientAsync();
            _ = RelayAsync(client, targetAddress, port);
        }
    }

    private static async Task RelayAsync(TcpClient client, string targetAddress, int port)
    {
        using (client)
        using (var target = new TcpClient())
        {
            try
            {
                await target.ConnectAsync(targetAddress, port);
                var clientStream = client.GetStream();
                var targetStream = target.GetStream();
                var toTarget = clientStream.CopyToAsync(targetStream);
                var toClient = targetStream.CopyToAsync(clientStream);
                await Task.WhenAny(toTarget, toClient);
            }
            catch
            {
            }
        }
    }
}
'@
    [AetherisHiveRelay]::RunAsync($ListenAddress, $TargetAddress, $Port).GetAwaiter().GetResult()
}

if ($Action -eq 'stop') {
    $process = Get-RelayProcess
    if ($process) {
        Stop-Process -Id $process.Id
        Write-Output "WSL Hive relay stopped (PID $($process.Id))"
    } else {
        Write-Output 'WSL Hive relay is not running'
    }
    Remove-Item -LiteralPath $pidFile, $stateFile -Force -ErrorAction SilentlyContinue
    exit 0
}

if ($Action -eq 'status') {
    $process = Get-RelayProcess
    if ($process) {
        $state = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw } else { 'starting' }
        Write-Output "WSL Hive relay running (PID $($process.Id)): $($state.Trim())"
    } else {
        Write-Output 'WSL Hive relay is not running'
    }
    exit 0
}

if ($Action -eq 'health') {
    $process = Get-RelayProcess
    if (-not $process -or -not (Test-Path -LiteralPath $stateFile)) {
        Write-Output 'relay: unavailable (process/state missing)'
        exit 1
    }
    $state = (Get-Content -LiteralPath $stateFile -Raw).Trim()
    if ($state -notmatch '^([\d.]+):(\d+) -> ([\d.]+):(\d+)$') {
        throw 'relay.state 格式无法识别，请重新启动 relay。'
    }
    $relayAddress, $relayPort, $targetHost, $targetPort = $Matches[1], [int]$Matches[2], $Matches[3], [int]$Matches[4]
    $currentListenAddress = if ($ListenAddress) { $ListenAddress } else { Resolve-ListenAddress }
    $currentTargetAddress = if ($TargetAddress) { $TargetAddress } else { Resolve-WslAddress }
    if ($relayAddress -ne $currentListenAddress -or $targetHost -ne $currentTargetAddress) {
        Write-Output "relay: stale address ($state); stop/start relay and update datasource host"
        exit 1
    }
    $listening = Test-RelayPort $relayAddress $relayPort
    $targetReachable = Test-RelayPort $targetHost $targetPort
    Write-Output "relay listener: $listening; WSL Spark Thrift: $targetReachable ($state)"
    Write-Output 'TCP checks only; platform JDBC probe is still required.'
    if (-not ($listening -and $targetReachable)) { exit 1 }
    exit 0
}

$resolvedListenAddress = if ($ListenAddress) { $ListenAddress } else { Resolve-ListenAddress }
$resolvedTargetAddress = if ($TargetAddress) { $TargetAddress } else { Resolve-WslAddress }
if (-not (Test-RelayPort $resolvedTargetAddress $Port)) {
    throw "WSL 目标 $resolvedTargetAddress`:$Port 不可达，请先恢复 Spark Thrift Server。"
}
$existing = Get-RelayProcess
if ($existing) {
    $expectedState = "$resolvedListenAddress`:$Port -> $resolvedTargetAddress`:$Port"
    if (-not (Test-Path -LiteralPath $stateFile) -or (Get-Content -LiteralPath $stateFile -Raw).Trim() -ne $expectedState) {
        throw 'relay 地址已变化。请执行 stop、start，然后同步数据源主机地址。'
    }
    Write-Output "WSL Hive relay already running (PID $($existing.Id))"
    exit 0
}

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$arguments = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', $PSCommandPath,
    '-Action', 'run',
    '-ListenAddress', $resolvedListenAddress,
    '-TargetAddress', $resolvedTargetAddress,
    '-Port', $Port
)
$process = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $arguments -WindowStyle Hidden -PassThru
$process.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Milliseconds 100
    $relayProcess = Get-RelayProcess
    if (-not $relayProcess) { break }
    if (Test-RelayPort $resolvedListenAddress $Port 200) {
        $ready = $true
        break
    }
}
if (-not $ready) {
    Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pidFile, $stateFile -Force -ErrorAction SilentlyContinue
    throw 'WSL Hive relay 启动失败：监听端口未就绪。'
}
Write-Output "WSL Hive relay started (PID $($process.Id)): $resolvedListenAddress`:$Port -> $resolvedTargetAddress`:$Port"

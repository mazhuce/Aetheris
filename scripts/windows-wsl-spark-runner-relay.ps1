param(
    [ValidateSet('start', 'stop', 'status', 'health', 'run')]
    [string]$Action = 'start',
    [string]$Distribution = 'Ubuntu',
    [string]$ListenAddress = '',
    [string]$TargetAddress = '',
    [int]$Port = 18081
)

$runtimeDirectory = Join-Path $env:TEMP 'aetheris-spark-runner-relay'
$pidFile = Join-Path $runtimeDirectory 'relay.pid'
$stateFile = Join-Path $runtimeDirectory 'relay.state'

function Get-OwnedProcess {
    if (-not (Test-Path -LiteralPath $pidFile)) { return $null }
    $relayPid = [int](Get-Content -LiteralPath $pidFile -Raw)
    $process = Get-Process -Id $relayPid -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $command = (Get-CimInstance Win32_Process -Filter "ProcessId = $relayPid" -ErrorAction Stop).CommandLine
    if (-not $command -or $command.IndexOf($PSCommandPath, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or $command -notmatch '-Action\s+run') {
        throw 'spark runner relay PID 已被其他进程复用；未操作该进程。'
    }
    return $process
}

function Test-Port([string]$Address, [int]$TargetPort, [int]$TimeoutMs = 3000) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($Address, $TargetPort)
        return $task.Wait($TimeoutMs) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Resolve-Listen {
    $address = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object InterfaceAlias -Like '*WSL*' | Select-Object -First 1 -ExpandProperty IPAddress
    if (-not $address) {
        $address = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object InterfaceAlias -Like '*Default Switch*' | Select-Object -First 1 -ExpandProperty IPAddress
    }
    if (-not $address) { throw '未找到 WSL/Hyper-V IPv4 地址。' }
    return $address
}

function Resolve-Target {
    $address = (& wsl -d $Distribution -- hostname -I).Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries) |
        Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' } | Select-Object -First 1
    if (-not $address) { throw "未找到 WSL 发行版 $Distribution 的 IPv4 地址" }
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
public static class AetherisSparkRunnerRelay {
    public static async Task RunAsync(string listen, string target, int port) {
        var listener = new TcpListener(IPAddress.Parse(listen), port);
        listener.Start();
        while (true) {
            var client = await listener.AcceptTcpClientAsync();
            _ = RelayAsync(client, target, port);
        }
    }
    private static async Task RelayAsync(TcpClient client, string targetHost, int port) {
        using (client)
        using (var target = new TcpClient()) {
            try {
                await target.ConnectAsync(targetHost, port);
                var left = client.GetStream();
                var right = target.GetStream();
                await Task.WhenAny(left.CopyToAsync(right), right.CopyToAsync(left));
            } catch { }
        }
    }
}
'@
    [AetherisSparkRunnerRelay]::RunAsync($ListenAddress, $TargetAddress, $Port).GetAwaiter().GetResult()
}

if ($Action -eq 'stop') {
    $process = Get-OwnedProcess
    if ($process) { Stop-Process -Id $process.Id }
    Remove-Item -LiteralPath $pidFile, $stateFile -Force -ErrorAction SilentlyContinue
    Write-Output 'Spark runner relay stopped'
    exit 0
}

if ($Action -eq 'status') {
    $process = Get-OwnedProcess
    if ($process) { Write-Output "Spark runner relay running (PID $($process.Id)): $((Get-Content $stateFile -Raw).Trim())" }
    else { Write-Output 'Spark runner relay is not running' }
    exit 0
}

if ($Action -eq 'health') {
    $process = Get-OwnedProcess
    if (-not $process -or -not (Test-Path $stateFile)) { Write-Output 'relay: unavailable'; exit 1 }
    $state = (Get-Content $stateFile -Raw).Trim()
    Write-Output "relay: $state; reachable=$(Test-Port ($state -split ':')[0] $Port)"
    exit 0
}

$resolvedListen = if ($ListenAddress) { $ListenAddress } else { Resolve-Listen }
$resolvedTarget = if ($TargetAddress) { $TargetAddress } else { Resolve-Target }
if ($resolvedListen -in @('0.0.0.0', '::')) { throw '拒绝在通配地址启动 relay。' }
if (-not (Test-Port $resolvedTarget $Port)) { throw "WSL Spark runner $resolvedTarget`:$Port 不可达。" }
$existing = Get-OwnedProcess
if ($existing) { Write-Output "Spark runner relay already running (PID $($existing.Id))"; exit 0 }

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath,
    '-Action', 'run', '-ListenAddress', $resolvedListen, '-TargetAddress', $resolvedTarget, '-Port', $Port)
$process = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $arguments -WindowStyle Hidden -PassThru
$process.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Milliseconds 100
    if (Test-Port $resolvedListen $Port 200) {
        Write-Output "Spark runner relay started (PID $($process.Id)): $resolvedListen`:$Port -> $resolvedTarget`:$Port"
        exit 0
    }
}
Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pidFile, $stateFile -Force -ErrorAction SilentlyContinue
throw 'Spark runner relay 启动失败。'

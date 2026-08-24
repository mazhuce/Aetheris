param(
    [ValidateSet('start', 'stop', 'status', 'run')]
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
    return Get-Process -Id $relayPid -ErrorAction SilentlyContinue
}

function Resolve-ListenAddress {
    $address = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object InterfaceAlias -Like '*Default Switch*' |
        Select-Object -First 1 -ExpandProperty IPAddress
    if (-not $address) {
        throw '未找到 Hyper-V Default Switch IPv4 地址'
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

$existing = Get-RelayProcess
if ($existing) {
    Write-Output "WSL Hive relay already running (PID $($existing.Id))"
    exit 0
}

$resolvedListenAddress = if ($ListenAddress) { $ListenAddress } else { Resolve-ListenAddress }
$resolvedTargetAddress = if ($TargetAddress) { $TargetAddress } else { Resolve-WslAddress }
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
Start-Sleep -Milliseconds 500
if (-not (Get-RelayProcess)) {
    throw 'WSL Hive relay 启动失败'
}
Write-Output "WSL Hive relay started (PID $($process.Id)): $resolvedListenAddress`:$Port -> $resolvedTargetAddress`:$Port"

param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectDir 'target\ops-pilot-1.0.0.jar'
$dataDir = Join-Path $projectDir 'data'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先在项目根目录运行 mvn -B -ntp verify' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "端口 $Port 已被占用，请先停止已有服务，或使用 -Port 指定其他端口。"
}
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$launchOptions = @{
    FilePath = (Get-Command java).Source
    ArgumentList = @('-jar', ('"' + $jarPath + '"'), "--server.port=$Port")
    WorkingDirectory = $projectDir
    WindowStyle = 'Hidden'
    PassThru = $true
    RedirectStandardOutput = (Join-Path $dataDir "server-$Port.log")
    RedirectStandardError = (Join-Path $dataDir "server-$Port.err.log")
}
$process = Start-Process @launchOptions
$process.Id | Set-Content -LiteralPath (Join-Path $dataDir "server-$Port.pid")
$ready = $false
for ($attempt = 0; $attempt -lt 50; $attempt++) {
    if ($process.HasExited) { throw "服务启动失败，请查看 data\server-$Port.log" }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 1
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 300
}
if (-not $ready) { throw "服务未在预期时间内就绪；进程 $($process.Id) 仍可通过 scripts\stop.ps1 停止。" }
Write-Output "OpsPilot: http://127.0.0.1:$Port"
Write-Output "PID: $($process.Id)"
Write-Output "Stop: .\scripts\stop.ps1 -Port $Port"


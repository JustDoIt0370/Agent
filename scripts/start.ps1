param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'load-env.ps1') -Path (Join-Path $projectDir '.env.local')
$jarPath = Join-Path $projectDir 'target\ops-pilot-1.0.0.jar'
$dataDir = Join-Path $projectDir 'data'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build first: mvn -B -ntp verify' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Port $Port is in use. Stop the service first or choose another -Port."
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
    if ($process.HasExited) { throw "Startup failed. Check data\server-$Port.log and data\server-$Port.err.log" }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 1
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 300
}
if (-not $ready) { throw "Startup timed out. Check the server logs; use scripts\stop.ps1 to stop PID $($process.Id)." }
Write-Output "OpsPilot: http://127.0.0.1:$Port"
Write-Output "PID: $($process.Id)"
Write-Output "Stop: .\scripts\stop.ps1 -Port $Port"


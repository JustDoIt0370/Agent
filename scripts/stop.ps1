param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectDir "data\server-$Port.pid"
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output '没有此项目的服务进程记录。'; exit 0 }
$serverProcessId = [int](Get-Content -LiteralPath $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $serverProcessId"
if ($null -eq $process) { Write-Output '服务已停止。'; exit 0 }
$jarPath = Join-Path $projectDir 'target\ops-pilot-1.0.0.jar'
if (-not $process.CommandLine.Contains($jarPath)) { throw '进程身份已变化，不会停止其他进程。' }
Stop-Process -Id $serverProcessId
Write-Output "OpsPilot 已停止（PID $serverProcessId）。"


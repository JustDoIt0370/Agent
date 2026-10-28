param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectDir "data\server-$Port.pid"
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output 'No OpsPilot PID file found.'; exit 0 }
$serverProcessId = [int](Get-Content -LiteralPath $pidFile)
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $serverProcessId"
if ($null -eq $process) { Write-Output 'OpsPilot is already stopped.'; exit 0 }
$jarPath = Join-Path $projectDir 'target\ops-pilot-1.0.0.jar'
if (-not $process.CommandLine -or -not $process.CommandLine.Contains($jarPath)) { throw 'Process identity does not match OpsPilot. Refusing to stop it.' }
Stop-Process -Id $serverProcessId
Wait-Process -Id $serverProcessId -Timeout 15 -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Output "OpsPilot stopped (PID $serverProcessId)."


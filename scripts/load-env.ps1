param([string]$Path = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Path)) { return }
$allowed = @('AGENT_API_KEY', 'AGENT_BASE_URL', 'AGENT_MODEL', 'AGENT_EXTRA_BODY_JSON')
$seen = @{}
$values = @{}
$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
    $lineNumber++
    $text = $line.Trim()
    if (-not $text -or $text.StartsWith('#')) { continue }
    if ($text -notmatch '^([A-Z_]+)\s*=(.*)$') { throw "本地配置第 $lineNumber 行格式错误，应为 NAME=value。" }
    $name = $Matches[1]
    $value = $Matches[2].Trim()
    if ($name -notin $allowed -or $seen.ContainsKey($name)) { throw "本地配置第 $lineNumber 行包含不支持或重复的变量名。" }
    $seen[$name] = $true
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ($value) { $values[$name] = $value }
}
# Parse the whole file first. Never evaluate configuration as PowerShell code or print its contents.
foreach ($name in $values.Keys) { [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process') }

$ErrorActionPreference = 'Stop'
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    if (@($bytes | Where-Object { $_ -gt 127 }).Count -gt 0) {
        throw "Scripts must be ASCII for Windows PowerShell 5.1: $($file.Name)"
    }
    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) { throw "Script parse failed: $($file.Name)" }
}
& (Join-Path $PSScriptRoot 'test-env.ps1')
Write-Output "Script compatibility checks passed (PowerShell $($PSVersionTable.PSVersion))."

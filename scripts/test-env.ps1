$ErrorActionPreference = 'Stop'
$temporaryFile = Join-Path ([IO.Path]::GetTempPath()) ('opspilot-env-' + [Guid]::NewGuid() + '.txt')
$original = [Environment]::GetEnvironmentVariable('AGENT_API_KEY', 'Process')
$originalJson = [Environment]::GetEnvironmentVariable('AGENT_EXTRA_BODY_JSON', 'Process')
try {
    @'
# fixture only
AGENT_API_KEY="test-only=a=b"
AGENT_EXTRA_BODY_JSON='{"enable_thinking":false}'
'@ | Set-Content -LiteralPath $temporaryFile -Encoding utf8
    & (Join-Path $PSScriptRoot 'load-env.ps1') -Path $temporaryFile
    if ($env:AGENT_API_KEY -ne 'test-only=a=b') { throw 'Quoted value parsing failed' }
    if ($env:AGENT_EXTRA_BODY_JSON -ne '{"enable_thinking":false}') { throw 'JSON value parsing failed' }
    'AGENT_API_KEY=' | Set-Content -LiteralPath $temporaryFile -Encoding utf8
    & (Join-Path $PSScriptRoot 'load-env.ps1') -Path $temporaryFile
    if ($env:AGENT_API_KEY -ne 'test-only=a=b') { throw 'Blank values must not clear existing environment variables' }
    'PATH=invalid' | Set-Content -LiteralPath $temporaryFile -Encoding utf8
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'load-env.ps1') -Path $temporaryFile } catch { $rejected = $true }
    if (-not $rejected) { throw 'Unknown variables must be rejected' }
    Write-Output 'Local configuration checks passed.'
} finally {
    [Environment]::SetEnvironmentVariable('AGENT_API_KEY', $original, 'Process')
    [Environment]::SetEnvironmentVariable('AGENT_EXTRA_BODY_JSON', $originalJson, 'Process')
    Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
}

param(
    [string]$BaseUrl = 'http://127.0.0.1:19091',
    [string]$Token = 'local-token-17',
    [switch]$Quick,
    [switch]$SkipLargePayload
)

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$source = Join-Path $PSScriptRoot 'RuntimeMvpLoad.java'
$toolchains = [xml](Get-Content -Raw -LiteralPath (Join-Path $repoRoot '.mvn\toolchains.xml'))
$jdk = $toolchains.toolchains.toolchain |
    Where-Object { $_.type -eq 'jdk' -and $_.provides.version -eq '17' } |
    Select-Object -First 1
$java = Join-Path ([string]$jdk.configuration.jdkHome) 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) {
    throw 'A valid JDK 17 toolchain is required.'
}

$arguments = @($source, '--base-url', $BaseUrl, '--token', $Token)
if ($Quick) { $arguments += '--quick' }
if ($SkipLargePayload) { $arguments += '--skip-large' }

Push-Location $repoRoot
try {
    & $java $arguments
    if ($LASTEXITCODE -ne 0) { throw 'Runtime MVP performance gate failed.' }
} finally {
    Pop-Location
}

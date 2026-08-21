param(
    [switch]$WithDocker
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$toolchainsFile = Join-Path $repoRoot '.mvn\toolchains.xml'

if (-not (Test-Path -LiteralPath $toolchainsFile)) {
    throw 'Missing .mvn/toolchains.xml; copy and edit .mvn/toolchains.xml.example first.'
}

[xml]$toolchains = Get-Content -Raw -LiteralPath $toolchainsFile
$jdk17Toolchain = $toolchains.toolchains.toolchain |
    Where-Object { $_.type -eq 'jdk' -and $_.provides.version -eq '17' } |
    Select-Object -First 1
$mavenJdk17Home = [string]$jdk17Toolchain.configuration.jdkHome
if (-not $mavenJdk17Home -or -not (Test-Path -LiteralPath (Join-Path $mavenJdk17Home 'bin\java.exe'))) {
    throw 'The JDK 17 entry in .mvn/toolchains.xml is missing or invalid.'
}

$previousJavaHome = $env:JAVA_HOME

Push-Location $repoRoot
try {
    $env:JAVA_HOME = $mavenJdk17Home
    & '.\mvnw.cmd' -t '.mvn\toolchains.xml' clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }

    Push-Location (Join-Path $repoRoot 'mock-platform-web')
    try {
        & 'npm.cmd' ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        & 'npm.cmd' run type-check
        if ($LASTEXITCODE -ne 0) { throw 'Web type-check failed.' }
        & 'npm.cmd' run lint
        if ($LASTEXITCODE -ne 0) { throw 'Web lint failed.' }
        & 'npm.cmd' run build
        if ($LASTEXITCODE -ne 0) { throw 'Web build failed.' }
    } finally {
        Pop-Location
    }

    if ($WithDocker) {
        & 'docker' compose --env-file '.env' -f 'deploy\docker-compose.yml' up -d
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }
        & 'docker' compose --env-file '.env' -f 'deploy\docker-compose.yml' ps
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose status check failed.' }
    }
} finally {
    if ($null -eq $previousJavaHome) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_HOME = $previousJavaHome
    }
    Pop-Location
}

#Requires -Version 7.0
[CmdletBinding()]
param([switch]$Restart)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
[xml]$toolchains = Get-Content '.mvn/toolchains.xml'
$jdk = ($toolchains.toolchains.toolchain | Where-Object { $_.provides.version -eq '17' }).configuration.jdkHome
$java = Join-Path $jdk 'bin/java.exe'
$settings = @{}
Get-Content '.env' | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        $settings[$matches[1]] = $matches[2].Trim('"').Trim("'")
    }
}
$dbPort = if ($settings['MYSQL_PORT']) { $settings['MYSQL_PORT'] } else { '3306' }
$dbName = if ($settings['MYSQL_DATABASE']) { $settings['MYSQL_DATABASE'] } else { 'mock_platform' }
$env:MOCK_MYSQL_URL = "jdbc:mysql://127.0.0.1:${dbPort}/${dbName}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
$env:MOCK_MYSQL_USERNAME = if ($settings['MYSQL_USER']) { $settings['MYSQL_USER'] } else { 'mock' }
$env:MOCK_MYSQL_PASSWORD = $settings['MYSQL_PASSWORD']
$env:MOCK_REDIS_HOST = '127.0.0.1'
$env:MOCK_REDIS_PORT = if ($settings['REDIS_PORT']) { $settings['REDIS_PORT'] } else { '6379' }
$env:MOCK_RUNTIME_NODE_ID = 'runtime-local-1'
$env:MOCK_RUNTIME_APPS = 'sample-jdk8,sample-jdk17'
$env:MOCK_ENVIRONMENT = 'TEST'

# Persistent local key material is ignored by Git and readable only by the current Windows user.
$keyDirectory = Join-Path $projectRoot '.codex-tmp/local-published'
New-Item -ItemType Directory -Path $keyDirectory -Force | Out-Null
$acl = [System.Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl',
    'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.SetAccessRule($rule)
[System.IO.FileSystemAclExtensions]::SetAccessControl([System.IO.DirectoryInfo]::new($keyDirectory), $acl)
$privateFile = Join-Path $keyDirectory 'snapshot-private.der'
$publicFile = Join-Path $keyDirectory 'snapshot-public.der'
if ((Test-Path $privateFile) -ne (Test-Path $publicFile)) { throw 'Incomplete local signing key pair; restore the missing key file.' }
if (!(Test-Path $privateFile)) {
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    try {
        [System.IO.File]::WriteAllBytes($privateFile, $rsa.ExportPkcs8PrivateKey())
        [System.IO.File]::WriteAllBytes($publicFile, $rsa.ExportSubjectPublicKeyInfo())
    } finally { $rsa.Dispose() }
}
$env:MOCK_SNAPSHOT_PRIVATE_KEY_FILE = $privateFile
$env:MOCK_SNAPSHOT_PUBLIC_KEY_FILE = $publicFile
$env:MOCK_SNAPSHOT_PUBLIC_KEY = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($publicFile))

$services = @(
    @{ Name='control'; Port=19090; Module='mock-platform-control'; Jar='mock-platform-control-0.1.0-SNAPSHOT.jar'; Health='/api/platform/health' },
    @{ Name='runtime'; Port=19091; Module='mock-platform-runtime'; Jar='mock-platform-runtime-0.1.0-SNAPSHOT-exec.jar'; Health='/actuator/health' }
)
foreach ($service in $services) {
    $service.JarPath = Join-Path $projectRoot ($service.Module + '/target/' + $service.Jar)
    if (!(Test-Path -LiteralPath $service.JarPath)) { throw "Build $($service.Module) before starting." }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $service.Port -ErrorAction SilentlyContinue)
    foreach ($ownerId in ($listeners.OwningProcess | Select-Object -Unique)) {
        $ownerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId"
        if (!$Restart -or !$ownerProcess.CommandLine.Contains($service.JarPath)) {
            throw "Port $($service.Port) is occupied; -Restart only stops this workspace's matching service."
        }
        Stop-Process -Id $ownerId
        Wait-Process -Id $ownerId -Timeout 15 -ErrorAction SilentlyContinue
    }
}
foreach ($service in $services) {
    $target = Join-Path $projectRoot ($service.Module + '/target')
    $process = Start-Process -FilePath $java -ArgumentList @(
        '-Djdk.net.unixdomain.tmpdir=Z:\codex-selector-fallback', '-jar', ('"' + $service.JarPath + '"'),
        '--spring.profiles.active=local,local-published'
    ) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
      -RedirectStandardOutput (Join-Path $target 'local-service.out.log') `
      -RedirectStandardError (Join-Path $target 'local-service.err.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if ($process.HasExited) { throw "$($service.Name) exited; inspect $target/local-service.out.log" }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$($service.Port)$($service.Health)" -TimeoutSec 2
            if ($health.status -eq 'UP' -or $health.success -eq $true) { $ready = $true; break }
        } catch { Start-Sleep -Seconds 1 }
    }
    if (!$ready) { throw "$($service.Name) did not become healthy; inspect its local-service log." }
    [pscustomobject]@{ Service=$service.Name; PID=$process.Id; Port=$service.Port; Mode='local-published' }
}

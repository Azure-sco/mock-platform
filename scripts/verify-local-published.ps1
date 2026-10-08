#Requires -Version 7.0
# Creates demo catalog entries through the same APIs as Web. Never writes business tables directly.
[CmdletBinding()]
param([string]$ControlUrl='http://127.0.0.1:19090')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runId=Get-Date -Format 'yyyyMMddHHmmss'
$fixture=Get-Content (Join-Path $projectRoot 'mock-platform-runtime/src/main/resources/runtime-fixture.json') -Raw | ConvertFrom-Json
function Invoke-Admin([string]$Method, [string]$Path, $Body=$null, [string]$Operator='local-admin') {
    $headers=@{
        'X-Operator-Id'=$Operator
        'X-Operator-Roles'=$(if($Operator -eq 'local-admin'){'MOCK_ADMIN,MOCK_VIEWER'}else{'MOCK_APPROVER,MOCK_VIEWER'})
        'X-Request-Id'=[guid]::NewGuid().ToString()
    }
    $options=@{Method=$Method;Uri="$ControlUrl/api/admin/v1$Path";Headers=$headers;TimeoutSec=15}
    if($null -ne $Body){$options.ContentType='application/json';$options.Body=$Body|ConvertTo-Json -Depth 30 -Compress}
    $result=Invoke-RestMethod @options
    if(!$result.success){throw "Admin request failed: $Path $($result.code)"}
    return $result.data
}
function New-ApprovedVersion($Entry, [string]$Status) {
    $body=@{code=$Entry.Code;message='success';source='PUBLISHED_LOCAL';data=@{status=$Status;flowId='FIXED-EQB-001';flowNo='FIXED-OA-001';settleId=42;oaNumber='OA-001';files=@()}}
    $version=Invoke-Admin POST "/scenarios/$($Entry.ScenarioId)/versions" @{
        contractVersionId=$Entry.ContractId;priority=100
        scope=@{environments=@('TEST');apps=@('sample-jdk8','sample-jdk17');tenants=@();testAccounts=@()}
        matchRules=@($Entry.MatchRules)
        response=@{httpStatus=200;headers=@{'Content-Type'='application/json';'X-Demo-Run'=$runId};bodyTemplate=($body|ConvertTo-Json -Depth 10 -Compress)}
        callbacks=@()
    }
    $null=Invoke-Admin POST "/scenario-versions/$($version.id)/validate"
    $approval=Invoke-Admin POST "/scenario-versions/$($version.id)/submit-approval"
    $denied=$false
    try {$null=Invoke-Admin POST "/approvals/$($approval.id)/approve" @{comment='self approval must fail'}}
    catch {if($_.Exception.Response.StatusCode.value__ -eq 403){$denied=$true}else{throw}}
    if(!$denied){throw 'Self approval unexpectedly succeeded'}
    $null=Invoke-Admin POST "/approvals/$($approval.id)/approve" @{comment='Local publication verification'} 'local-reviewer'
    return [long]$version.id
}
function Wait-Activation($Activation) {
    for($attempt=0;$attempt -lt 60;$attempt++) {
        $current=Invoke-Admin GET "/release-activations/$($Activation.activation.id)"
        if($current.activation.status -eq 'APPLIED') {
            if(@($current.targets).Count -ne 1 -or $current.targets[0].status -ne 'READY') {throw 'Runtime target did not confirm READY'}
            return $current
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Release did not converge: $($Activation.activation.id)"
}
function Publish-Selection([string]$App, [long[]]$Versions, [string]$Suffix) {
    $release=Invoke-Admin POST '/releases' @{releaseCode="local-$runId-$Suffix";environment='TEST';appCode=$App;scenarioVersionIds=$Versions;releaseNote='Local fixed response verification'}
    $active=Invoke-Admin GET "/active-releases?environment=TEST&app=$App"
    $expected=if($null -eq $active){0}else{$active.activationVersion}
    $activation=Invoke-Admin POST "/releases/$($release.release.id)/publish" @{expectedActivationVersion=$expected}
    $null=Wait-Activation $activation
    return $release.release.id
}
function Assert-Sample([string]$Url, [string]$Status) {
    $result=Invoke-RestMethod -Method Post $Url -TimeoutSec 10
    if($result.source -ne 'PUBLISHED_LOCAL' -or $result.data.status -ne $Status) {throw "Unexpected published response at $Url"}
}
$runtimePidBefore=@(Get-NetTCPConnection -State Listen -LocalPort 19091).OwningProcess | Select-Object -Unique
$entries=@()
foreach($contract in $fixture.contracts) {
    $provider=@(Invoke-Admin GET '/providers') | Where-Object providerCode -eq $contract.provider | Select-Object -First 1
    if(!$provider) {$provider=Invoke-Admin POST '/providers' @{providerCode=$contract.provider;providerName=$contract.provider;owner='local-demo';status='ENABLED'}}
    $api=@(Invoke-Admin GET "/providers/$($provider.id)/apis") | Where-Object apiCode -eq $contract.api | Select-Object -First 1
    if(!$api) {$api=Invoke-Admin POST '/apis' @{providerId=$provider.id;apiCode=$contract.api;apiName=$contract.api;httpMethod=$contract.method;path=$contract.path;contentType=$(if($contract.contentTypes.Count){$contract.contentTypes[0]}else{'application/json'});owner='local-demo';status='ENABLED'}}
    if($api.path -ne $contract.path -or $api.httpMethod -ne $contract.method){throw "Existing API differs from demo: $($contract.api)"}
    $published=@(Invoke-Admin GET "/apis/$($api.id)/contracts") | Where-Object status -eq 'PUBLISHED' | Sort-Object versionNo -Descending | Select-Object -First 1
    if(!$published) {
        $requestSchema=if($contract.requestSchema){$contract.requestSchema}else{@{}}
        $published=Invoke-Admin POST "/apis/$($api.id)/contracts" @{requestSchema=$requestSchema;responseSchema=$contract.responseSchema;sourceType='MANUAL'}
        $null=Invoke-Admin POST "/contracts/$($published.id)/validate"
        $published=Invoke-Admin POST "/contracts/$($published.id)/publish"
    }
    $code='local-'+$contract.api.ToLowerInvariant()
    $scenario=@(Invoke-Admin GET '/scenarios') | Where-Object scenarioCode -eq $code | Select-Object -First 1
    if(!$scenario){$scenario=Invoke-Admin POST '/scenarios' @{scenarioCode=$code;scenarioName="固定响应 $($contract.api)";providerId=$provider.id;apiId=$api.id}}
    if($scenario.apiId -ne $api.id){throw 'Existing scenario belongs to a different API'}
    $source=$fixture.scenarios | Where-Object {$_.api -eq $contract.api -and $_.priority -eq 100} | Select-Object -First 1
    $entry=@{Api=$contract.api;ScenarioId=$scenario.id;ContractId=$published.id;MatchRules=@($source.matchRules);Code=$(if($contract.provider -eq 'OA'){'200'}else{'0'})}
    $entry.Version=New-ApprovedVersion $entry 'FIXED_V1'
    $entries+=,$entry
}
$versions=@($entries | ForEach-Object {$_.Version})
$oaRelease=Publish-Selection 'sample-jdk8' $versions 'oa-v1'
$cpsRelease=Publish-Selection 'sample-jdk17' $versions 'cps-v1'
Assert-Sample 'http://127.0.0.1:19093/demo/oa/reviews?businessNo=LOCAL-PUBLISHED' 'FIXED_V1'
Assert-Sample 'http://127.0.0.1:19094/demo/cps/signatures?settleId=42' 'FIXED_V1'
$cps=$entries | Where-Object Api -eq 'CPS_SIGN_CREATE_START'
$cps.Version=New-ApprovedVersion $cps 'FIXED_V2'
$updatedRelease=Publish-Selection 'sample-jdk17' @($entries | ForEach-Object {$_.Version}) 'cps-v2'
Assert-Sample 'http://127.0.0.1:19094/demo/cps/signatures?settleId=42' 'FIXED_V2'
$active=Invoke-Admin GET '/active-releases?environment=TEST&app=sample-jdk17'
$rollback=Invoke-Admin POST "/releases/$cpsRelease/rollback" @{expectedActivationVersion=$active.activationVersion}
$null=Wait-Activation $rollback
Assert-Sample 'http://127.0.0.1:19094/demo/cps/signatures?settleId=42' 'FIXED_V1'
# Restore the latest response as the final user-visible state.
$active=Invoke-Admin GET '/active-releases?environment=TEST&app=sample-jdk17'
$restore=Invoke-Admin POST "/releases/$updatedRelease/rollback" @{expectedActivationVersion=$active.activationVersion}
$null=Wait-Activation $restore
Assert-Sample 'http://127.0.0.1:19094/demo/cps/signatures?settleId=42' 'FIXED_V2'
$runtimePidAfter=@(Get-NetTCPConnection -State Listen -LocalPort 19091).OwningProcess | Select-Object -Unique
if($runtimePidBefore -ne $runtimePidAfter){throw 'Runtime restarted during publication verification'}
[pscustomobject]@{Result='PASS';RuntimePID=$runtimePidAfter;ProviderCount=2;ApiCount=4;OaRelease=$oaRelease;CpsRelease=$updatedRelease;Checks='self-approval denied; signed publish; READY ACK; V1 -> V2 -> rollback V1 -> V2; same Runtime PID'}

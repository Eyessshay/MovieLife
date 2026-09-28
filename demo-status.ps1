[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools\demo-common.ps1')

$mysqlState = 'UNAVAILABLE'
$redisState = 'UNAVAILABLE'
$dockerAvailable = $false
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($null -ne $dockerCommand) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker info *> $null
        $dockerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
} else {
    $dockerExitCode = 1
}
if ($dockerExitCode -eq 0) {
    $dockerAvailable = $true
    $mysqlState = (Get-MovieLifeContainerHealth -ContainerName 'movielife-mysql').ToUpperInvariant()
    $redisState = (Get-MovieLifeContainerHealth -ContainerName 'movielife-redis').ToUpperInvariant()
}

$applicationState = 'STOPPED'
$processDetail = 'no recorded process'
try {
    $recordedProcess = Get-MovieLifeDemoProcess
    if ($null -ne $recordedProcess) {
        $health = Get-MovieLifeDemoHealth
        $applicationState = if ($health.Healthy) { 'UP' } else { $health.Status }
        $processDetail = "recorded PID $($recordedProcess.ProcessId)"
    }
} catch {
    $applicationState = 'PID_CONFLICT'
    $processDetail = $_.Exception.Message
}

$portOpen = Test-MovieLifePortOpen -Port $DemoPort
$portState = if ($portOpen) { 'LISTENING' } else { 'FREE' }

@(
    [pscustomobject]@{ Component = 'Docker'; State = $(if ($dockerAvailable) { 'AVAILABLE' } else { 'UNAVAILABLE' }); Detail = 'Docker Engine' },
    [pscustomobject]@{ Component = 'MySQL'; State = $mysqlState; Detail = 'movielife-mysql / 127.0.0.1:13306' },
    [pscustomobject]@{ Component = 'Redis'; State = $redisState; Detail = 'movielife-redis / 127.0.0.1:16379' },
    [pscustomobject]@{ Component = 'Application'; State = $applicationState; Detail = $processDetail },
    [pscustomobject]@{ Component = 'HTTP port'; State = $portState; Detail = "127.0.0.1:$DemoPort" }
) | Format-Table -AutoSize

if (-not $dockerAvailable -or $mysqlState -ne 'HEALTHY' -or $redisState -ne 'HEALTHY' -or
    $applicationState -ne 'UP' -or -not $portOpen) {
    exit 1
}

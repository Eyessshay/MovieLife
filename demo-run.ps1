[CmdletBinding()]
param([switch]$Reset)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools\demo-common.ps1')

try {
    Assert-MovieLifeDemoDocker

    if ($Reset) {
        $runningProcess = Get-MovieLifeDemoProcess
        if ($null -ne $runningProcess) {
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
                (Join-Path $PSScriptRoot 'demo-down.ps1')
            if ($LASTEXITCODE -ne 0) {
                throw 'MovieLife demo shutdown failed before reset.'
            }
        }
        Invoke-MovieLifeInfrastructure -Action 'start'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
            (Join-Path $PSScriptRoot 'demo-data-reset.ps1') -Force
        if ($LASTEXITCODE -ne 0) {
            throw 'MovieLife deterministic demo data reset failed.'
        }
    }

    $processRecord = Get-MovieLifeDemoProcess
    $health = Get-MovieLifeDemoHealth
    if ($null -eq $processRecord -or -not $health.Healthy) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
            (Join-Path $PSScriptRoot 'demo-up.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw 'MovieLife demo startup failed.'
        }
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot 'demo-data-check.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'Demo data is not at the deterministic baseline. Re-run with: .\demo-run.ps1 -Reset'
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot 'tools\demo-business.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'MovieLife automated business demo failed.'
    }
} catch {
    Write-Error "demo-run failed: $($_.Exception.Message)"
    exit 1
}

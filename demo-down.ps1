[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools\demo-common.ps1')

try {
    $recordedProcess = Get-MovieLifeDemoProcess
    if ($null -ne $recordedProcess) {
        $recordedProcessId = [int]$recordedProcess.ProcessId
        Stop-MovieLifeDemoProcess -ProcessRecord $recordedProcess
        Remove-Item -LiteralPath $DemoPidFile -Force -ErrorAction SilentlyContinue
        Write-Host "MovieLife demo application stopped (PID $recordedProcessId)."
    } else {
        Write-Host 'No recorded MovieLife demo application process is running.'
    }

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ((Test-MovieLifePortOpen -Port $DemoPort) -and $stopwatch.Elapsed.TotalSeconds -lt 10) {
        Start-Sleep -Milliseconds 200
    }
    if (Test-MovieLifePortOpen -Port $DemoPort) {
        throw "Port $DemoPort remains in use by an unrecorded process; it was not changed."
    }

    if (Test-Path -LiteralPath $DemoPasswordFile) {
        Invoke-MovieLifeInfrastructure -Action 'stop'
    } else {
        Write-Host 'MovieLife infrastructure was not initialized; no containers were changed.'
    }
    Write-Host 'MovieLife demo is down. Logs and infrastructure data volumes are retained.'
} catch {
    Write-Error "demo-down failed: $($_.Exception.Message)"
    exit 1
}

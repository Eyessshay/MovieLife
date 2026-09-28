Set-StrictMode -Version Latest

$DemoRepoRoot = Split-Path -Parent $PSScriptRoot
$DemoServerDirectory = Join-Path $DemoRepoRoot 'movielife-server'
$DemoJarPath = Join-Path $DemoServerDirectory 'target\movielife-server-0.0.1-SNAPSHOT.jar'
$DemoInfrastructureScript = Join-Path $PSScriptRoot 'infrastructure.ps1'
$DemoRuntimeDirectory = Join-Path $DemoRepoRoot '.movielife-runtime'
$DemoPasswordFile = Join-Path $DemoRuntimeDirectory 'mysql-app-password.txt'
$DemoPidFile = Join-Path $DemoRuntimeDirectory 'demo-app.pid'
$DemoLogDirectory = Join-Path $DemoRepoRoot 'logs\demo'
$DemoApplicationLog = Join-Path $DemoLogDirectory 'application.log'
$DemoStandardOutputLog = Join-Path $DemoLogDirectory 'stdout.log'
$DemoStandardErrorLog = Join-Path $DemoLogDirectory 'stderr.log'
$DemoPort = 18081
$DemoHealthUrl = "http://127.0.0.1:$DemoPort/actuator/health"

function Assert-MovieLifeDemoJava {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw 'Java was not found on PATH. Install JDK 8 and retry.'
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $versionOutput = (& java.exe -version 2>&1 | Out-String)
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($javaExitCode -ne 0 -or $versionOutput -notmatch 'version\s+"1\.8\.') {
        throw 'MovieLife demo requires JDK 8. Ensure java.exe on PATH reports version 1.8.x.'
    }
}

function Assert-MovieLifeDemoDocker {
    if ($null -eq (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
        throw 'Docker CLI was not found on PATH. Install Docker Desktop and retry.'
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker info *> $null
        $dockerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($dockerExitCode -ne 0) {
        throw 'Docker Engine is unavailable. Start Docker Desktop and retry.'
    }
}

function Invoke-MovieLifeInfrastructure {
    param([Parameter(Mandatory = $true)][string]$Action)

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $DemoInfrastructureScript $Action
    if ($LASTEXITCODE -ne 0) {
        throw "MovieLife infrastructure action '$Action' failed."
    }
}

function Test-MovieLifePortOpen {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMilliseconds = 500
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $asyncResult = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $asyncResult.AsyncWaitHandle.WaitOne($TimeoutMilliseconds)) {
            return $false
        }
        $client.EndConnect($asyncResult)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Test-MovieLifeDemoProcessIdentity {
    param([Parameter(Mandatory = $true)]$ProcessRecord)

    $commandLine = [string]$ProcessRecord.CommandLine
    return $commandLine.IndexOf($DemoJarPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $commandLine.IndexOf('--spring.profiles.active=demo', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Get-MovieLifeDemoProcess {
    if (-not (Test-Path -LiteralPath $DemoPidFile)) {
        return $null
    }

    $pidText = [System.IO.File]::ReadAllText($DemoPidFile).Trim()
    $recordedProcessId = 0
    if (-not [int]::TryParse($pidText, [ref]$recordedProcessId) -or $recordedProcessId -le 0) {
        throw "MovieLife demo PID file is invalid: $DemoPidFile"
    }

    $processRecord = Get-CimInstance Win32_Process -Filter "ProcessId = $recordedProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $processRecord) {
        Remove-Item -LiteralPath $DemoPidFile -Force
        return $null
    }
    if (-not (Test-MovieLifeDemoProcessIdentity -ProcessRecord $processRecord)) {
        throw "PID $recordedProcessId no longer belongs to the recorded MovieLife demo process; it will not be managed."
    }
    return $processRecord
}

function Get-MovieLifeContainerHealth {
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $containerMatch = & docker ps --all --filter "name=^/$ContainerName$" --format '{{.Names}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or $containerMatch -notcontains $ContainerName) {
        return 'missing'
    }
    $state = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0) {
        return 'missing'
    }
    return ($state | Select-Object -First 1).Trim()
}

function Get-MovieLifeDemoHealth {
    try {
        $response = Invoke-RestMethod -Method Get -Uri $DemoHealthUrl -TimeoutSec 3
        $componentNames = @($response.components.PSObject.Properties.Name | Sort-Object)
        $expectedNames = @('application', 'mysql', 'redis')
        $componentsMatch = $componentNames.Count -eq $expectedNames.Count -and
            -not (Compare-Object $expectedNames $componentNames -SyncWindow 0)
        $componentsUp = $componentsMatch -and
            $response.components.application.status -eq 'UP' -and
            $response.components.mysql.status -eq 'UP' -and
            $response.components.redis.status -eq 'UP'
        return [pscustomobject]@{
            Healthy = ($response.status -eq 'UP' -and $componentsUp)
            Status = [string]$response.status
        }
    } catch {
        return [pscustomobject]@{
            Healthy = $false
            Status = 'UNAVAILABLE'
        }
    }
}

function Wait-MovieLifeDemoHealth {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [int]$TimeoutSeconds = 120
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "MovieLife demo process exited before becoming healthy. Check $DemoStandardErrorLog and $DemoApplicationLog."
        }
        $health = Get-MovieLifeDemoHealth
        if ($health.Healthy) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "MovieLife demo did not become healthy within $TimeoutSeconds seconds. Check $DemoStandardErrorLog and $DemoApplicationLog."
}

function Stop-MovieLifeDemoProcess {
    param([Parameter(Mandatory = $true)]$ProcessRecord)

    $recordedProcessId = [int]$ProcessRecord.ProcessId
    Stop-Process -Id $recordedProcessId -ErrorAction Stop

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt 20) {
        if ($null -eq (Get-Process -Id $recordedProcessId -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 200
    }

    Stop-Process -Id $recordedProcessId -Force -ErrorAction Stop
    $stopwatch.Restart()
    while ($stopwatch.Elapsed.TotalSeconds -lt 10) {
        if ($null -eq (Get-Process -Id $recordedProcessId -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw "Recorded MovieLife demo process $recordedProcessId could not be stopped."
}

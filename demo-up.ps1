[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools\demo-common.ps1')

try {
    Assert-MovieLifeDemoJava
    Assert-MovieLifeDemoDocker

    $existingProcess = Get-MovieLifeDemoProcess
    if ($null -ne $existingProcess) {
        $existingHealth = Get-MovieLifeDemoHealth
        if ($existingHealth.Healthy) {
            Write-Host "MovieLife demo is already running and healthy (PID $($existingProcess.ProcessId), port $DemoPort)."
            exit 0
        }
        throw "Recorded MovieLife demo process $($existingProcess.ProcessId) is running but not healthy. Run .\demo-down.ps1 before retrying."
    }

    if (Test-MovieLifePortOpen -Port $DemoPort) {
        throw "Port $DemoPort is already in use by a process not recorded by MovieLife; no process was changed."
    }

    Invoke-MovieLifeInfrastructure -Action 'start'
    if (-not (Test-Path -LiteralPath $DemoPasswordFile)) {
        throw "MovieLife MySQL password file is missing: $DemoPasswordFile"
    }

    $mavenWrapper = Join-Path $DemoServerDirectory 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "Maven Wrapper is missing: $mavenWrapper"
    }

    Push-Location $DemoServerDirectory
    try {
        & $mavenWrapper clean package '-DskipTests'
        if ($LASTEXITCODE -ne 0) {
            throw "MovieLife application build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $DemoJarPath)) {
        throw "MovieLife application artifact was not created: $DemoJarPath"
    }

    New-Item -ItemType Directory -Path $DemoRuntimeDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $DemoLogDirectory -Force | Out-Null

    $environmentNames = @(
        'MYSQL_URL', 'MYSQL_USERNAME', 'MYSQL_PASSWORD',
        'REDIS_HOST', 'REDIS_PORT', 'REDIS_DATABASE', 'SPRING_REDIS_PASSWORD',
        'SERVER_PORT', 'AUTH_LOG_CODE_ENABLED', 'STARTUP_CHECK_ENABLED',
        'AI_REVIEW_SUMMARY_ENABLED', 'MOVIELIFE_DEMO_LOG_FILE'
    )
    $previousEnvironment = @{}
    foreach ($name in $environmentNames) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }

    try {
        $env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:13306/movielife?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
        $env:MYSQL_USERNAME = 'movielife'
        $env:MYSQL_PASSWORD = [System.IO.File]::ReadAllText($DemoPasswordFile).Trim()
        $env:REDIS_HOST = '127.0.0.1'
        $env:REDIS_PORT = '16379'
        $env:REDIS_DATABASE = '0'
        Remove-Item Env:SPRING_REDIS_PASSWORD -ErrorAction SilentlyContinue
        $env:SERVER_PORT = [string]$DemoPort
        $env:AUTH_LOG_CODE_ENABLED = 'false'
        $env:STARTUP_CHECK_ENABLED = 'true'
        $env:AI_REVIEW_SUMMARY_ENABLED = 'false'
        $env:MOVIELIFE_DEMO_LOG_FILE = $DemoApplicationLog

        $process = Start-Process -FilePath 'java.exe' `
            -ArgumentList @('-jar', "`"$DemoJarPath`"", '--spring.profiles.active=demo') `
            -WorkingDirectory $DemoServerDirectory `
            -RedirectStandardOutput $DemoStandardOutputLog `
            -RedirectStandardError $DemoStandardErrorLog `
            -WindowStyle Hidden `
            -PassThru
    } finally {
        foreach ($name in $environmentNames) {
            $previousValue = $previousEnvironment[$name]
            if ($null -eq $previousValue) {
                [Environment]::SetEnvironmentVariable($name, $null, 'Process')
            } else {
                [Environment]::SetEnvironmentVariable($name, [string]$previousValue, 'Process')
            }
        }
    }

    [System.IO.File]::WriteAllText($DemoPidFile, [string]$process.Id, (New-Object System.Text.UTF8Encoding($false)))
    try {
        Wait-MovieLifeDemoHealth -ProcessId $process.Id
    } catch {
        $recordedProcess = Get-MovieLifeDemoProcess
        if ($null -ne $recordedProcess) {
            Stop-MovieLifeDemoProcess -ProcessRecord $recordedProcess
        }
        Remove-Item -LiteralPath $DemoPidFile -Force -ErrorAction SilentlyContinue
        throw
    }

    Write-Host "MovieLife demo is ready: http://127.0.0.1:$DemoPort (PID $($process.Id))."
    Write-Host "Health: $DemoHealthUrl"
    Write-Host "Logs: $DemoLogDirectory"
} catch {
    Write-Error "demo-up failed: $($_.Exception.Message)"
    exit 1
}

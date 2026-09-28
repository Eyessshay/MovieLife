[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'status', 'verify', 'stop', 'reset')]
    [string]$Action = 'status',

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'infrastructure\compose.yml'
$runtimeDirectory = Join-Path $repoRoot '.movielife-runtime'
$appPasswordFile = Join-Path $runtimeDirectory 'mysql-app-password.txt'
$rootPasswordFile = Join-Path $runtimeDirectory 'mysql-root-password.txt'
$projectName = 'movielife'
$managedContainers = @('movielife-mysql', 'movielife-redis')

function Invoke-DockerCompose {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & docker compose --project-name $projectName --file $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed with exit code $LASTEXITCODE."
    }
}

function Assert-DockerAvailable {
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose is unavailable. Install or start Docker Desktop, then retry.'
    }

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Engine is unavailable. Start Docker Desktop, then retry.'
    }
}

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    }
    finally {
        $generator.Dispose()
    }
}

function Initialize-Secrets {
    if (-not (Test-Path -LiteralPath $runtimeDirectory)) {
        New-Item -ItemType Directory -Path $runtimeDirectory | Out-Null
    }

    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    if (-not (Test-Path -LiteralPath $appPasswordFile)) {
        [System.IO.File]::WriteAllText($appPasswordFile, (New-RandomSecret), $utf8WithoutBom)
    }
    if (-not (Test-Path -LiteralPath $rootPasswordFile)) {
        [System.IO.File]::WriteAllText($rootPasswordFile, (New-RandomSecret), $utf8WithoutBom)
    }
}

function Assert-SecretsInitialized {
    if (-not (Test-Path -LiteralPath $appPasswordFile) -or
        -not (Test-Path -LiteralPath $rootPasswordFile)) {
        throw 'MovieLife infrastructure is not initialized. Run .\tools\infrastructure.ps1 start first.'
    }
}

function Get-ContainerHealth {
    param([Parameter(Mandatory = $true)][string]$ContainerName)

    $health = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null
    if ($LASTEXITCODE -ne 0) {
        return 'missing'
    }
    return ($health | Select-Object -First 1).Trim()
}

function Test-Infrastructure {
    Assert-SecretsInitialized

    foreach ($container in $managedContainers) {
        $health = Get-ContainerHealth -ContainerName $container
        if ($health -ne 'healthy') {
            throw "$container is not healthy (state: $health)."
        }
    }

    $applicationPassword = [System.IO.File]::ReadAllText($appPasswordFile).Trim()
    $query = @'
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'movielife') AS table_count,
  (SELECT COUNT(DISTINCT CONCAT(table_name, ':', index_name))
     FROM information_schema.statistics
    WHERE table_schema = 'movielife'
      AND index_name IN ('uk_showtime_venue_create_request', 'uk_order_lock_token', 'uk_ticket_code')) AS key_index_count,
  (SELECT COUNT(*) FROM tb_user WHERE id IN (1, 2, 3)) AS user_seed_count,
  (SELECT COUNT(*) FROM tb_venue WHERE id IN (1, 2)) AS venue_seed_count,
  (SELECT COUNT(*) FROM tb_movie WHERE id = 1) AS movie_seed_count,
  (SELECT COUNT(*) FROM tb_activity WHERE id = 1) AS activity_seed_count,
  (SELECT COUNT(*) FROM tb_showtime WHERE id IN (1, 2)) AS showtime_seed_count,
  (SELECT COUNT(*) FROM tb_seat WHERE hall_id IN (1, 3)) AS seat_seed_count,
  (SELECT COUNT(*) FROM tb_showtime_seat WHERE showtime_id IN (1, 2)) AS showtime_seat_seed_count;
'@
    $mysqlResult = & docker exec --env "MYSQL_PWD=$applicationPassword" movielife-mysql `
        mysql --user=movielife --host=127.0.0.1 --database=movielife `
        --batch --skip-column-names --execute=$query
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL verification query failed.'
    }

    $values = (($mysqlResult | Select-Object -Last 1).Trim() -split '\s+')
    $expected = @('20', '3', '3', '2', '1', '1', '2', '12', '12')
    if ($values.Count -ne $expected.Count -or (Compare-Object $expected $values -SyncWindow 0)) {
        throw "MySQL schema or seed verification failed. Expected $($expected -join ','); received $($values -join ',')."
    }

    $redisResult = & docker exec movielife-redis redis-cli --raw PING
    if ($LASTEXITCODE -ne 0 -or (($redisResult | Select-Object -Last 1).Trim()) -ne 'PONG') {
        throw 'Redis PING verification failed.'
    }

    Write-Host 'Verification passed: MySQL schema, key indexes, seed data, and Redis PING are correct.'
}

Assert-DockerAvailable

switch ($Action) {
    'start' {
        Initialize-Secrets
        Invoke-DockerCompose -Arguments @('up', '--detach', '--wait', '--wait-timeout', '180')
        Test-Infrastructure
        Write-Host 'MovieLife infrastructure is ready on MySQL 127.0.0.1:13306 and Redis 127.0.0.1:16379.'
    }
    'status' {
        Assert-SecretsInitialized
        Invoke-DockerCompose -Arguments @('ps')
        foreach ($container in $managedContainers) {
            Write-Host "$container health: $(Get-ContainerHealth -ContainerName $container)"
        }
    }
    'verify' {
        Test-Infrastructure
    }
    'stop' {
        Assert-SecretsInitialized
        Invoke-DockerCompose -Arguments @('stop')
        Write-Host 'MovieLife containers are stopped; containers, network, secrets, and data volumes are retained.'
    }
    'reset' {
        Assert-SecretsInitialized
        if (-not $Force) {
            throw 'Reset deletes only MovieLife Compose containers and data volumes. Re-run with: .\tools\infrastructure.ps1 reset -Force'
        }
        Invoke-DockerCompose -Arguments @('down', '--volumes', '--remove-orphans')
        Write-Host 'MovieLife Compose containers, network, and data volumes were removed. Generated local secrets were retained.'
    }
}

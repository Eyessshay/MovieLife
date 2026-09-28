[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('install', 'check', 'reset')]
    [string]$Action = 'check',

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')

$DemoIdMinimum = 1604000000L
$DemoIdMaximum = 1604999999L
$DemoPhones = @('13916040001', '13916040002', '13916040003', '13916040004')
$DemoVenueIds = @('1604000101', '1604000102')
$DemoMovieId = '1604000601'
$DemoReviewIds = @('1604000701', '1604000702', '1604000703')
$DemoSqlDirectory = Join-Path $DemoRepoRoot 'demo\sql'

function Assert-DemoDataInfrastructure {
    Assert-MovieLifeDemoDocker
    foreach ($containerName in @('movielife-mysql', 'movielife-redis')) {
        $health = Get-MovieLifeContainerHealth -ContainerName $containerName
        if ($health -ne 'healthy') {
            throw "$containerName is not healthy (state: $health). Run .\tools\infrastructure.ps1 start first."
        }
    }
    if (-not (Test-Path -LiteralPath $DemoPasswordFile)) {
        throw "MovieLife MySQL password file is missing: $DemoPasswordFile"
    }
}

function Assert-DemoApplicationStopped {
    $processRecord = Get-MovieLifeDemoProcess
    if ($null -ne $processRecord) {
        throw "MovieLife demo application is running (PID $($processRecord.ProcessId)). Run .\demo-down.ps1 before changing demo data."
    }
}

function Invoke-DemoMySqlQuery {
    param([Parameter(Mandatory = $true)][string]$Query)

    $applicationPassword = [System.IO.File]::ReadAllText($DemoPasswordFile).Trim()
    $result = & docker exec --env "MYSQL_PWD=$applicationPassword" movielife-mysql `
        mysql --user=movielife --host=127.0.0.1 --database=movielife `
        --default-character-set=utf8mb4 --batch --skip-column-names --execute=$Query
    if ($LASTEXITCODE -ne 0) {
        throw 'MovieLife demo data MySQL query failed.'
    }
    return @($result)
}

function Invoke-DemoSqlFile {
    param([Parameter(Mandatory = $true)][string]$Name)

    $sourcePath = Join-Path $DemoSqlDirectory "$Name.sql"
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        throw "MovieLife demo SQL file is missing: $sourcePath"
    }
    $containerPath = "/tmp/movielife-demo-$Name.sql"
    & docker cp $sourcePath "movielife-mysql:$containerPath" *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stage MovieLife demo SQL file '$Name'."
    }

    try {
        $applicationPassword = [System.IO.File]::ReadAllText($DemoPasswordFile).Trim()
        $result = & docker exec --env "MYSQL_PWD=$applicationPassword" movielife-mysql `
            mysql --user=movielife --host=127.0.0.1 --database=movielife `
            --default-character-set=utf8mb4 --batch --skip-column-names `
            --execute="source $containerPath"
        if ($LASTEXITCODE -ne 0) {
            throw "MovieLife demo SQL action '$Name' failed."
        }
        return @($result)
    } finally {
        & docker exec movielife-mysql rm -f $containerPath *> $null
    }
}

function Get-DemoShowtimeIds {
    $rows = Invoke-DemoMySqlQuery -Query (
        "SELECT id FROM tb_showtime WHERE venue_id BETWEEN $DemoIdMinimum AND $DemoIdMaximum ORDER BY id")
    return @($rows | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^\d+$' })
}

function Get-RedisKeys {
    param([Parameter(Mandatory = $true)][string]$Pattern)

    $result = & docker exec movielife-redis redis-cli --raw --scan --pattern $Pattern
    if ($LASTEXITCODE -ne 0) {
        throw 'MovieLife demo Redis scan failed.'
    }
    return @($result | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
}

function Get-RedisValue {
    param([Parameter(Mandatory = $true)][string]$Key)

    $result = & docker exec movielife-redis redis-cli --raw GET $Key
    if ($LASTEXITCODE -ne 0) {
        throw 'MovieLife demo Redis read failed.'
    }
    return (($result | Out-String).Trim())
}

function Remove-RedisKeys {
    param([string[]]$Keys)

    $uniqueKeys = @($Keys | Where-Object { $_ } | Sort-Object -Unique)
    foreach ($key in $uniqueKeys) {
        & docker exec movielife-redis redis-cli --raw DEL $key *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'MovieLife demo Redis key cleanup failed.'
        }
    }
}

function Remove-DemoRedisData {
    param([Parameter(Mandatory = $true)][string[]]$ShowtimeIds)

    $keysToDelete = New-Object System.Collections.Generic.List[string]

    foreach ($showtimeId in $ShowtimeIds) {
        foreach ($seatKey in (Get-RedisKeys -Pattern "seat:lock:${showtimeId}:*")) {
            $lockToken = Get-RedisValue -Key $seatKey
            $keysToDelete.Add($seatKey)
            if ($lockToken) {
                $keysToDelete.Add("seat:lock:token:$lockToken")
            }
        }
    }

    foreach ($tokenKey in (Get-RedisKeys -Pattern 'seat:lock:token:*')) {
        $metadataText = Get-RedisValue -Key $tokenKey
        if (-not $metadataText) {
            continue
        }
        try {
            $metadata = $metadataText | ConvertFrom-Json
            $metadataShowtimeId = [string]$metadata.showtimeId
            $metadataUserId = [long]$metadata.userId
            if ($ShowtimeIds -contains $metadataShowtimeId -or
                ($metadataUserId -ge $DemoIdMinimum -and $metadataUserId -le $DemoIdMaximum)) {
                $keysToDelete.Add($tokenKey)
            }
        } catch {
            # Unknown token metadata is not demonstrably demo-owned and is left untouched.
        }
    }

    foreach ($tokenKey in (Get-RedisKeys -Pattern 'login:token:*')) {
        $userIdText = Get-RedisValue -Key $tokenKey
        $userId = 0L
        if ([long]::TryParse($userIdText, [ref]$userId) -and
            $userId -ge $DemoIdMinimum -and $userId -le $DemoIdMaximum) {
            $keysToDelete.Add($tokenKey)
        }
    }

    foreach ($phone in $DemoPhones) {
        $keysToDelete.Add("login:code:$phone")
        $keysToDelete.Add("login:code:limit:$phone")
    }
    foreach ($venueId in $DemoVenueIds) {
        $keysToDelete.Add("venue:detail:$venueId")
    }
    $keysToDelete.Add("movie:detail:$DemoMovieId")
    $keysToDelete.Add("ai:review:summary:MOVIE:$DemoMovieId")
    foreach ($reviewId in $DemoReviewIds) {
        $keysToDelete.Add("review:like:$reviewId")
    }
    foreach ($userId in 1604000001..1604000004) {
        $keysToDelete.Add("feed:$userId")
    }
    Remove-RedisKeys -Keys $keysToDelete.ToArray()

    foreach ($venueId in $DemoVenueIds) {
        & docker exec movielife-redis redis-cli --raw ZREM 'venue:geo:上海:THEATER' $venueId *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'MovieLife demo Redis GEO cleanup failed.'
        }
    }
}

function Assert-DemoDataCheckResult {
    param([string[]]$Rows)

    if ($Rows.Count -eq 0) {
        throw 'MovieLife demo data verification returned no result.'
    }
    $values = (([string]($Rows | Select-Object -Last 1)).Trim() -split '\s+')
    $expected = @('4', '2', '1', '1', '0', '1', '0', '0', '6', '6',
                  '1', '6', '6', '0', '1', '3', '1', '0', '0')
    if ($values.Count -ne $expected.Count -or (Compare-Object $expected $values -SyncWindow 0)) {
        throw "MovieLife demo data verification failed. Expected $($expected -join ','); received $($values -join ',')."
    }
}

Assert-DemoDataInfrastructure

switch ($Action) {
    'install' {
        Assert-DemoApplicationStopped
        $mutableCount = Invoke-DemoMySqlQuery -Query @"
SELECT COUNT(*)
FROM tb_ticket_order
WHERE showtime_id IN (
  SELECT id FROM tb_showtime
  WHERE venue_id BETWEEN $DemoIdMinimum AND $DemoIdMaximum
);
"@
        if (([int](($mutableCount | Select-Object -Last 1).Trim())) -ne 0) {
            throw 'Demo-owned orders already exist. Run .\demo-data-reset.ps1 -Force before reinstalling the baseline.'
        }
        Invoke-DemoSqlFile -Name 'install' | Out-Null
        Assert-DemoDataCheckResult -Rows (Invoke-DemoSqlFile -Name 'check')
        Write-Host 'MovieLife deterministic demo data is installed and verified.'
    }
    'check' {
        Assert-DemoDataCheckResult -Rows (Invoke-DemoSqlFile -Name 'check')
        Write-Host 'MovieLife deterministic demo data verification passed.'
    }
    'reset' {
        if (-not $Force) {
            throw 'Reset deletes only Stage 16.4 demo-owned data. Re-run with: .\demo-data-reset.ps1 -Force'
        }
        Assert-DemoApplicationStopped
        $showtimeIds = Get-DemoShowtimeIds
        Remove-DemoRedisData -ShowtimeIds $showtimeIds
        $resetRows = Invoke-DemoSqlFile -Name 'reset'
        $remainingRows = [int]((($resetRows | Select-Object -Last 1)).Trim())
        if ($remainingRows -ne 0) {
            throw "MovieLife demo reset left $remainingRows reserved rows."
        }
        Write-Host 'MovieLife demo-owned MySQL rows and Redis data were reset; non-demo resources were not targeted.'
    }
}

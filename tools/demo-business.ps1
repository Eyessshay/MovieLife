[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')

$BaseUrl = "http://127.0.0.1:$DemoPort"
$ReportDirectory = Join-Path $DemoLogDirectory 'reports'
$ReportPath = Join-Path $ReportDirectory `
    ("demo-report-{0}.md" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$LatestReportPath = Join-Path $ReportDirectory 'latest.md'
$ReportLines = New-Object System.Collections.Generic.List[string]
$CurrentStep = 'initialize'
$StepNumber = 0

function Protect-Phone {
    param([Parameter(Mandatory = $true)][string]$Phone)
    if ($Phone.Length -lt 7) { return '***' }
    return $Phone.Substring(0, 3) + '****' + $Phone.Substring($Phone.Length - 4)
}

function Protect-Secret {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return '<empty>' }
    if ($Value.Length -le 8) { return '********' }
    return $Value.Substring(0, 4) + '...' + $Value.Substring($Value.Length - 4)
}

function Add-ReportLine {
    param([Parameter(Mandatory = $true)][string]$Text)
    $ReportLines.Add($Text)
}

function Complete-Report {
    param(
        [Parameter(Mandatory = $true)][bool]$Succeeded,
        [string]$FailureMessage
    )

    $status = if ($Succeeded) { 'PASS' } else { 'FAIL' }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# MovieLife Automated Business Demo Report')
    $lines.Add('')
    $lines.Add("- Result: $status")
    $lines.Add("- Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    $lines.Add("- Application: $BaseUrl")
    $lines.Add('- Dataset: Stage 16.4 deterministic baseline')
    if (-not $Succeeded) {
        $lines.Add("- Failed stage: $CurrentStep")
        $lines.Add("- Failure: $FailureMessage")
    }
    $lines.Add('')
    $lines.Add('## Execution')
    $lines.Add('')
    foreach ($line in $ReportLines) { $lines.Add($line) }
    $lines.Add('')
    $lines.Add('> Tokens, phone numbers, verification codes, and ticket codes are never stored in full.')

    New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
    $text = ($lines -join [Environment]::NewLine) + [Environment]::NewLine
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($ReportPath, $text, $encoding)
    [System.IO.File]::WriteAllText($LatestReportPath, $text, $encoding)
}

function Write-DemoStep {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Result
    )
    $script:StepNumber++
    $script:CurrentStep = $Name
    $line = ('[{0:D2}] PASS {1} - {2}' -f $script:StepNumber, $Name, $Result)
    Write-Host $line
    Add-ReportLine -Text ("- $line")
}

function Invoke-DemoHttp {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body,
        [string]$BearerToken
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $headers.Authorization = "Bearer $BearerToken"
    }
    $parameters = @{
        UseBasicParsing = $true
        Method = $Method
        Uri = $BaseUrl + $Path
        Headers = $headers
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }

    $status = 0
    $content = ''
    try {
        $response = Invoke-WebRequest @parameters
        $status = [int]$response.StatusCode
        $content = [string]$response.Content
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw }
        $status = [int]$webResponse.StatusCode
        try {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } catch {
            $content = [string]$_.ErrorDetails.Message
        }
    }

    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($content)) {
        try { $json = $content | ConvertFrom-Json } catch { $json = $null }
    }
    return [pscustomobject]@{
        HttpStatus = $status
        Code = if ($null -ne $json) { [string]$json.code } else { '<non-json>' }
        Message = if ($null -ne $json) { [string]$json.message } else { '<non-json>' }
        Data = if ($null -ne $json) { $json.data } else { $null }
    }
}

function Require-Response {
    param(
        [Parameter(Mandatory = $true)]$Response,
        [Parameter(Mandatory = $true)][int]$HttpStatus,
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    $script:CurrentStep = $Stage
    if ($Response.HttpStatus -ne $HttpStatus -or $Response.Code -ne $Code) {
        throw "$Stage failed: HTTP $($Response.HttpStatus), business code $($Response.Code), message $($Response.Message)."
    }
    return $Response.Data
}

function Get-DemoVerificationCode {
    param([Parameter(Mandatory = $true)][string]$Phone)
    $code = & docker exec movielife-redis redis-cli --raw GET "login:code:$Phone"
    if ($LASTEXITCODE -ne 0) {
        throw 'Local verification-code delivery read failed.'
    }
    $value = (($code | Out-String).Trim())
    if ($value -notmatch '^\d{6}$') {
        throw 'Local verification-code delivery did not contain a valid code.'
    }
    return $value
}

function Login-DemoUser {
    param(
        [Parameter(Mandatory = $true)][string]$Phone,
        [Parameter(Mandatory = $true)][string]$Role
    )
    $send = Invoke-DemoHttp -Method POST -Path '/api/user/code' -Body @{ phone = $Phone }
    Require-Response -Response $send -HttpStatus 200 -Code 'OK' -Stage "$Role send code" | Out-Null
    $code = Get-DemoVerificationCode -Phone $Phone
    $login = Invoke-DemoHttp -Method POST -Path '/api/user/login' `
        -Body @{ phone = $Phone; code = $code }
    $data = Require-Response -Response $login -HttpStatus 200 -Code 'OK' -Stage "$Role login"
    if ([string]::IsNullOrWhiteSpace([string]$data.token)) {
        throw "$Role login returned no token."
    }
    Write-DemoStep -Name "$Role login" `
        -Result "phone=$(Protect-Phone $Phone), token=$(Protect-Secret ([string]$data.token))"
    return [string]$data.token
}

function New-DemoShowtime {
    param(
        [Parameter(Mandatory = $true)][string]$RequestId,
        [Parameter(Mandatory = $true)][datetime]$StartTime,
        [Parameter(Mandatory = $true)][string]$StaffToken,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $body = @{
        requestId = $RequestId
        activityId = 1604000501
        hallId = 1604000302
        startTime = $StartTime.ToString('yyyy-MM-ddTHH:mm:ss')
        endTime = $StartTime.AddHours(2).ToString('yyyy-MM-ddTHH:mm:ss')
        basePrice = 188.00
    }
    $response = Invoke-DemoHttp -Method POST `
        -Path '/api/admin/venues/1604000101/activity-showtimes' `
        -Body $body -BearerToken $StaffToken
    $data = Require-Response -Response $response -HttpStatus 200 -Code 'OK' -Stage "$Label create"
    Write-DemoStep -Name "$Label create" `
        -Result "showtimeId=$($data.showtimeId), seats=$($data.totalSeats), status=$($data.status)"
    return [long]$data.showtimeId
}

function Lock-DemoSeats {
    param(
        [Parameter(Mandatory = $true)][long]$ShowtimeId,
        [Parameter(Mandatory = $true)][long[]]$SeatIds,
        [Parameter(Mandatory = $true)][string]$BuyerToken,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $response = Invoke-DemoHttp -Method POST -Path "/api/showtimes/$ShowtimeId/seats/lock" `
        -Body @{ seatIds = $SeatIds } -BearerToken $BuyerToken
    $data = Require-Response -Response $response -HttpStatus 200 -Code 'OK' -Stage "$Label lock seats"
    if ([string]::IsNullOrWhiteSpace([string]$data.lockToken)) {
        throw "$Label lock seats returned no lockToken."
    }
    Write-DemoStep -Name "$Label lock seats" `
        -Result "seatIds=$($SeatIds -join ','), lockToken=$(Protect-Secret ([string]$data.lockToken))"
    return [string]$data.lockToken
}

function New-DemoOrder {
    param(
        [Parameter(Mandatory = $true)][long]$ShowtimeId,
        [Parameter(Mandatory = $true)][long[]]$SeatIds,
        [Parameter(Mandatory = $true)][string]$LockToken,
        [Parameter(Mandatory = $true)][string]$BuyerToken,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $response = Invoke-DemoHttp -Method POST -Path '/api/orders' -BearerToken $BuyerToken `
        -Body @{ showtimeId = $ShowtimeId; seatIds = $SeatIds; lockToken = $LockToken }
    $data = Require-Response -Response $response -HttpStatus 200 -Code 'OK' -Stage "$Label create order"
    Write-DemoStep -Name "$Label create order" `
        -Result "orderId=$($data.orderId), status=$($data.status), amount=$($data.totalAmount)"
    return [long]$data.orderId
}

function Invoke-DemoOrderAction {
    param(
        [Parameter(Mandatory = $true)][long]$OrderId,
        [Parameter(Mandatory = $true)][ValidateSet('cancel', 'pay', 'issue', 'refund')][string]$Action,
        [Parameter(Mandatory = $true)][string]$BuyerToken,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $response = Invoke-DemoHttp -Method POST -Path "/api/orders/$OrderId/$Action" `
        -Body @{} -BearerToken $BuyerToken
    $data = Require-Response -Response $response -HttpStatus 200 -Code 'OK' -Stage $Label
    return $data
}

function Invoke-DemoSaleAction {
    param(
        [Parameter(Mandatory = $true)][long]$ShowtimeId,
        [Parameter(Mandatory = $true)][ValidateSet('stop-sale', 'resume-sale')][string]$Action,
        [Parameter(Mandatory = $true)][string]$StaffToken,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $response = Invoke-DemoHttp -Method POST `
        -Path "/api/admin/venues/1604000101/activity-showtimes/$ShowtimeId/$Action" `
        -Body @{} -BearerToken $StaffToken
    return Require-Response -Response $response -HttpStatus 200 -Code 'OK' -Stage $Label
}

function Convert-DataToStableJson {
    param([object]$Data)
    return ($Data | ConvertTo-Json -Depth 20 -Compress)
}

function Invoke-FinalFactQuery {
    param(
        [long]$MainShowtimeId,
        [long]$MainOrderId,
        [long]$BranchShowtimeId,
        [long]$CanceledOrderId,
        [long]$RefundedOrderId
    )
    $password = [System.IO.File]::ReadAllText($DemoPasswordFile).Trim()
    $query = @"
SELECT
  (SELECT status FROM tb_ticket_order WHERE id=$MainOrderId),
  (SELECT COUNT(*) FROM tb_ticket_order_item WHERE order_id=$MainOrderId AND ticket_status=2),
  (SELECT COUNT(*) FROM tb_ticket_order_item WHERE order_id=$MainOrderId AND ticket_status=1),
  (SELECT total_seats FROM tb_showtime WHERE id=$MainShowtimeId),
  (SELECT available_seats FROM tb_showtime WHERE id=$MainShowtimeId),
  (SELECT COUNT(*) FROM tb_showtime_seat WHERE showtime_id=$MainShowtimeId AND status=2 AND lock_order_id=$MainOrderId),
  (SELECT status FROM tb_ticket_order WHERE id=$CanceledOrderId),
  (SELECT status FROM tb_ticket_order WHERE id=$RefundedOrderId),
  (SELECT total_seats FROM tb_showtime WHERE id=$BranchShowtimeId),
  (SELECT available_seats FROM tb_showtime WHERE id=$BranchShowtimeId),
  (SELECT COUNT(*) FROM tb_showtime_seat WHERE showtime_id=$BranchShowtimeId AND status=0 AND lock_order_id IS NULL),
  (SELECT COUNT(*) FROM tb_collection WHERE user_id=1604000001),
  (SELECT SUM(like_count) FROM tb_review WHERE id IN (1604000701,1604000702,1604000703)),
  (SELECT COUNT(*) FROM tb_ai_review_summary WHERE target_type='MOVIE' AND target_id=1604000601),
  (SELECT COUNT(*) FROM tb_ai_summary_task WHERE target_type='MOVIE' AND target_id=1604000601 AND status='SUCCEEDED');
"@
    $result = & docker exec --env "MYSQL_PWD=$password" movielife-mysql `
        mysql --user=movielife --host=127.0.0.1 --database=movielife `
        --batch --skip-column-names --execute=$query
    if ($LASTEXITCODE -ne 0) { throw 'Final MySQL fact verification query failed.' }
    $values = ((([string]($result | Select-Object -Last 1)).Trim()) -split '\s+')
    $expected = @('PAID', '1', '1', '6', '4', '2', 'CANCELED', 'REFUNDED',
                  '6', '6', '6', '0', '0', '1', '1')
    if ($values.Count -ne $expected.Count -or (Compare-Object $expected $values -SyncWindow 0)) {
        throw "Final MySQL facts did not converge. Expected $($expected -join ','); received $($values -join ',')."
    }
}

try {
    $health = Get-MovieLifeDemoHealth
    if (-not $health.Healthy) { throw 'MovieLife demo health is not UP.' }
    Add-ReportLine -Text '- [00] PASS health - application/mysql/redis are UP'
    Write-Host '[00] PASS health - application/mysql/redis are UP'

    $staffToken = Login-DemoUser -Phone '13916040002' -Role 'target venue staff'
    $management = Invoke-DemoHttp -Method GET `
        -Path '/api/admin/venues/1604000101/activity-showtimes?page=1&pageSize=20' `
        -BearerToken $staffToken
    $managementData = Require-Response -Response $management -HttpStatus 200 -Code 'OK' `
        -Stage 'query management list'
    Write-DemoStep -Name 'query management list' `
        -Result "visible=$(@($managementData.list).Count)"

    $mainStart = (Get-Date).Date.AddDays(5).AddHours(18)
    $branchStart = (Get-Date).Date.AddDays(6).AddHours(18)
    $mainShowtimeId = New-DemoShowtime -RequestId 'demo-16-5-main' `
        -StartTime $mainStart -StaffToken $staffToken -Label 'main flow showtime'

    $activities = Invoke-DemoHttp -Method GET `
        -Path '/api/activities?city=%E4%B8%8A%E6%B5%B7&page=1&pageSize=100'
    $activityData = Require-Response -Response $activities -HttpStatus 200 -Code 'OK' `
        -Stage 'anonymous activity discovery'
    if (-not (@($activityData.list | Where-Object { [long]$_.id -eq 1604000501 }).Count -eq 1)) {
        throw 'anonymous activity discovery failed: demo activity was not returned.'
    }
    Write-DemoStep -Name 'anonymous activity discovery' -Result 'demo activity found'

    $publicShowtimes = Invoke-DemoHttp -Method GET `
        -Path "/api/activities/1604000501/showtimes?date=$($mainStart.ToString('yyyy-MM-dd'))&venueId=1604000101"
    $publicData = Require-Response -Response $publicShowtimes -HttpStatus 200 -Code 'OK' `
        -Stage 'anonymous showtime discovery'
    if (-not (@($publicData | Where-Object { [long]$_.showtimeId -eq $mainShowtimeId }).Count -eq 1)) {
        throw 'anonymous showtime discovery failed: created showtime was not returned.'
    }
    Write-DemoStep -Name 'anonymous showtime discovery' -Result "showtimeId=$mainShowtimeId"

    $seatMap = Invoke-DemoHttp -Method GET -Path "/api/showtimes/$mainShowtimeId/seats"
    $seatData = Require-Response -Response $seatMap -HttpStatus 200 -Code 'OK' -Stage 'query seat map'
    $seatIds = @($seatData.seats | ForEach-Object { [long]$_.seatId } | Sort-Object)
    if ($seatIds.Count -ne 6) { throw "query seat map failed: expected 6 seats, received $($seatIds.Count)." }
    Write-DemoStep -Name 'query seat map' -Result '6 available fixed seats'

    $buyerToken = Login-DemoUser -Phone '13916040001' -Role 'buyer'
    $aiBefore = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET -Path '/api/ai/summaries/MOVIE/1604000601') `
        -HttpStatus 200 -Code 'OK' -Stage 'snapshot AI summary')
    $collectionBefore = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET `
            -Path '/api/collections/check?targetType=MOVIE&targetId=1604000601' `
            -BearerToken $buyerToken) -HttpStatus 200 -Code 'OK' -Stage 'snapshot collection')
    $reviewBefore = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET -Path '/api/reviews/1604000701') `
        -HttpStatus 200 -Code 'OK' -Stage 'snapshot review')
    $geoPath = '/api/venues/nearby?city=%E4%B8%8A%E6%B5%B7' `
        + '&type=THEATER&x=121.506&y=31.235&distance=50000&page=1&pageSize=20'
    $geoBefore = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET -Path $geoPath) `
        -HttpStatus 200 -Code 'OK' -Stage 'snapshot venue GEO')

    $mainSeats = [long[]]@($seatIds[0], $seatIds[1])
    $mainLock = Lock-DemoSeats -ShowtimeId $mainShowtimeId -SeatIds $mainSeats `
        -BuyerToken $buyerToken -Label 'main flow'
    $mainOrderId = New-DemoOrder -ShowtimeId $mainShowtimeId -SeatIds $mainSeats `
        -LockToken $mainLock -BuyerToken $buyerToken -Label 'main flow'
    $pay = Invoke-DemoOrderAction -OrderId $mainOrderId -Action pay `
        -BuyerToken $buyerToken -Label 'main flow pay'
    Write-DemoStep -Name 'main flow pay' -Result "orderId=$mainOrderId, status=$($pay.status)"
    $tickets = @(Invoke-DemoOrderAction -OrderId $mainOrderId -Action issue `
        -BuyerToken $buyerToken -Label 'main flow issue tickets')
    if ($tickets.Count -ne 2) { throw "main flow issue tickets failed: expected 2 tickets, received $($tickets.Count)." }
    $ticketCode = [string]$tickets[0].ticketCode
    Write-DemoStep -Name 'main flow issue tickets' `
        -Result "tickets=2, firstTicket=$(Protect-Secret $ticketCode)"

    $otherStaffToken = Login-DemoUser -Phone '13916040003' -Role 'other venue staff'
    $foreignVerify = Invoke-DemoHttp -Method POST -Path '/api/admin/tickets/verify' `
        -Body @{ ticketCode = $ticketCode } -BearerToken $otherStaffToken
    Require-Response -Response $foreignVerify -HttpStatus 403 -Code 'TICKET_VERIFY_FORBIDDEN' `
        -Stage 'other venue verification rejection' | Out-Null
    Write-DemoStep -Name 'other venue verification rejection' `
        -Result 'HTTP 403, TICKET_VERIFY_FORBIDDEN'

    $verify = Invoke-DemoHttp -Method POST -Path '/api/admin/tickets/verify' `
        -Body @{ ticketCode = $ticketCode } -BearerToken $staffToken
    $verified = Require-Response -Response $verify -HttpStatus 200 -Code 'OK' `
        -Stage 'target venue ticket verification'
    if ([int]$verified.ticketStatus -ne 2) { throw 'target venue ticket verification returned an unexpected ticket status.' }
    Write-DemoStep -Name 'target venue ticket verification' `
        -Result "ticket=$(Protect-Secret $ticketCode), status=USED"

    $branchShowtimeId = New-DemoShowtime -RequestId 'demo-16-5-branches' `
        -StartTime $branchStart -StaffToken $staffToken -Label 'branch showtime'
    $oldSeats = [long[]]@($seatIds[2])
    $oldLock = Lock-DemoSeats -ShowtimeId $branchShowtimeId -SeatIds $oldSeats `
        -BuyerToken $buyerToken -Label 'pre-stop temporary'
    $stopped = Invoke-DemoSaleAction -ShowtimeId $branchShowtimeId -Action stop-sale `
        -StaffToken $staffToken -Label 'stop sale after temporary lock'
    Write-DemoStep -Name 'stop sale after temporary lock' -Result "status=$($stopped.status)"

    $newLockRejected = Invoke-DemoHttp -Method POST `
        -Path "/api/showtimes/$branchShowtimeId/seats/lock" `
        -Body @{ seatIds = [long[]]@($seatIds[3]) } -BearerToken $buyerToken
    Require-Response -Response $newLockRejected -HttpStatus 404 -Code 'SHOWTIME_NOT_FOUND' `
        -Stage 'reject new lock after stop' | Out-Null
    Write-DemoStep -Name 'reject new lock after stop' -Result 'HTTP 404, SHOWTIME_NOT_FOUND'

    $oldOrderRejected = Invoke-DemoHttp -Method POST -Path '/api/orders' `
        -Body @{ showtimeId = $branchShowtimeId; seatIds = $oldSeats; lockToken = $oldLock } `
        -BearerToken $buyerToken
    Require-Response -Response $oldOrderRejected -HttpStatus 404 -Code 'SHOWTIME_NOT_FOUND' `
        -Stage 'reject old lockToken order after stop' | Out-Null
    Write-DemoStep -Name 'reject old lockToken order after stop' `
        -Result 'HTTP 404, SHOWTIME_NOT_FOUND'

    $release = Invoke-DemoHttp -Method POST `
        -Path "/api/showtimes/$branchShowtimeId/seats/release" `
        -Body @{ lockToken = $oldLock } -BearerToken $buyerToken
    Require-Response -Response $release -HttpStatus 200 -Code 'OK' `
        -Stage 'release pre-stop temporary lock' | Out-Null
    Write-DemoStep -Name 'release pre-stop temporary lock' -Result 'released through HTTP'

    $resumed = Invoke-DemoSaleAction -ShowtimeId $branchShowtimeId -Action resume-sale `
        -StaffToken $staffToken -Label 'resume for cancel branch'
    Write-DemoStep -Name 'resume for cancel branch' -Result "status=$($resumed.status)"
    $branchSeats = [long[]]@($seatIds[2], $seatIds[3])
    $cancelLock = Lock-DemoSeats -ShowtimeId $branchShowtimeId -SeatIds $branchSeats `
        -BuyerToken $buyerToken -Label 'pre-stop order'
    $canceledOrderId = New-DemoOrder -ShowtimeId $branchShowtimeId -SeatIds $branchSeats `
        -LockToken $cancelLock -BuyerToken $buyerToken -Label 'pre-stop order'
    Invoke-DemoSaleAction -ShowtimeId $branchShowtimeId -Action stop-sale `
        -StaffToken $staffToken -Label 'stop sale with existing order' | Out-Null
    Write-DemoStep -Name 'stop sale with existing order' -Result "orderId=$canceledOrderId remains actionable"
    Invoke-DemoOrderAction -OrderId $canceledOrderId -Action cancel `
        -BuyerToken $buyerToken -Label 'cancel pre-stop order' | Out-Null
    Write-DemoStep -Name 'cancel pre-stop order' -Result "orderId=$canceledOrderId, status=CANCELED"

    Invoke-DemoSaleAction -ShowtimeId $branchShowtimeId -Action resume-sale `
        -StaffToken $staffToken -Label 'resume for refund branch' | Out-Null
    Write-DemoStep -Name 'resume for refund branch' -Result 'status=ON_SALE'
    $refundLock = Lock-DemoSeats -ShowtimeId $branchShowtimeId -SeatIds $branchSeats `
        -BuyerToken $buyerToken -Label 'refund branch'
    $refundedOrderId = New-DemoOrder -ShowtimeId $branchShowtimeId -SeatIds $branchSeats `
        -LockToken $refundLock -BuyerToken $buyerToken -Label 'refund branch'
    Invoke-DemoOrderAction -OrderId $refundedOrderId -Action pay `
        -BuyerToken $buyerToken -Label 'refund branch pay' | Out-Null
    Write-DemoStep -Name 'refund branch pay' -Result "orderId=$refundedOrderId, status=PAID"
    Invoke-DemoOrderAction -OrderId $refundedOrderId -Action refund `
        -BuyerToken $buyerToken -Label 'refund paid order' | Out-Null
    Write-DemoStep -Name 'refund paid order' -Result "orderId=$refundedOrderId, status=REFUNDED"

    $aiAfter = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET -Path '/api/ai/summaries/MOVIE/1604000601') `
        -HttpStatus 200 -Code 'OK' -Stage 'verify AI summary isolation')
    $collectionAfter = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET `
            -Path '/api/collections/check?targetType=MOVIE&targetId=1604000601' `
            -BearerToken $buyerToken) -HttpStatus 200 -Code 'OK' -Stage 'verify collection isolation')
    $reviewAfter = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET -Path '/api/reviews/1604000701') `
        -HttpStatus 200 -Code 'OK' -Stage 'verify review isolation')
    $geoAfter = Convert-DataToStableJson (Require-Response `
        -Response (Invoke-DemoHttp -Method GET -Path $geoPath) `
        -HttpStatus 200 -Code 'OK' -Stage 'verify venue GEO isolation')
    if ($aiBefore -ne $aiAfter -or $collectionBefore -ne $collectionAfter `
        -or $reviewBefore -ne $reviewAfter -or $geoBefore -ne $geoAfter) {
        throw 'Side-effect isolation failed: AI Summary, collection, Review, or Venue GEO response changed.'
    }
    Write-DemoStep -Name 'verify unrelated feature isolation' `
        -Result 'AI Summary, collection, Review Like view and GEO unchanged'

    Invoke-FinalFactQuery -MainShowtimeId $mainShowtimeId -MainOrderId $mainOrderId `
        -BranchShowtimeId $branchShowtimeId -CanceledOrderId $canceledOrderId `
        -RefundedOrderId $refundedOrderId
    $lockKeys = @(& docker exec movielife-redis redis-cli --raw --scan --pattern 'seat:lock:*' |
        ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0) { throw 'Final Redis lock verification failed.' }
    if ($lockKeys.Count -ne 0) { throw "Final Redis lock verification found $($lockKeys.Count) residual key(s)." }
    Write-DemoStep -Name 'verify final convergence' `
        -Result 'orders/tickets/seats/inventory converged; Redis seat locks=0'

    Complete-Report -Succeeded $true
    Write-Host "MovieLife automated business demo completed."
    Write-Host "Sanitized report: $ReportPath"
} catch {
    $safeMessage = [string]$_.Exception.Message
    Add-ReportLine -Text ("- FAIL $CurrentStep - $safeMessage")
    Complete-Report -Succeeded $false -FailureMessage $safeMessage
    Write-Error "Business demo failed at '$CurrentStep': $safeMessage Report: $ReportPath"
    exit 1
}

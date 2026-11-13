param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ShowtimeId,
    [string]$OrderId,
    [int]$PollSeconds = 0,
    [switch]$TokenFromClipboard
)

# Token chi nam trong bo nho; khong ghi ra log hoac file.
$ErrorActionPreference = 'Stop'
$token = $env:VINGO_ACCESS_TOKEN
if ($TokenFromClipboard) {
    $token = Get-Clipboard -Raw
}
if (-not $token) {
    $secureToken = Read-Host 'Access token buyer (an)' -AsSecureString
    $token = [System.Net.NetworkCredential]::new('', $secureToken).Password
}
$token = ($token.Trim() -replace '^Bearer\s+', '') -replace '\s+', ''
if ($token -notmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$') {
    throw 'Access token khong dung dinh dang JWT. Chi dan chuoi eyJ... gom 3 phan ngan bang dau cham; khong dan JSON, dau nhay hoac lenh terminal.'
}
$api = $BaseUrl.TrimEnd('/') + '/api/v1'
$uri = [uri]$api
if (-not $uri.IsAbsoluteUri -or ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.IsLoopback)) -or $uri.UserInfo) {
    throw 'BaseUrl must use HTTPS; HTTP is allowed only for loopback hosts.'
}
$headers = @{ Authorization = "Bearer $token" }

function Call-Api([string]$Method, [string]$Path, $Body = $null, [string]$Key = '') {
    $options = @{ Uri = "$api$Path"; Method = $Method; Headers = $headers; TimeoutSec = 20; MaximumRedirection = 0 }
    if ($Key) { $options.Headers = $headers.Clone(); $options.Headers['Idempotency-Key'] = $Key }
    if ($null -ne $Body) { $options.Body = ConvertTo-Json -InputObject $Body -Depth 12 -Compress; $options.ContentType = 'application/json' }
    try { Invoke-RestMethod @options }
    catch { throw "API $Method $Path that bai: $($_.ErrorDetails.Message) $($_.Exception.Message)" }
}

try {
    if (-not $OrderId) {
        if (-not $ShowtimeId) {
            $events = Call-Api GET '/events?limit=20'
            foreach ($event in $events.data) {
                $shows = Call-Api GET "/events/$($event.id)/showtimes"
                $show = $shows.data | Where-Object status -eq 'ON_SALE' | Select-Object -First 1
                if ($show) { $ShowtimeId = $show.id; break }
            }
        }
        if (-not $ShowtimeId) { throw 'Khong tim thay suat ON_SALE trong 20 su kien dau. Truyen -ShowtimeId.' }
        $map = Call-Api GET "/showtimes/$ShowtimeId/seatmap"
        $holdBody = $null
        foreach ($zone in $map.zones) {
            if ($zone.kind -eq 'STANDING' -and $zone.seats_left -gt 0) {
                $holdBody = @{ zone_id = $zone.zone_id; quantity = 1 }; break
            }
            $seat = $zone.seats | Where-Object { $_.status -eq 'AVAILABLE' -and $_.seat_type -ne 'COUPLE' } | Select-Object -First 1
            if ($seat) { $holdBody = @{ seat_ids = @($seat.seat_id) }; break }
        }
        if (-not $holdBody) { throw 'Khong tim thay ghe/cho dung trong. Truyen suat khac.' }
        $order = Call-Api POST "/showtimes/$ShowtimeId/holds" $holdBody ([guid]::NewGuid().ToString())
        $OrderId = $order.id
        if (-not $OrderId) { throw 'Response hold thieu id don.' }
        Write-Host "PASS hold: order_id=$OrderId; total=$($order.total_amount); expires=$($order.expires_at)"
    }
    $key = [guid]::NewGuid().ToString()
    $payment = Call-Api POST "/orders/$OrderId/payments" @{} $key
    $repeat = Call-Api POST "/orders/$OrderId/payments" @{} $key
    if (-not $payment.id -or $payment.id -ne $repeat.id) { throw 'Idempotency khong tra cung payment intent.' }
    if (-not $payment.payment_url) { throw 'Response thieu payment_url.' }
    Write-Host "PASS payment + idempotency: txn_ref=$($payment.txn_ref); amount=$($payment.amount)"
    Write-Host "Mo URL sau de thanh toan SANDBOX (xac nhan hostname sandbox truoc):"
    Write-Host $payment.payment_url
    Write-Host "Co the tiep tuc voi -OrderId $OrderId de dung lai don neu con han."
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($PollSeconds)
    do {
        $current = Call-Api GET "/orders/$OrderId"
        $intent = Call-Api GET "/orders/$OrderId/payment"
        Write-Host "order=$($current.status); payment=$($intent.status)"
        if ($current.status -eq 'PAID') {
            $tickets = Call-Api GET '/tickets?limit=100'
            $found = @($tickets.data | Where-Object order_code -eq $current.code)
            Write-Host "PAID; so ve tim thay trong trang dau=$($found.Count)"
            exit 0
        }
        if ($current.status -ne 'PENDING') { throw "Don ket thuc voi trang thai $($current.status)." }
        if ([DateTimeOffset]::UtcNow -ge $deadline) { break }
        Start-Sleep -Seconds 5
    } while ($true)
    Write-Host 'PASS tao hold/payment; chua xac nhan thanh toan. Dung -PollSeconds 300 de cho ket qua trong luc thanh toan sandbox.'
} catch {
    Write-Error $_
    exit 1
} finally {
    $headers.Clear()
    $token = $null
}

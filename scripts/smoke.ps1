param([string]$BaseUrl = 'http://localhost:8080', [switch]$TestOutage)
$ErrorActionPreference = 'Stop'

function Wait-For([scriptblock]$Condition, [string]$Description) {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        try { if (& $Condition) { return } } catch { }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description"
}

Wait-For { (Invoke-RestMethod "$BaseUrl/actuator/health").status -eq 'UP' } 'application startup'
$eventId = 'smoke-' + [guid]::NewGuid().ToString('N')
$winnerBet = $eventId + '-win'
$loserBet = $eventId + '-lose'

foreach ($item in @(@{Id=$winnerBet; Winner='team-a'}, @{Id=$loserBet; Winner='team-b'})) {
    $body = @{
        betId=$item.Id; userId='demo-user'; eventId=$eventId; eventMarketId='match-winner'
        eventWinnerId=$item.Winner; betAmount=10.00
    } | ConvertTo-Json
    Invoke-RestMethod "$BaseUrl/api/bets" -Method Post -ContentType 'application/json' -Body $body | Out-Null
}
$outcome = @{eventId=$eventId; eventName='Demo final'; eventWinnerId='team-a'} | ConvertTo-Json
Invoke-RestMethod "$BaseUrl/api/event-outcomes" -Method Post -ContentType 'application/json' -Body $outcome | Out-Null
Wait-For {
    $win = Invoke-RestMethod "$BaseUrl/api/bets/$winnerBet"
    $lose = Invoke-RestMethod "$BaseUrl/api/bets/$loserBet"
    $win.status -eq 'WON' -and $win.payout -eq 20 -and $lose.status -eq 'LOST' -and $lose.payout -eq 0
} 'Kafka -> outbox -> RocketMQ -> settlement'

# Repeat the API publication: final payout must remain exactly 20, never accumulate.
Invoke-RestMethod "$BaseUrl/api/event-outcomes" -Method Post -ContentType 'application/json' -Body $outcome | Out-Null
Start-Sleep -Seconds 3
$result = Invoke-RestMethod "$BaseUrl/api/bets/$winnerBet"
if ($result.status -ne 'WON' -or $result.payout -ne 20) { throw 'Duplicate outcome changed the payout' }

# Send the same instruction through the real RocketMQ broker, as a broker redelivery would.
$instruction = @{betId=$winnerBet; eventId=$eventId; eventWinnerId='team-a'} | ConvertTo-Json -Compress
$instruction | docker compose exec -T rocketmq sh -c 'IFS= read -r body; mqadmin sendMessage -n nameserver:9876 -t bet-settlements -p "$body"' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not publish duplicate RocketMQ instruction' }
Wait-For { (docker compose logs --no-color app | Out-String).Contains("Duplicate settlement ignored betId=$winnerBet") } 'duplicate RocketMQ consumption'
$result = Invoke-RestMethod "$BaseUrl/api/bets/$winnerBet"
if ($result.status -ne 'WON' -or $result.payout -ne 20) { throw 'Duplicate RocketMQ delivery changed the payout' }

if ($TestOutage) {
    $outageEvent = 'outage-' + [guid]::NewGuid().ToString('N')
    $outageBet = $outageEvent + '-bet'
    $body = @{
        betId=$outageBet; userId='demo-user'; eventId=$outageEvent; eventMarketId='match-winner'
        eventWinnerId='team-a'; betAmount=10.00
    } | ConvertTo-Json
    Invoke-RestMethod "$BaseUrl/api/bets" -Method Post -ContentType 'application/json' -Body $body | Out-Null
    docker compose stop rocketmq | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop RocketMQ for outage test' }
    try {
        $outcome = @{eventId=$outageEvent; eventName='Outage final'; eventWinnerId='team-a'} | ConvertTo-Json
        Invoke-RestMethod "$BaseUrl/api/event-outcomes" -Method Post -ContentType 'application/json' -Body $outcome | Out-Null
        Wait-For {
            $bet = Invoke-RestMethod "$BaseUrl/api/bets/$outageBet"
            $bet.status -eq 'PENDING' -and
                (docker compose logs --no-color app | Out-String).Contains("Settlement send failed betId=$outageBet")
        } 'outbox retry during broker outage'
    } finally {
        docker compose start rocketmq | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart RocketMQ after outage test' }
    }
    Wait-For {
        $bet = Invoke-RestMethod "$BaseUrl/api/bets/$outageBet"
        $bet.status -eq 'WON' -and $bet.payout -eq 20
    } 'RocketMQ recovery and settlement'
}
Write-Output "PASS: winner paid 20, loser paid 0, Kafka and RocketMQ duplicates unchanged$(if ($TestOutage) { ', broker outage recovered' }). Event: $eventId"

package com.sportsbetting.settlement.domain.settlement.service;

import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementMessage;
import com.sportsbetting.settlement.domain.bet.repository.BetRepository;
import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {
    private final BetRepository betRepository;
    private final OutboxRepository outboxRepository;

    @Transactional
    public void settle(SettlementMessage message) {
        var expected = outboxRepository.findById(message.betId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement instruction"));
        if (!expected.message().equals(message)) {
            throw new IllegalArgumentException("Settlement does not match the recorded outcome");
        }
        var bet = betRepository.lockById(message.betId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown bet"));
        if (bet.getStatus() == BetStatus.WON || bet.getStatus() == BetStatus.LOST) {
            log.info("Duplicate settlement ignored betId={}", message.betId());
            return;
        }
        bet.settle(message.eventWinnerId());
        log.info("Bet settled betId={} status={} payout={}", bet.getBetId(), bet.getStatus(), bet.getPayout());
    }
}

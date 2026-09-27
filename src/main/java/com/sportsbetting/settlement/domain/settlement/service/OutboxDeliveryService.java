package com.sportsbetting.settlement.domain.settlement.service;

import com.sportsbetting.settlement.domain.settlement.messaging.SettlementPublisher;
import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.messaging-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDeliveryService {
    private final OutboxRepository outboxRepository;
    private final SettlementPublisher settlementPublisher;
    private final Clock clock;

    @Transactional
    public void deliver(String betId) {
        var entry = outboxRepository.lockById(betId).orElseThrow();
        if (entry.isSent() || entry.getNextAttempt().isAfter(clock.instant())) {
            return;
        }
        try {
            settlementPublisher.send(entry.message());
            entry.markSent();
            log.info("Settlement instruction sent betId={}", betId);
        } catch (RuntimeException ex) {
            entry.retryLater(clock.instant());
            log.warn("Settlement send failed betId={} attempts={} nextAttempt={}",
                    betId, entry.getAttempts(), entry.getNextAttempt(), ex);
        }
    }
}

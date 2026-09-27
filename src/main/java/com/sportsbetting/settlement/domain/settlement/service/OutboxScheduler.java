package com.sportsbetting.settlement.domain.settlement.service;

import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.messaging-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private final OutboxRepository outboxRepository;
    private final OutboxDeliveryService outboxDeliveryService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.outbox-delay-ms:1000}")
    public void publishDue() {
        for (String id : outboxRepository.findDue(clock.instant(), PageRequest.of(0, 50))) {
            try {
                outboxDeliveryService.deliver(id);
            } catch (RuntimeException ex) {
                log.error("Outbox transaction failed betId={}; retained for retry", id, ex);
            }
        }
    }
}

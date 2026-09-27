package com.sportsbetting.settlement.domain.outcome.service;

import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.domain.bet.repository.BetRepository;
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
public class OutcomeScheduler {
    private final BetRepository betRepository;
    private final OutcomeService outcomeService;

    @Scheduled(fixedDelayString = "${app.match-delay-ms:1000}")
    public void matchRemaining() {
        for (String eventId : betRepository.findRecordedEventsWithBets(BetStatus.OPEN, PageRequest.of(0, 50))) {
            try {
                outcomeService.matchBatch(eventId);
            } catch (RuntimeException ex) {
                log.error("Outcome matching failed eventId={}; retained for retry", eventId, ex);
            }
        }
    }
}

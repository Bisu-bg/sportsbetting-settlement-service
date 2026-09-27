package com.sportsbetting.settlement.domain.outcome.service;

import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.domain.outcome.model.RecordedOutcome;
import com.sportsbetting.settlement.domain.settlement.model.SettlementOutbox;
import com.sportsbetting.settlement.domain.bet.repository.BetRepository;
import com.sportsbetting.settlement.domain.outcome.repository.OutcomeRepository;
import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutcomeService {
    private final EventLockService eventLockService;
    private final OutcomeRepository outcomeRepository;
    private final BetRepository betRepository;
    private final OutboxRepository outboxRepository;

    @Transactional
    public void handle(EventOutcome outcome) {
        eventLockService.acquire(outcome.eventId());
        var existing = outcomeRepository.findById(outcome.eventId());
        if (existing.isPresent()) {
            if (!existing.get().getEventWinnerId().equals(outcome.eventWinnerId())) {
                throw new IllegalArgumentException("Conflicting winner for event " + outcome.eventId());
            }
            log.info("Duplicate outcome resumed eventId={}", outcome.eventId());
        } else {
            outcomeRepository.save(new RecordedOutcome(outcome));
        }
        match(outcome.eventId(), outcome.eventWinnerId());
    }

    @Transactional
    public void matchBatch(String eventId) {
        eventLockService.acquire(eventId);
        var outcome = outcomeRepository.findById(eventId).orElseThrow();
        match(eventId, outcome.getEventWinnerId());
    }

    private void match(String eventId, String winnerId) {
        var matches = betRepository.findByEventIdAndStatus(eventId, BetStatus.OPEN, PageRequest.of(0, 50));
        for (Bet bet : matches) {
            bet.awaitSettlement();
            outboxRepository.save(new SettlementOutbox(bet.getBetId(), eventId, winnerId));
        }
        log.info("Outcome matching batch eventId={} matchedBets={}", eventId, matches.size());
    }
}

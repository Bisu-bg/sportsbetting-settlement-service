package com.sportsbetting.settlement.domain.bet.service;

import com.sportsbetting.settlement.domain.bet.api.PlaceBet;
import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.domain.bet.repository.BetRepository;
import com.sportsbetting.settlement.domain.outcome.repository.OutcomeRepository;
import com.sportsbetting.settlement.domain.outcome.service.EventLockService;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@Service
@RequiredArgsConstructor
@Slf4j
public class BetService {
    private final BetRepository betRepository;
    private final OutcomeRepository outcomeRepository;
    private final EventLockService eventLockService;

    @Transactional
    public Bet place(PlaceBet request) {
        eventLockService.acquire(request.eventId());
        if (outcomeRepository.existsById(request.eventId())) {
            throw new IllegalStateException("The event already has a recorded outcome");
        }
        if (betRepository.existsById(request.betId())) {
            throw new IllegalStateException("Bet ID already exists");
        }
        Bet bet;
        try {
            bet = betRepository.saveAndFlush(new Bet(request));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Bet ID already exists", ex);
        }
        log.info("Bet placed betId={} eventId={}", bet.getBetId(), bet.getEventId());
        return bet;
    }

    @Transactional(readOnly = true)
    public Bet get(String id) {
        return betRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Bet not found"));
    }
}

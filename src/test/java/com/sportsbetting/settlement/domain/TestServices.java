package com.sportsbetting.settlement.domain;

import com.sportsbetting.settlement.support.BetFixtures;
import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.domain.outcome.model.RecordedOutcome;
import com.sportsbetting.settlement.domain.settlement.model.SettlementOutbox;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementMessage;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementPublisher;
import com.sportsbetting.settlement.domain.bet.repository.BetRepository;
import com.sportsbetting.settlement.domain.outcome.repository.OutcomeRepository;
import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import com.sportsbetting.settlement.domain.bet.service.BetService;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeService;
import com.sportsbetting.settlement.domain.outcome.service.EventLockService;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeScheduler;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import com.sportsbetting.settlement.domain.settlement.service.OutboxDeliveryService;
import com.sportsbetting.settlement.domain.settlement.service.OutboxScheduler;
import com.sportsbetting.settlement.domain.settlement.service.SettlementService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestServices {
    private final BetRepository betRepository = mock(BetRepository.class);
    private final OutcomeRepository outcomeRepository = mock(OutcomeRepository.class);
    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final EventLockService eventLockService = mock(EventLockService.class);
    private final EventOutcome outcome = new EventOutcome("event-1", "Final", "a");

    @Test
    void betPlacementAndLookupRespectClosedEventsAndUniqueIds() {
        var betService = new BetService(betRepository, outcomeRepository, eventLockService);
        var request = BetFixtures.request("bet", "a");
        when(betRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var placed = betService.place(request);
        assertThat(placed.getBetId()).isEqualTo("bet");
        verify(eventLockService).acquire("event-1");
        when(betRepository.findById("bet")).thenReturn(Optional.of(placed));
        assertThat(betService.get("bet")).isSameAs(placed);
        assertThatThrownBy(() -> betService.get("absent")).isInstanceOf(NoSuchElementException.class);
        when(betRepository.existsById("bet")).thenReturn(true);
        assertThatThrownBy(() -> betService.place(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        when(outcomeRepository.existsById("event-1")).thenReturn(true);
        assertThatThrownBy(() -> betService.place(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("recorded outcome");
        when(outcomeRepository.existsById("event-1")).thenReturn(false);
        when(betRepository.existsById("bet")).thenReturn(false);
        when(betRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> betService.place(request)).isInstanceOf(IllegalStateException.class).hasMessage("Bet ID already exists");
    }

    @Test
    void outcomeCreatesInstructionsOnceAndRejectsConflictingWinner() {
        var outcomeService = new OutcomeService(eventLockService, outcomeRepository, betRepository, outboxRepository);
        var bet = new Bet(BetFixtures.request("bet", "a"));
        when(betRepository.findByEventIdAndStatus("event-1", BetStatus.OPEN, PageRequest.of(0, 50)))
                .thenReturn(List.of(bet), List.of(), List.of());
        outcomeService.handle(outcome);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.PENDING);
        verify(outboxRepository).save(argThat(entry -> entry.message().equals(new SettlementMessage("bet", "event-1", "a"))));
        verify(outcomeRepository).save(any());
        when(outcomeRepository.findById("event-1")).thenReturn(Optional.of(new RecordedOutcome(outcome)));
        outcomeService.handle(outcome);
        verify(outboxRepository, times(1)).save(any());
        assertThatThrownBy(() -> outcomeService.handle(new EventOutcome("event-1", "Final", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        outcomeService.matchBatch("event-1");
        verify(eventLockService, times(4)).acquire("event-1");
    }

    @Test
    void outcomeWithNoBetsStillClosesEvent() {
        new OutcomeService(eventLockService, outcomeRepository, betRepository, outboxRepository).handle(outcome);
        verify(outcomeRepository).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void settlementValidatesInstructionsAndIgnoresTerminalDuplicates() {
        var settlementService = new SettlementService(betRepository, outboxRepository);
        var message = new SettlementMessage("bet", "event-1", "a");
        assertThatThrownBy(() -> settlementService.settle(message)).hasMessage("Unknown settlement instruction");
        when(outboxRepository.findById("bet")).thenReturn(Optional.of(new SettlementOutbox("bet", "event-1", "b")));
        assertThatThrownBy(() -> settlementService.settle(message)).hasMessageContaining("does not match");
        when(outboxRepository.findById("bet")).thenReturn(Optional.of(new SettlementOutbox("bet", "event-1", "a")));
        assertThatThrownBy(() -> settlementService.settle(message)).hasMessage("Unknown bet");
        var winner = new Bet(BetFixtures.request("bet", "a"));
        when(betRepository.lockById("bet")).thenReturn(Optional.of(winner));
        winner.awaitSettlement();
        settlementService.settle(message);
        settlementService.settle(message);
        assertThat(winner.getPayout()).isEqualByComparingTo("20");
        var loser = new Bet(BetFixtures.request("bet", "b"));
        loser.awaitSettlement();
        when(betRepository.lockById("bet")).thenReturn(Optional.of(loser));
        settlementService.settle(message);
        settlementService.settle(message);
        assertThat(loser.getStatus()).isEqualTo(BetStatus.LOST);
    }

    @Test
    void outboxChecksDueTimeAndRetriesFailedSends() {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var publisher = mock(SettlementPublisher.class);
        var outboxDeliveryService = new OutboxDeliveryService(outboxRepository, publisher, clock);
        assertThatThrownBy(() -> outboxDeliveryService.deliver("absent")).isInstanceOf(NoSuchElementException.class);
        var entry = new SettlementOutbox("bet", "event", "a");
        when(outboxRepository.lockById("bet")).thenReturn(Optional.of(entry));
        doThrow(new IllegalStateException("offline")).when(publisher).send(any());
        outboxDeliveryService.deliver("bet");
        assertThat(entry.isSent()).isFalse();
        assertThat(entry.getAttempts()).isEqualTo(1);
        outboxDeliveryService.deliver("bet");
        verify(publisher, times(1)).send(any());
        reset(publisher);
        var retryOutboxDeliveryService = new OutboxDeliveryService(outboxRepository, publisher, Clock.fixed(Instant.EPOCH.plusSeconds(2), ZoneOffset.UTC));
        retryOutboxDeliveryService.deliver("bet");
        retryOutboxDeliveryService.deliver("bet");
        assertThat(entry.isSent()).isTrue();
        verify(publisher, times(1)).send(entry.message());
    }

    @Test
    void schedulerContinuesAfterFailedTransaction() {
        var outboxDeliveryService = mock(OutboxDeliveryService.class);
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        when(outboxRepository.findDue(eq(Instant.EPOCH), any())).thenReturn(List.of("bad", "good"));
        doThrow(new IllegalStateException("lock timeout")).when(outboxDeliveryService).deliver("bad");
        new OutboxScheduler(outboxRepository, outboxDeliveryService, clock).publishDue();
        verify(outboxDeliveryService).deliver("good");
    }

    @Test
    void outcomeSchedulerContinuesAfterFailedBatch() {
        var outcomeService = mock(OutcomeService.class);
        when(betRepository.findRecordedEventsWithBets(BetStatus.OPEN, PageRequest.of(0, 50)))
                .thenReturn(List.of("bad", "good"));
        doThrow(new IllegalStateException("lock timeout")).when(outcomeService).matchBatch("bad");
        new OutcomeScheduler(betRepository, outcomeService).matchRemaining();
        verify(outcomeService).matchBatch("good");
    }
}

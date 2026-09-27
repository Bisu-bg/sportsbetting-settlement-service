package com.sportsbetting.settlement.domain;

import com.sportsbetting.settlement.support.BetFixtures;
import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.domain.bet.api.PlaceBet;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementMessage;
import com.sportsbetting.settlement.domain.bet.repository.BetRepository;
import com.sportsbetting.settlement.domain.outcome.repository.OutcomeRepository;
import com.sportsbetting.settlement.domain.settlement.repository.OutboxRepository;
import com.sportsbetting.settlement.domain.bet.service.BetService;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeService;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeScheduler;
import com.sportsbetting.settlement.domain.settlement.service.OutboxDeliveryService;
import com.sportsbetting.settlement.domain.settlement.service.SettlementService;
import java.util.*;
import java.util.concurrent.*;
import java.time.Clock;
import com.sportsbetting.settlement.domain.settlement.messaging.SettlementPublisher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"app.messaging-enabled=false", "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
class TestPersistenceIntegration {
    @Autowired BetService betService;
    @Autowired OutcomeService outcomeService;
    @Autowired SettlementService settlementService;
    @Autowired BetRepository betRepository;
    @Autowired OutcomeRepository outcomeRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clean() {
        outboxRepository.deleteAll();
        betRepository.deleteAll();
        outcomeRepository.deleteAll();
    }

    @Test
    void jpaRejectsInvalidBetEvenWhenApiValidationIsBypassed() {
        var invalid = new Bet(new PlaceBet("invalid", " ", "event", "market", "winner",
                new java.math.BigDecimal("10.00")));
        assertThatThrownBy(() -> betRepository.saveAndFlush(invalid))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        assertThat(betRepository.count()).isZero();
    }

    @Test
    void concurrentReplaysProduceOneInstructionAndOneReward() throws Exception {
        betService.place(BetFixtures.request("winner", "a"));
        betService.place(BetFixtures.request("loser", "b"));
        var outcome = new EventOutcome("event-1", "Final", "a");
        concurrently(8, () -> outcomeService.handle(outcome));
        assertThat(outboxRepository.count()).isEqualTo(2);
        assertThat(outcomeRepository.count()).isEqualTo(1);
        concurrently(12, () -> {
            settlementService.settle(new SettlementMessage("winner", "event-1", "a"));
            settlementService.settle(new SettlementMessage("loser", "event-1", "a"));
        });
        assertThat(betService.get("winner").getPayout()).isEqualByComparingTo("20");
        assertThat(betService.get("winner").getStatus()).isEqualTo(BetStatus.WON);
        assertThat(betService.get("loser").getStatus()).isEqualTo(BetStatus.LOST);
        assertThat(betService.get("winner").getVersion()).isEqualTo(2);
        assertThatThrownBy(() -> betService.place(BetFixtures.request("late", "a"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outcomeService.handle(new EventOutcome("event-1", "Final", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(betService.get("winner").getPayout()).isEqualByComparingTo("20");
    }

    @Test
    void outcomeAndOutboxRollBackTogether() {
        betService.place(BetFixtures.request("bet", "a"));
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outcomeService.handle(new EventOutcome("event-1", "Final", "a"));
            throw new IllegalStateException("simulate transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outcomeRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(betService.get("bet").getStatus()).isEqualTo(BetStatus.OPEN);
    }

    @Test
    void sendAcknowledgedBeforeRollbackCanBeRedeliveredWithoutSecondReward() {
        betService.place(BetFixtures.request("bet", "a"));
        outcomeService.handle(new EventOutcome("event-1", "Final", "a"));
        List<SettlementMessage> delivered = new ArrayList<>();
        SettlementPublisher publisher = delivered::add;
        var outboxDeliveryService = new OutboxDeliveryService(outboxRepository, publisher, Clock.systemUTC());
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outboxDeliveryService.deliver("bet");
            throw new IllegalStateException("crash after broker acknowledgement, before database commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outboxRepository.findById("bet").orElseThrow().isSent()).isFalse();
        transaction.executeWithoutResult(status -> outboxDeliveryService.deliver("bet"));
        assertThat(delivered).hasSize(2);
        delivered.forEach(settlementService::settle);
        assertThat(betService.get("bet").getPayout()).isEqualByComparingTo("20");
        assertThat(betService.get("bet").getVersion()).isEqualTo(2);
    }

    @Test
    void racingAdmissionAndOutcomeNeverLeaveAnAcceptedBetOpen() throws Exception {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        concurrently(12, () -> {
            int index = sequence.getAndIncrement();
            if (index == 0) {
                outcomeService.handle(new EventOutcome("event-1", "Final", "a"));
            } else {
                try {
                    betService.place(BetFixtures.request("race-" + index, "a"));
                } catch (IllegalStateException closed) {
                    assertThat(closed).hasMessageContaining("recorded outcome");
                }
            }
        });
        assertThat(outcomeRepository.count()).isEqualTo(1);
        assertThat(betRepository.findAll()).allSatisfy(bet -> assertThat(bet.getStatus()).isEqualTo(BetStatus.PENDING));
        assertThat(outboxRepository.count()).isEqualTo(betRepository.count());
    }

    @Test
    void concurrentBetCreationCannotOverwriteExistingBet() throws Exception {
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        concurrently(8, () -> {
            try {
                betService.place(BetFixtures.request("bet", "a"));
                successes.incrementAndGet();
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessage("Bet ID already exists");
            }
        });
        assertThat(successes.get()).isEqualTo(1);
        assertThat(betRepository.count()).isEqualTo(1);
    }

    @Test
    void largeEventResumesAcrossBoundedTransactions() {
        for (int index = 0; index < 125; index++) {
            betService.place(BetFixtures.request("bulk-" + index, "a"));
        }
        outcomeService.handle(new EventOutcome("event-1", "Final", "a"));
        assertThat(outboxRepository.count()).isEqualTo(50);
        var scheduler = new OutcomeScheduler(betRepository, outcomeService);
        scheduler.matchRemaining();
        assertThat(outboxRepository.count()).isEqualTo(100);
        scheduler.matchRemaining();
        assertThat(outboxRepository.count()).isEqualTo(125);
        scheduler.matchRemaining();
        assertThat(betRepository.findByEventIdAndStatus("event-1", BetStatus.OPEN)).isEmpty();
        assertThat(outboxRepository.count()).isEqualTo(125);
    }

    @Test
    void sameBetIdCannotBeInsertedFromTwoDifferentEvents() throws Exception {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        concurrently(2, () -> {
            int index = sequence.getAndIncrement();
            var request = new PlaceBet("shared-id", "user", "separate-" + index,
                    "match-winner", "a", new java.math.BigDecimal("10.00"));
            try {
                betService.place(request);
                successes.incrementAndGet();
            } catch (IllegalStateException duplicate) {
                assertThat(duplicate).hasMessage("Bet ID already exists");
            }
        });
        assertThat(successes.get()).isEqualTo(1);
        assertThat(betRepository.count()).isEqualTo(1);
    }

    private void concurrently(int count, Runnable action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var ready = new CountDownLatch(count);
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                action.run();
                return null;
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }
}

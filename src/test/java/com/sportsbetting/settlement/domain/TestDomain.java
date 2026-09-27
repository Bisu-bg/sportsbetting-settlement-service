package com.sportsbetting.settlement.domain;

import com.sportsbetting.settlement.support.BetFixtures;
import com.sportsbetting.settlement.domain.bet.api.BetMapper;
import com.sportsbetting.settlement.domain.bet.api.BetView;
import com.sportsbetting.settlement.domain.bet.api.PlaceBet;
import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import com.sportsbetting.settlement.domain.outcome.model.RecordedOutcome;
import com.sportsbetting.settlement.domain.settlement.model.SettlementOutbox;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.assertj.core.api.Assertions.*;

class TestDomain {
    @Test
    void winningAndLosingBetsHaveOneTerminalPayout() {
        Bet won = new Bet(BetFixtures.request("win", "a"));
        assertThatThrownBy(() -> won.settle("a")).isInstanceOf(IllegalStateException.class);
        won.awaitSettlement();
        assertThatThrownBy(won::awaitSettlement).isInstanceOf(IllegalStateException.class);
        won.settle("a");
        assertThat(won.getStatus()).isEqualTo(BetStatus.WON);
        assertThat(won.getPayout()).isEqualByComparingTo("20.00");
        assertThatThrownBy(() -> won.settle("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(won::awaitSettlement).isInstanceOf(IllegalStateException.class);
        Bet lost = new Bet(BetFixtures.request("lose", "b"));
        lost.awaitSettlement();
        lost.settle("a");
        assertThat(lost.getStatus()).isEqualTo(BetStatus.LOST);
        assertThat(lost.getPayout()).isEqualByComparingTo("0");
    }

    @Test
    void mapperPreservesFieldsAndHandlesNull() {
        var mapper = Mappers.getMapper(BetMapper.class);
        assertThat(mapper.toView(null)).isNull();
        assertThat(mapper.toView(new Bet(BetFixtures.request("bet", "a"))))
                .isEqualTo(new BetView("bet", "user-1", "event-1", "match-winner", "a",
                        new BigDecimal("10.00"), BetStatus.OPEN, BigDecimal.ZERO));
    }

    @Test
    void outboxUsesBoundedExponentialBackoffAndStableIdentity() {
        var entry = new SettlementOutbox("bet", "event", "winner");
        assertThat(entry.message().betId()).isEqualTo("bet");
        assertThat(entry.message().eventId()).isEqualTo("event");
        assertThat(entry.message().eventWinnerId()).isEqualTo("winner");
        entry.retryLater(Instant.EPOCH);
        assertThat(entry.getNextAttempt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
        for (int i = 0; i < 40; i++) entry.retryLater(Instant.EPOCH);
        assertThat(entry.getNextAttempt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
        assertThat(entry.getAttempts()).isEqualTo(30);
        entry.markSent();
        assertThat(entry.isSent()).isTrue();
    }

    @Test
    void outcomePreservesMetadata() {
        var saved = new RecordedOutcome(new EventOutcome("event", "Final", "winner"));
        assertThat(saved.getEventId()).isEqualTo("event");
        assertThat(saved.getEventName()).isEqualTo("Final");
        assertThat(saved.getEventWinnerId()).isEqualTo("winner");
    }

    @Test
    void persistedEntitiesEnforceRequiredFieldsAndAmountRules() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var invalidBet = new Bet(new PlaceBet(" ", " ", "x".repeat(101), " ", " ",
                    new BigDecimal("0.001")));
            assertThat(validator.validate(invalidBet))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("betId", "userId", "eventId", "eventMarketId", "eventWinnerId", "betAmount");
            assertThat(validator.validate(new Bet(new PlaceBet("bet", "user", "event", "market", "winner",
                    new BigDecimal("-1.00")))))
                    .extracting(violation -> violation.getPropertyPath().toString()).contains("betAmount");
            assertThat(validator.validate(new RecordedOutcome(new EventOutcome(" ", " ", " "))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("eventId", "eventName", "eventWinnerId");
            assertThat(validator.validate(new SettlementOutbox(" ", " ", " ")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("betId", "eventId", "eventWinnerId");
        }
    }
}

package com.sportsbetting.settlement.domain.bet.model;

import com.sportsbetting.settlement.domain.bet.api.PlaceBet;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "bet", indexes = @Index(name = "bet_event_status_idx", columnList = "eventId,status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bet {
    @Id @NotBlank @Size(max = 100) @Column(length = 100) private String betId;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String userId;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String eventId;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String eventMarketId;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String eventWinnerId;
    @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal betAmount;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false) private BetStatus status = BetStatus.OPEN;
    @NotNull @DecimalMin("0.00") @Digits(integer = 14, fraction = 2)
    @Column(nullable = false, precision = 16, scale = 2) private BigDecimal payout = BigDecimal.ZERO;
    @Version private Long version;

    public Bet(PlaceBet request) {
        betId = request.betId();
        userId = request.userId();
        eventId = request.eventId();
        eventMarketId = request.eventMarketId();
        eventWinnerId = request.eventWinnerId();
        betAmount = request.betAmount();
    }

    public void awaitSettlement() {
        if (status != BetStatus.OPEN) {
            throw new IllegalStateException("Only open bets can await settlement");
        }
        status = BetStatus.PENDING;
    }

    public void settle(String winnerId) {
        if (status != BetStatus.PENDING) {
            throw new IllegalStateException("Only pending bets can be settled");
        }
        boolean won = eventWinnerId.equals(winnerId);
        status = won ? BetStatus.WON : BetStatus.LOST;
        payout = won ? betAmount.multiply(BigDecimal.valueOf(2)) : BigDecimal.ZERO;
    }
}

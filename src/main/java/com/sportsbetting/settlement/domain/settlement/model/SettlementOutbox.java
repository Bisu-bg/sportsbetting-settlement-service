package com.sportsbetting.settlement.domain.settlement.model;

import com.sportsbetting.settlement.domain.settlement.messaging.SettlementMessage;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(indexes = @Index(name = "outbox_due_idx", columnList = "sent,nextAttempt"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementOutbox {
    @Id @NotBlank @Size(max = 100) @Column(length = 100) private String betId;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String eventId;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String eventWinnerId;
    @Column(nullable = false) private boolean sent;
    @Min(0) @Max(30) @Column(nullable = false) private int attempts;
    @NotNull @Column(nullable = false) private Instant nextAttempt = Instant.EPOCH;

    public SettlementOutbox(String betId, String eventId, String winnerId) {
        this.betId = betId;
        this.eventId = eventId;
        this.eventWinnerId = winnerId;
    }

    public SettlementMessage message() {
        return new SettlementMessage(betId, eventId, eventWinnerId);
    }

    public void markSent() { sent = true; }

    public void retryLater(Instant now) {
        attempts = Math.min(attempts + 1, 30);
        nextAttempt = now.plusSeconds(Math.min(1L << attempts, 60));
    }
}

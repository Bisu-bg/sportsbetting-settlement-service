package com.sportsbetting.settlement.domain.outcome.model;

import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecordedOutcome {
    @Id @NotBlank @Size(max = 100) @Column(length = 100) private String eventId;
    @NotBlank @Size(max = 200) @Column(nullable = false, length = 200) private String eventName;
    @NotBlank @Size(max = 100) @Column(nullable = false, length = 100) private String eventWinnerId;

    public RecordedOutcome(EventOutcome outcome) {
        eventId = outcome.eventId();
        eventName = outcome.eventName();
        eventWinnerId = outcome.eventWinnerId();
    }
}

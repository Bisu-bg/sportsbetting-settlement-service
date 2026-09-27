package com.sportsbetting.settlement.domain.outcome.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EventOutcome(
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 200) String eventName,
        @NotBlank @Size(max = 100) String eventWinnerId) {}

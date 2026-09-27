package com.sportsbetting.settlement.domain.settlement.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SettlementMessage(
        @NotBlank @Size(max = 100) String betId,
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String eventWinnerId) {}

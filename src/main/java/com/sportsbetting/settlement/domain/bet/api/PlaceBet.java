package com.sportsbetting.settlement.domain.bet.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PlaceBet(
        @NotBlank @Size(max = 100) String betId,
        @NotBlank @Size(max = 100) String userId,
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String eventMarketId,
        @NotBlank @Size(max = 100) String eventWinnerId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal betAmount) {}

package com.sportsbetting.settlement.domain.bet.api;

import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import java.math.BigDecimal;

public record BetView(
        String betId,
        String userId,
        String eventId,
        String eventMarketId,
        String eventWinnerId,
        BigDecimal betAmount,
        BetStatus status,
        BigDecimal payout) {}

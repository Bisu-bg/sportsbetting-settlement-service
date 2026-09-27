package com.sportsbetting.settlement.support;

import com.sportsbetting.settlement.domain.bet.api.PlaceBet;
import java.math.BigDecimal;

public final class BetFixtures {
    private BetFixtures() {
    }

    public static PlaceBet request(String id, String winner) {
        return new PlaceBet(id, "user-1", "event-1", "match-winner", winner, new BigDecimal("10.00"));
    }
}

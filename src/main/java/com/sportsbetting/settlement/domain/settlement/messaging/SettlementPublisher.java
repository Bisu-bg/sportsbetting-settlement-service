package com.sportsbetting.settlement.domain.settlement.messaging;

public interface SettlementPublisher {
    void send(SettlementMessage message);
}

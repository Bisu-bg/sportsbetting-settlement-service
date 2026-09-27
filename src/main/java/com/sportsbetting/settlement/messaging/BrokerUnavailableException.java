package com.sportsbetting.settlement.messaging;

public class BrokerUnavailableException extends RuntimeException {
    public BrokerUnavailableException(Throwable cause) {
        super("Broker acknowledgement unavailable; retry the same event outcome", cause);
    }
}

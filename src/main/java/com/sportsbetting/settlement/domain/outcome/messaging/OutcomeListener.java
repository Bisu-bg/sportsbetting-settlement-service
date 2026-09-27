package com.sportsbetting.settlement.domain.outcome.messaging;

import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.domain.outcome.service.OutcomeService;
import com.sportsbetting.settlement.messaging.JsonMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.messaging-enabled", havingValue = "true", matchIfMissing = true)
public class OutcomeListener {
    private final JsonMessages jsonMessages;
    private final OutcomeService outcomeService;

    @KafkaListener(topics = "event-outcomes")
    public void consume(String payload) {
        outcomeService.handle(jsonMessages.read(payload, EventOutcome.class));
    }
}

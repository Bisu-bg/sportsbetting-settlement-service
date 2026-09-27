package com.sportsbetting.settlement.domain.outcome.messaging;

import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.messaging.BrokerUnavailableException;
import com.sportsbetting.settlement.messaging.JsonMessages;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutcomePublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMessages jsonMessages;

    public void publish(EventOutcome outcome) {
        try {
            kafkaTemplate.send("event-outcomes", outcome.eventId(), jsonMessages.write(outcome)).get(10, TimeUnit.SECONDS);
            log.info("Outcome published eventId={}", outcome.eventId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException(ex);
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            throw new BrokerUnavailableException(ex);
        }
    }
}

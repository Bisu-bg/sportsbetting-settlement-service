package com.sportsbetting.settlement.domain.outcome.api;

import com.sportsbetting.settlement.domain.outcome.messaging.OutcomePublisher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/event-outcomes")
@RequiredArgsConstructor
public class OutcomeController {
    private final OutcomePublisher outcomePublisher;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void publish(@Valid @RequestBody EventOutcome outcome) {
        outcomePublisher.publish(outcome);
    }
}

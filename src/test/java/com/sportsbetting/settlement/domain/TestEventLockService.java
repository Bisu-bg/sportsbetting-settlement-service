package com.sportsbetting.settlement.domain;

import com.sportsbetting.settlement.domain.outcome.service.EventLockService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.*;

class TestEventLockService {
    @Test
    void coordinatesOnlyTheSpecifiedEvent() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        new EventLockService(jdbcTemplate).acquire("event-1");
        verify(jdbcTemplate).update("MERGE INTO event_lock (event_id) KEY (event_id) VALUES (?)", "event-1");
    }
}

package com.sportsbetting.settlement.domain.outcome.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventLockService {
    private final JdbcTemplate jdbcTemplate;

    /** H2 MERGE updates an existing row under a write lock or creates it atomically. */
    public void acquire(String eventId) {
        jdbcTemplate.update("MERGE INTO event_lock (event_id) KEY (event_id) VALUES (?)", eventId);
    }
}

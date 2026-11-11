package com.mobility.core.shared;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes driver schedule changes and trip starts across application instances. */
@Component
public class DriverScheduleLock {
    private final JdbcTemplate jdbc;

    public DriverScheduleLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID driverId) {
        // Preserve the namespace already used by shift creation and cancellation.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class, "dispatch.shift:" + driverId);
    }
}

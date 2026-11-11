package com.mobility.core.dispatch.automatic;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import com.mobility.core.booking.Trips;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentJobs {
    private final JdbcTemplate jdbc;
    private final Trips trips;
    private final Clock clock;

    AssignmentJobs(JdbcTemplate jdbc, Trips trips, Clock clock) {
        this.jdbc = jdbc;
        this.trips = trips;
        this.clock = clock;
    }

    @Transactional
    public void enqueue(UUID eventId, UUID rideId, List<UUID> tripIds) {
        // Validate against booking's public API, never query another module's tables.
        for (UUID id : tripIds.stream().distinct().sorted().toList()) {
            var trip = trips.find(id).orElseThrow(() ->
                    new AmqpRejectAndDontRequeueException("Unknown booking trip"));
            if (!rideId.equals(trip.rideId())) {
                throw new AmqpRejectAndDontRequeueException("Trip does not belong to ride");
            }
            jdbc.update("""
                    INSERT INTO dispatch.assignment_jobs(trip_id, event_id, next_attempt_at)
                    VALUES (?, ?, ?) ON CONFLICT (trip_id) DO NOTHING
                    """, id, eventId, Timestamp.from(clock.instant()));
        }
    }
}

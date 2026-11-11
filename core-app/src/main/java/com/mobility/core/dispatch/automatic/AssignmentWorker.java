package com.mobility.core.dispatch.automatic;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import com.mobility.core.dispatch.suggest.AutomaticAssignmentService;
import com.mobility.core.dispatch.suggest.AutomaticAssignmentService.AssignmentOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AssignmentWorker {
    private static final Logger log = LoggerFactory.getLogger(AssignmentWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AutomaticAssignmentService assignment;
    private final AutomaticAssignmentProperties properties;
    private final Clock clock;

    AssignmentWorker(JdbcTemplate jdbc, TransactionTemplate transactions, AutomaticAssignmentService assignment,
            AutomaticAssignmentProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.assignment = assignment;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dispatch.automatic.poll-interval:5s}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        for (int i = 0; i < properties.batchSize() && processNext(); i++) { /* bounded batch */ }
    }

    /** Public for deterministic integration tests; production polling is gated above. */
    public boolean processNext() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var jobs = jdbc.query("""
                    SELECT trip_id, attempts FROM dispatch.assignment_jobs
                    WHERE status = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at, trip_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new Job(rs.getObject(1, UUID.class), rs.getInt(2)), Timestamp.from(clock.instant()));
            if (jobs.isEmpty()) {
                return false;
            }
            var job = jobs.getFirst();
            AssignmentOutcome outcome;
            try {
                outcome = assignment.attempt(job.tripId(), job.attempts() + 1);
            } catch (RuntimeException failure) {
                log.warn("Automatic assignment failed for trip {} ({})", job.tripId(), failure.getClass().getSimpleName());
                // Store a stable diagnostic, never exception text containing private request data.
                if (job.attempts() + 1 >= properties.maxAttempts()) {
                    assignment.failedPermanently(job.tripId());
                }
                outcome = new AssignmentOutcome(job.attempts() + 1 >= properties.maxAttempts() ? "MANUAL" : "PENDING",
                        "ASSIGNMENT_FAILED", clock.instant().plus(properties.retryDelay()));
            }
            jdbc.update("""
                    UPDATE dispatch.assignment_jobs SET status = ?, attempts = attempts + ?,
                    last_reason = ?, next_attempt_at = ?, updated_at = ? WHERE trip_id = ?
                    """, outcome.status(), "OUTSIDE_HORIZON".equals(outcome.reason()) ? 0 : 1,
                    outcome.reason(), Timestamp.from(outcome.nextAttempt() == null ? clock.instant() : outcome.nextAttempt()),
                    Timestamp.from(clock.instant()), job.tripId());
            return true;
        }));
    }

    private record Job(UUID tripId, int attempts) {}
}

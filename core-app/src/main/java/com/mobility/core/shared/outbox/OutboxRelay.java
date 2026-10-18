package com.mobility.core.shared.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers outbox events to RabbitMQ in order and marks them published once RabbitMQ confirms them. Rows are
 * claimed with {@code FOR UPDATE SKIP LOCKED}, so several instances can run it at once. A failed event is retried
 * later with growing waits, without holding up the rest of the batch.
 */
@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final JdbcTemplate jdbc;

	private final RabbitTemplate rabbit;

	private final TransactionTemplate transactions;

	private final OutboxProperties properties;

	private final JsonMapper json;

	private final Clock clock;

	OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate transactions, OutboxProperties properties,
			JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.rabbit = rabbit;
		this.transactions = transactions;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	record Pending(UUID id, String type, String routingKey, String aggregateType, UUID aggregateId, String payload,
			Instant occurredAt, int attempts) {
	}

	/** One batch; returns how many events were published. Tests call it directly (scheduling is off there). */
	@Scheduled(fixedDelayString = "${outbox.relay-interval:1s}")
	public int relayBatch() {
		Integer published = transactions.execute(status -> {
			Instant now = clock.instant();
			List<Pending> batch = jdbc.query("""
					SELECT id, event_type, routing_key, aggregate_type, aggregate_id, payload::text AS payload, occurred_at, attempts
					FROM outbox.outbox_events
					WHERE published_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
					ORDER BY seq
					LIMIT ?
					FOR UPDATE SKIP LOCKED""",
					(rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("routing_key"),
							rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), rs.getString("payload"),
							rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts")),
					Timestamp.from(now), properties.batchSize());
			int ok = 0;
			for (Pending event : batch) {
				try {
					send(event);
					jdbc.update("UPDATE outbox.outbox_events SET published_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ?",
							Timestamp.from(clock.instant()), event.id());
					ok++;
				}
				catch (Exception e) {
					Duration wait = backoff(event.attempts() + 1);
					String error = e.getClass().getSimpleName() + ": " + e.getMessage();
					log.warn("Outbox event {} ({} {}) not published, retry in {}: {}", event.id(), event.type(),
							event.routingKey(), wait, error);
					jdbc.update("""
							UPDATE outbox.outbox_events SET attempts = attempts + 1, next_attempt_at = ?, last_error = ?
							WHERE id = ?""", Timestamp.from(clock.instant().plus(wait)),
							error.length() > 500 ? error.substring(0, 500) : error, event.id());
				}
			}
			return ok;
		});
		return published == null ? 0 : published;
	}

	/** Daily: published events older than the retention are removed. */
	@Scheduled(cron = "${outbox.cleanup-cron:0 30 3 * * *}", zone = "Asia/Phnom_Penh")
	public int deletePublished() {
		return jdbc.update("DELETE FROM outbox.outbox_events WHERE published_at < ?",
				Timestamp.from(clock.instant().minus(properties.retention())));
	}

	/** 1 s, 2 s, 4 s … up to the configured maximum. */
	Duration backoff(int attempts) {
		long seconds = 1L << Math.min(attempts - 1, 20);
		Duration wait = Duration.ofSeconds(seconds);
		return wait.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : wait;
	}

	private void send(Pending event) throws Exception {
		byte[] body = json.writeValueAsBytes(new Envelope(event.id(), event.type(), event.aggregateType(), event.aggregateId(),
				event.occurredAt(), json.readTree(event.payload())));
		Message message = MessageBuilder.withBody(body)
			.setContentType("application/json")
			.setContentEncoding(StandardCharsets.UTF_8.name())
			.setMessageId(event.id().toString())
			.setType(event.type())
			.setTimestamp(java.util.Date.from(event.occurredAt()))
			.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
			.build();
		CorrelationData correlation = new CorrelationData(event.id().toString());
		rabbit.send(properties.exchange(), event.routingKey(), message, correlation);
		CorrelationData.Confirm confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
		if (!confirm.ack()) {
			throw new IllegalStateException("RabbitMQ refused the message: " + confirm.reason());
		}
	}

	/** What consumers receive. */
	record Envelope(UUID eventId, String type, String aggregateType, UUID aggregateId, Instant occurredAt, Object payload) {
	}
}

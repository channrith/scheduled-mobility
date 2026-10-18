package com.mobility.core.shared.outbox;

import java.time.Clock;
import java.util.UUID;

import com.mobility.core.shared.time.StoredTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a domain event in the outbox, in the caller's transaction: the event exists if and only if the change
 * that caused it commits. The relay delivers it to RabbitMQ later. The event is also published in-process
 * (Spring application event) for listeners in this application.
 */
@Component
public class Outbox {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	Outbox(JdbcTemplate jdbc, JsonMapper json, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * @param routingKey e.g. {@code booking.trip-completed}
	 * @param aggregateType e.g. {@code trip}
	 * @param event a record; serialized as JSON (no personal data in events)
	 * @return the event id (RabbitMQ {@code message-id}, for consumers to drop duplicates)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID publish(String routingKey, String aggregateType, UUID aggregateId, Object event) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO outbox.outbox_events (id, event_type, routing_key, aggregate_type, aggregate_id, payload, occurred_at)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)""",
				id, event.getClass().getSimpleName(), routingKey, aggregateType, aggregateId, json.writeValueAsString(event),
				java.sql.Timestamp.from(StoredTime.of(clock.instant())));
		events.publishEvent(event);
		return id;
	}
}

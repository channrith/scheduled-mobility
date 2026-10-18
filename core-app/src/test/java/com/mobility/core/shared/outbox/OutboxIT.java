package com.mobility.core.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class OutboxIT {

	record SomethingHappened(UUID thingId, String what) {
	}

	@Autowired
	Outbox outbox;

	@Autowired
	OutboxRelay relay;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	RabbitAdmin admin;

	@Autowired
	TopicExchange eventsExchange;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	MutableClock clock;

	Queue queue;

	String routingKey;

	@BeforeEach
	void bindQueue() {
		routingKey = "test.outbox-" + UUID.randomUUID();
		// Not auto-delete: receive() would remove an auto-delete queue after the first message.
		Queue q = new Queue("test.outbox." + UUID.randomUUID(), false, false, false);
		admin.declareQueue(q);
		admin.declareBinding(BindingBuilder.bind(q).to(eventsExchange).with(routingKey));
		queue = q;
		// Other tests leave events behind; deliver them first so each test sees only its own.
		while (relay.relayBatch() > 0) {
		}
	}

	@AfterEach
	void reset() {
		clock.reset();
		admin.declareExchange(eventsExchange);
		admin.deleteQueue(queue.getName());
	}

	UUID publish(UUID aggregateId, String what) {
		return transactions.execute(s -> outbox.publish(routingKey, "thing", aggregateId, new SomethingHappened(aggregateId, what)));
	}

	@Test
	void deliversInOrderWithIdsAndMarksPublished() {
		UUID thing = UUID.randomUUID();
		UUID first = publish(thing, "first");
		UUID second = publish(thing, "second");

		assertThat(relay.relayBatch()).isGreaterThanOrEqualTo(2);

		Message m1 = rabbit.receive(queue.getName(), 5_000);
		Message m2 = rabbit.receive(queue.getName(), 5_000);
		assertThat(m1.getMessageProperties().getMessageId()).isEqualTo(first.toString());
		assertThat(m1.getMessageProperties().getType()).isEqualTo("SomethingHappened");
		JsonNode body = json.readTree(new String(m1.getBody(), StandardCharsets.UTF_8));
		assertThat(body.get("payload").get("what").asString()).isEqualTo("first");
		assertThat(body.get("aggregateId").asString()).isEqualTo(thing.toString());
		assertThat(m2.getMessageProperties().getMessageId()).isEqualTo(second.toString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox.outbox_events WHERE aggregate_id = ? AND published_at IS NOT NULL",
				Integer.class, thing)).isEqualTo(2);
		assertThat(relay.relayBatch()).isZero();
	}

	@Test
	void anEventIsOnlyRecordedWithItsTransaction() {
		UUID thing = UUID.randomUUID();
		assertThatThrownBy(() -> outbox.publish(routingKey, "thing", thing, new SomethingHappened(thing, "x")))
			.isInstanceOf(IllegalTransactionStateException.class);

		transactions.executeWithoutResult(s -> {
			outbox.publish(routingKey, "thing", thing, new SomethingHappened(thing, "rolled back"));
			s.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox.outbox_events WHERE aggregate_id = ?", Integer.class, thing))
			.isZero();
	}

	@Test
	void aFailedPublishIsRetriedLater() {
		UUID thing = UUID.randomUUID();
		UUID id = publish(thing, "retry me");
		admin.deleteExchange(eventsExchange.getName());

		relay.relayBatch();

		assertThat(jdbc.queryForMap("SELECT published_at, attempts, last_error FROM outbox.outbox_events WHERE id = ?", id))
			.containsEntry("published_at", null).containsEntry("attempts", 1);
		admin.declareExchange(eventsExchange);
		admin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with(routingKey));
		relay.relayBatch();
		assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox.outbox_events WHERE id = ?", Boolean.class, id))
			.as("still waiting for its retry time").isTrue();

		clock.advance(Duration.ofSeconds(2));
		relay.relayBatch();
		assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox.outbox_events WHERE id = ?", Boolean.class, id))
			.isTrue();
		assertThat(rabbit.receive(queue.getName(), 5_000).getMessageProperties().getMessageId()).isEqualTo(id.toString());
	}

	@Test
	void publishedEventsAreDeletedAfterTheRetention() {
		UUID thing = UUID.randomUUID();
		UUID id = publish(thing, "old");
		relay.relayBatch();

		clock.advance(Duration.ofDays(8));
		relay.deletePublished();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox.outbox_events WHERE id = ?", Integer.class, id)).isZero();
	}
}

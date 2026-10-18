package com.mobility.core.booking;

import static com.mobility.core.booking.BookingApi.cashRide;
import static com.mobility.core.booking.BookingApi.firstTrip;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.booking.BookingApi.Passenger;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.outbox.OutboxRelay;
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
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class BookingFlowIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	OutboxRelay relay;

	@Autowired
	RabbitAdmin admin;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	TopicExchange eventsExchange;

	@Autowired
	JsonMapper json;

	static BookingApi api;

	DriverApi drivers;

	Passenger passenger;

	@BeforeEach
	void setUp() {
		if (api == null) {
			api = new BookingApi(client, issuer);
		}
		drivers = new DriverApi(client, issuer);
		passenger = api.passenger();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	Instant inTwoHours() {
		return Instant.now().plus(Duration.ofHours(2));
	}

	JsonNode booked() {
		return api.bookOk(passenger.token(), cashRide(api.quote(passenger.token(), inTwoHours(), null, false)));
	}

	@Test
	void fullHappyPathWithHistoryOutboxAndRabbitMq() {
		Queue queue = new Queue("test.booking." + UUID.randomUUID(), false, false, false);
		admin.declareQueue(queue);
		admin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("booking.#"));
		Registered driver = drivers.approvedDriver();

		JsonNode ride = booked();
		UUID rideId = UUID.fromString(ride.get("id").asString());
		UUID tripId = firstTrip(ride);
		assertThat(ride.get("trips").get(0).get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(ride.get("pickupNotes").asString()).isEqualTo("Blue gate behind the pharmacy");
		long fare = ride.get("fareMinor").asLong();

		api.json(api.assign(tripId, driver.driverId(), 0));
		for (String step : List.of("en-route", "arrived", "start", "complete")) {
			api.json(api.driver(driver.token(), tripId, step));
		}
		JsonNode cash = api.json(api.post("/api/v1/driver/trips/" + tripId + "/cash", driver.token(),
				Map.of("amountMinor", fare, "currency", "USD"), null));
		assertThat(cash.get("cash").get("mismatch").asBoolean()).isFalse();

		JsonNode done = api.json(api.get("/api/v1/rides/" + rideId, passenger.token())).get("trips").get(0);
		assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(done.get("history").valueStream().map(h -> h.get("to").asString())).containsExactly("SCHEDULED", "ASSIGNED",
				"DRIVER_EN_ROUTE", "DRIVER_ARRIVED", "IN_PROGRESS", "COMPLETED");
		assertThat(done.get("history").get(1).get("actorRole").asString()).isEqualTo("OPS");
		assertThat(done.get("history").get(2).get("actorRole").asString()).isEqualTo("DRIVER");
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class, tripId.toString()))
			.containsExactly("booking.trip.assigned");

		List<String> types = jdbc.queryForList("""
				SELECT event_type FROM outbox.outbox_events WHERE aggregate_id IN (?, ?) ORDER BY seq""", String.class, rideId, tripId);
		assertThat(types).containsExactly("RideBooked", "TripStatusChanged", "TripAssigned", "TripStatusChanged",
				"TripStatusChanged", "TripStatusChanged", "TripStatusChanged", "TripCompleted", "TripCashReported");

		while (relay.relayBatch() > 0) {
		}
		List<String> delivered = new ArrayList<>();
		for (Message m = rabbit.receive(queue.getName(), 5_000); m != null; m = rabbit.receive(queue.getName(), 1_000)) {
			JsonNode envelope = json.readTree(new String(m.getBody(), StandardCharsets.UTF_8));
			String aggregate = envelope.get("aggregateId").asString();
			if (aggregate.equals(rideId.toString()) || aggregate.equals(tripId.toString())) {
				delivered.add(m.getMessageProperties().getType());
			}
		}
		assertThat(delivered).containsExactlyElementsOf(types);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM outbox.outbox_events WHERE aggregate_id IN (?, ?) AND published_at IS NULL""",
				Integer.class, rideId, tripId)).isZero();
		admin.deleteQueue(queue.getName());
	}

	@Test
	void invalidTransitionsAreRefused() {
		Registered driver = drivers.approvedDriver();
		UUID tripId = firstTrip(booked());

		api.driver(driver.token(), tripId, "en-route").expectStatus().isNotFound();
		api.json(api.assign(tripId, driver.driverId(), 0));
		api.driver(driver.token(), tripId, "start").expectStatus().isEqualTo(409).expectBody()
			.jsonPath("$.code").isEqualTo("booking.invalid-transition")
			.jsonPath("$.from").isEqualTo("ASSIGNED")
			.jsonPath("$.to").isEqualTo("IN_PROGRESS")
			.jsonPath("$.detail").isEqualTo("A trip cannot go from ASSIGNED to IN_PROGRESS.");
		api.driver(driver.token(), tripId, "complete").expectStatus().isEqualTo(409);
		api.json(api.driver(driver.token(), tripId, "en-route"));
		api.json(api.driver(driver.token(), tripId, "arrived"));
		api.driver(driver.token(), tripId, "no-show").expectStatus().isEqualTo(409).expectBody()
			.jsonPath("$.code").isEqualTo("booking.no-show-too-early");
		api.post("/api/v1/ops/trips/" + tripId + "/dispute", api.tokenFor(Role.DISPATCHER),
				Map.of("reason", "Rider says the driver was rude"), null)
			.expectStatus().isEqualTo(409);
		api.post("/api/v1/driver/trips/" + tripId + "/cash", driver.token(), Map.of("amountMinor", 100, "currency", "USD"), null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("booking.not-completed");

		// After the wait the no-show is accepted, and nothing can follow it.
		clock.advance(Duration.ofHours(3));
		Registered sameDriver = new Registered(driver.driverId(), driver.userId(), driver.phone(),
				com.mobility.core.identity.token.SignedTokens.forUser(issuer, driver.userId(), Role.DRIVER));
		api.json(api.driver(sameDriver.token(), tripId, "no-show"));
		api.driver(sameDriver.token(), tripId, "start").expectStatus().isEqualTo(409);
		assertThat(jdbc.queryForObject("SELECT event_type FROM outbox.outbox_events WHERE aggregate_id = ? ORDER BY seq DESC LIMIT 1",
				String.class, tripId)).isEqualTo("TripCancelled");
	}

	@Test
	void twoDispatchersAssigningTheSameTripAtOnce() throws Exception {
		Registered first = drivers.approvedDriver();
		Registered second = drivers.approvedDriver();
		UUID tripId = firstTrip(booked());
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> a = pool.submit(() -> {
				go.await();
				return api.assign(tripId, first.driverId(), 0).returnResult(String.class).getStatus().value();
			});
			Future<Integer> b = pool.submit(() -> {
				go.await();
				return api.assign(tripId, second.driverId(), 0).returnResult(String.class).getStatus().value();
			});
			go.countDown();
			assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdown();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking.trip_events WHERE trip_id = ? AND to_status = 'ASSIGNED'",
				Integer.class, tripId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox.outbox_events WHERE aggregate_id = ? AND event_type = 'TripAssigned'",
				Integer.class, tripId)).isEqualTo(1);
		// A stale version is refused too, without any race.
		api.assign(tripId, first.driverId(), 0).expectStatus().isEqualTo(409);
	}

	@Test
	void retryingABookingWithTheSameKeyBooksOnce() {
		UUID quote = api.quote(passenger.token(), inTwoHours(), null, false);
		String key = UUID.randomUUID().toString();

		JsonNode firstTry = api.post("/api/v1/rides", passenger.token(), cashRide(quote), key).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
		JsonNode retry = api.post("/api/v1/rides", passenger.token(), cashRide(quote), key).expectStatus().isCreated()
			.expectHeader().valueEquals("Idempotent-Replayed", "true")
			.expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(retry.get("id").asString()).isEqualTo(firstTry.get("id").asString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking.ride_requests WHERE quote_id = ?", Integer.class, quote))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox.outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'RideBooked'",
				Integer.class, firstTry.get("id").asString())).isEqualTo(1);
		api.post("/api/v1/rides", passenger.token(), cashRide(quote), UUID.randomUUID().toString())
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("booking.quote-used");
		api.post("/api/v1/rides", passenger.token(), cashRide(quote), null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("booking.idempotency-key-required");
	}
}

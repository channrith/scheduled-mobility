package com.mobility.core.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.dispatch.assignment.DeadlineWatcher;
import com.mobility.core.dispatch.risk.DriverPositions;
import com.mobility.core.dispatch.risk.RiskEvaluator;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class RiskAndQueueIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	RiskEvaluator risk;

	@Autowired
	DeadlineWatcher deadlines;

	@Autowired
	DriverPositions positions;

	static DispatchApi api;

	DriverApi drivers;

	@BeforeEach
	void setUp() {
		if (api == null) {
			api = new DispatchApi(client, issuer);
		}
		drivers = new DriverApi(client, issuer);
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** The pickup is the test city's centre (BookingApi); {@code km} north of it, as the crow flies. */
	void driverAt(Registered driver, double km, Instant recordedAt) {
		positions.record(driver.userId(), api.booking.lat + km / 111.2, api.booking.lng, recordedAt);
	}

	String tierOf(UUID trip) {
		return jdbc.queryForObject("SELECT risk_tier FROM dispatch.trip_assignments WHERE trip_id = ?", String.class, trip);
	}

	@Test
	void slackTiersFromRedisPositionsOpenEscalateAndCloseAtRisk() {
		Registered driver = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofMinutes(40));
		api.assignOk(trip, driver.driverId(), null);

		// At the pickup: ETA 0, slack ≈ 40 min.
		driverAt(driver, 0, Instant.now());
		risk.evaluate();
		assertThat(tierOf(trip)).isEqualTo("GREEN");
		assertThat(api.openException(trip, "AT_RISK")).isNull();

		// ≈ 7 km away: 7 × 1.4 = 9.8 km at 20 km/h ≈ 29 min → slack ≈ 11 min.
		driverAt(driver, 7, Instant.now());
		risk.evaluate();
		assertThat(tierOf(trip)).isEqualTo("AMBER");
		assertThat(api.openException(trip, "AT_RISK").get("severity").asString()).isEqualTo("HIGH");

		// ≈ 10 km away: ≈ 42 min → already late.
		driverAt(driver, 10, Instant.now());
		risk.evaluate();
		assertThat(tierOf(trip)).isEqualTo("RED");
		JsonNode red = api.openException(trip, "AT_RISK");
		assertThat(red.get("severity").asString()).isEqualTo("CRITICAL");
		assertThat(red.get("details").get("reason").asString()).isEqualTo("SLACK");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.exceptions WHERE trip_id = ? AND exception_type = 'AT_RISK'",
				Integer.class, trip)).as("one exception, escalated").isEqualTo(1);

		driverAt(driver, 1, Instant.now());
		risk.evaluate();
		assertThat(tierOf(trip)).isEqualTo("GREEN");
		assertThat(api.openException(trip, "AT_RISK")).isNull();
		assertThat(jdbc.queryForObject("SELECT status FROM dispatch.exceptions WHERE trip_id = ? AND exception_type = 'AT_RISK'",
				String.class, trip)).isEqualTo("AUTO_RESOLVED");
	}

	@Test
	void noPositionForThreeMinutesIsRed() {
		Registered driver = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofMinutes(60));
		api.assignOk(trip, driver.driverId(), null);

		risk.evaluate();
		assertThat(tierOf(trip)).isEqualTo("RED");
		assertThat(jdbc.queryForObject("SELECT risk_reason FROM dispatch.trip_assignments WHERE trip_id = ?", String.class, trip))
			.isEqualTo("NO_PING");

		driverAt(driver, 0, Instant.now().minus(Duration.ofMinutes(4)));
		risk.evaluate();
		assertThat(api.openException(trip, "AT_RISK").get("details").get("reason").asString()).isEqualTo("NO_PING");
		driverAt(driver, 0, Instant.now().minus(Duration.ofMinutes(2)));
		risk.evaluate();
		assertThat(tierOf(trip)).isEqualTo("GREEN");
	}

	@Test
	void queueIsSortedBySeverityThenPickupAndCanBeTakenAndResolved() {
		UUID later = api.trip(Duration.ofMinutes(100));
		UUID sooner = api.trip(Duration.ofMinutes(50));
		Registered driver = drivers.approvedDriver();
		UUID atRisk = api.trip(Duration.ofMinutes(70));
		api.assignOk(atRisk, driver.driverId(), null);

		deadlines.check();
		risk.evaluate();

		List<String> mine = api.booking.json(api.booking.get("/api/v1/dispatch/exceptions", api.dispatcher())).valueStream()
			.filter(e -> List.of(later.toString(), sooner.toString(), atRisk.toString()).contains(e.get("tripId").asString()))
			.map(e -> e.get("tripId").asString() + ":" + e.get("type").asString())
			.toList();
		assertThat(mine).containsExactly(atRisk + ":AT_RISK", sooner + ":UNASSIGNED", later + ":UNASSIGNED");

		JsonNode e = api.openException(sooner, "UNASSIGNED");
		JsonNode taken = api.booking.json(api.booking.post("/api/v1/dispatch/exceptions/" + e.get("id").asString() + "/take",
				api.dispatcher(), Map.of("version", e.get("version").asLong()), null));
		assertThat(taken.get("status").asString()).isEqualTo("ACKNOWLEDGED");
		assertThat(taken.get("assignedDispatcherId").asString()).isEqualTo(api.dispatcherId.toString());
		api.booking.post("/api/v1/dispatch/exceptions/" + e.get("id").asString() + "/resolve", api.dispatcher(),
				Map.of("version", e.get("version").asLong(), "note", "Called the client"), null).expectStatus().isEqualTo(409);
		JsonNode resolved = api.booking.json(api.booking.post("/api/v1/dispatch/exceptions/" + e.get("id").asString() + "/resolve",
				api.dispatcher(), Map.of("version", taken.get("version").asLong(), "note", "Called the client"), null));
		assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class, e.get("id").asString()))
			.containsExactlyInAnyOrder("dispatch.exception.taken", "dispatch.exception.resolved");
	}

	@Test
	void cancellingTheRideClosesItsExceptions() {
		var passenger = api.booking.passenger();
		UUID quote = api.booking.quote(passenger.token(), Instant.now().plus(Duration.ofMinutes(45)), null, false);
		JsonNode ride = api.booking.bookOk(passenger.token(), com.mobility.core.booking.BookingApi.cashRide(quote));
		UUID trip = com.mobility.core.booking.BookingApi.firstTrip(ride);
		deadlines.check();
		assertThat(api.openException(trip, "UNASSIGNED")).isNotNull();

		api.booking.json(api.booking.post("/api/v1/rides/" + ride.get("id").asString() + "/cancel", passenger.token(), Map.of(), null));

		assertThat(api.openException(trip, "UNASSIGNED")).isNull();
		assertThat(jdbc.queryForObject("SELECT status FROM dispatch.exceptions WHERE trip_id = ?", String.class, trip))
			.isEqualTo("AUTO_RESOLVED");
	}
}

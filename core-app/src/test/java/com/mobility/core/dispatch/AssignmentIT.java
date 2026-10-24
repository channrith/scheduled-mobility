package com.mobility.core.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.dispatch.assignment.DeadlineWatcher;
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
class AssignmentIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	DeadlineWatcher deadlines;

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

	@Test
	void everyAssignmentNeedsAReasonIsAuditedAndWarnsWithoutBlocking() {
		Registered driver = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofHours(5));

		api.assign(trip, driver.driverId(), null, "OTHER", null).expectStatus().isBadRequest().expectBody()
			.jsonPath("$.code").isEqualTo("dispatch.note-required");
		api.assign(trip, driver.driverId(), driver.driverId(), "PLANNED", null).expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("dispatch.backup-same-as-primary");
		api.booking.post("/api/v1/dispatch/trips/" + trip + "/assign", api.dispatcher(),
				Map.of("driverId", driver.driverId().toString(), "tripVersion", api.tripVersion(trip)), null)
			.expectStatus().isBadRequest();

		JsonNode result = api.assignOk(trip, driver.driverId(), null);

		assertThat(result.get("trip").get("status").asString()).isEqualTo("ASSIGNED");
		assertThat(result.get("trip").get("assignment").get("reasonCode").asString()).isEqualTo("PLANNED");
		assertThat(result.get("warnings").valueStream().map(w -> w.get("code").asString())).containsExactly("NO_SHIFT");
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class, trip.toString()))
			.containsExactly("dispatch.trip.assigned");
		assertThat(jdbc.queryForObject("""
				SELECT details->>'reasonCode' FROM audit.audit_logs WHERE target_id = ? AND action = 'dispatch.trip.assigned'""",
				String.class, trip.toString())).isEqualTo("PLANNED");
		JsonNode detail = api.detail(trip);
		assertThat(detail.get("history").get(0).get("action").asString()).isEqualTo("ASSIGNED");
	}

	@Test
	void airportRidesNeedABackup() {
		Registered primary = drivers.approvedDriver();
		Registered backup = drivers.approvedDriver();
		UUID trip = api.trip(Instant.now().plus(Duration.ofHours(5)), true);

		api.assign(trip, primary.driverId(), null, "PLANNED", null).expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("dispatch.backup-required");
		JsonNode ok = api.assignOk(trip, primary.driverId(), backup.driverId());
		assertThat(ok.get("trip").get("slaTrip").asBoolean()).isTrue();
		assertThat(ok.get("trip").get("assignment").get("backupDriverId").asString()).isEqualTo(backup.driverId().toString());
	}

	@Test
	void unconfirmedAtTheDeadlineBecomesAnExceptionAndConfirmingClosesIt() {
		Registered driver = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofHours(3));
		JsonNode assigned = api.assignOk(trip, driver.driverId(), null).get("trip");
		Instant deadline = Instant.parse(assigned.get("assignment").get("primaryDeadline").asString());
		assertThat(deadline).isEqualTo(Instant.parse(assigned.get("pickupAt").asString()).minus(Duration.ofMinutes(60)));

		deadlines.check();
		assertThat(api.openException(trip, "UNCONFIRMED")).isNull();

		clock.advance(Duration.between(Instant.now(), deadline).plusSeconds(5));
		deadlines.check();
		JsonNode open = api.openException(trip, "UNCONFIRMED");
		assertThat(open).isNotNull();
		assertThat(open.get("severity").asString()).isEqualTo("HIGH");

		api.booking.json(api.booking.post("/api/v1/driver/trips/" + trip + "/confirm", api.driverToken(driver.userId()), Map.of(), null));
		assertThat(api.openException(trip, "UNCONFIRMED")).isNull();
		assertThat(jdbc.queryForObject("SELECT status FROM dispatch.exceptions WHERE trip_id = ? AND exception_type = 'UNCONFIRMED'",
				String.class, trip)).isEqualTo("AUTO_RESOLVED");
	}

	@Test
	void aLateAssignmentGetsTenMinutesToConfirm() {
		Registered driver = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofMinutes(40));
		api.assignOk(trip, driver.driverId(), null);

		deadlines.check();
		assertThat(api.openException(trip, "UNCONFIRMED")).isNull();
		clock.advance(Duration.ofMinutes(11));
		deadlines.check();
		assertThat(api.openException(trip, "UNCONFIRMED")).isNotNull();
	}

	@Test
	void switchToBackupWithTwoClicksAtOnceGivesOneSuccessAndOneConflict() throws Exception {
		Registered primary = drivers.approvedDriver();
		Registered backup = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofHours(4));
		api.assignOk(trip, primary.driverId(), backup.driverId());
		api.booking.json(api.booking.post("/api/v1/driver/trips/" + trip + "/confirm", api.driverToken(backup.userId()), Map.of(), null));
		long version = api.assignmentVersion(trip);

		List<Integer> statuses = race(() -> api.switchToBackup(trip, version).returnResult(String.class).getStatus().value(),
				() -> api.switchToBackup(trip, version).returnResult(String.class).getStatus().value());

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		JsonNode after = api.detail(trip).get("trip");
		assertThat(after.get("driverId").asString()).isEqualTo(backup.driverId().toString());
		assertThat(after.get("assignment").get("backupDriverId").isNull()).isTrue();
		assertThat(after.get("assignment").get("primaryConfirmedAt").isNull()).as("the backup's confirmation carries over").isFalse();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_history WHERE trip_id = ? AND action = 'SWITCHED_TO_BACKUP'",
				Integer.class, trip)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_logs WHERE target_id = ? AND action = 'dispatch.trip.backup-switched'",
				Integer.class, trip.toString())).isEqualTo(1);
		// Nothing left to switch to, and a stale version is refused.
		api.switchToBackup(trip, version).expectStatus().isEqualTo(409);
		api.switchToBackup(trip, api.assignmentVersion(trip)).expectStatus().isEqualTo(409).expectBody()
			.jsonPath("$.code").isEqualTo("dispatch.no-backup");
	}

	@Test
	void aReassignmentRacingASwitchToBackupConflicts() throws Exception {
		Registered primary = drivers.approvedDriver();
		Registered backup = drivers.approvedDriver();
		Registered third = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofHours(4));
		api.assignOk(trip, primary.driverId(), backup.driverId());
		long assignmentVersion = api.assignmentVersion(trip);
		long tripVersion = api.tripVersion(trip);

		List<Integer> statuses = race(() -> api.switchToBackup(trip, assignmentVersion).returnResult(String.class).getStatus().value(),
				() -> api.booking.post("/api/v1/dispatch/trips/" + trip + "/assign", api.dispatcher(), Map.of("driverId",
						third.driverId().toString(), "backupDriverId", backup.driverId().toString(), "reasonCode", "DRIVER_LATE",
						"tripVersion", tripVersion), null).returnResult(String.class).getStatus().value());

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking.trip_events WHERE trip_id = ? AND to_status = 'ASSIGNED'",
				Integer.class, trip)).isEqualTo(2);
	}

	@Test
	void aDecliningDriverIsTakenOffAndTheTripQueued() {
		Registered driver = drivers.approvedDriver();
		UUID trip = api.trip(Duration.ofHours(6));
		api.assignOk(trip, driver.driverId(), null);

		JsonNode after = api.booking.json(api.booking.post("/api/v1/driver/trips/" + trip + "/decline", api.driverToken(driver.userId()),
				Map.of("reason", "Car in the garage"), null));

		assertThat(after.get("primaryDriverId").isNull()).isTrue();
		assertThat(api.detail(trip).get("trip").get("status").asString()).isEqualTo("SCHEDULED");
		JsonNode declined = api.openException(trip, "DRIVER_DECLINED");
		assertThat(declined.get("severity").asString()).isEqualTo("HIGH");
		assertThat(jdbc.queryForObject("SELECT actor_role FROM booking.trip_events WHERE trip_id = ? ORDER BY seq DESC LIMIT 1",
				String.class, trip)).isEqualTo("DRIVER");
		api.booking.post("/api/v1/driver/trips/" + trip + "/confirm", api.driverToken(driver.userId()), Map.of(), null)
			.expectStatus().isNotFound();
	}

	static List<Integer> race(java.util.concurrent.Callable<Integer> a, java.util.concurrent.Callable<Integer> b) throws Exception {
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> fa = pool.submit(() -> {
				go.await();
				return a.call();
			});
			Future<Integer> fb = pool.submit(() -> {
				go.await();
				return b.call();
			});
			go.countDown();
			return List.of(fa.get(), fb.get());
		}
		finally {
			pool.shutdown();
		}
	}
}

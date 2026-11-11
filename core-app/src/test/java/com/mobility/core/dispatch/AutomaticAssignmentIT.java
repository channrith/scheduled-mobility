package com.mobility.core.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.mobility.core.IntegrationTest;
import com.mobility.core.booking.Trips;
import com.mobility.core.dispatch.assignment.AssignmentService;
import com.mobility.core.dispatch.automatic.AssignmentJobs;
import com.mobility.core.dispatch.automatic.AssignmentWorker;
import com.mobility.core.dispatch.suggest.AutomaticAssignmentService;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.awaitility.Awaitility.await;

@IntegrationTest
class AutomaticAssignmentIT {
    @Autowired RestTestClient client;
    @Autowired AccessTokenIssuer issuer;
    @Autowired JdbcTemplate jdbc;
    @Autowired Trips trips;
    @Autowired AssignmentJobs jobs;
    @Autowired AssignmentWorker worker;
    @Autowired AutomaticAssignmentService automatic;
    @Autowired AssignmentService assignments;
    @Autowired OutboxRelay relay;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin rabbitAdmin;
    DispatchApi api;
    DriverApi drivers;

    @BeforeEach
    void setup() {
        api = new DispatchApi(client, issuer);
        drivers = new DriverApi(client, issuer);
        // OutboxIT deliberately removes the exchange; restore declarative bindings for this fixture.
        rabbitAdmin.initialize();
    }

    @Test
    void realRelayToRabbitToDurableJobAndDriverCompletion() {
        Instant pickup = Instant.now().plus(Duration.ofDays(3));
        var driver = drivers.approvedDriver();
        api.shift(driver.token(), pickup.minusSeconds(3600), pickup.plusSeconds(7200));
        UUID id = api.trip(pickup, false);
        for (int i = 0; i < 100 && countJob(id) == 0; i++) relay.relayBatch();
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(countJob(id)).isEqualTo(1));
        // Use the same worker entry point used by polling; unrelated fixture jobs may be ahead of ours.
        for (int i = 0; i < 500 && trips.find(id).orElseThrow().driverId() == null; i++) worker.processNext();
        var assigned = trips.find(id).orElseThrow();
        assertThat(assigned.driverId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM dispatch.trip_assignments WHERE trip_id = ?", String.class, id))
                .isEqualTo("AUTO_ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT actor_role FROM booking.trip_events WHERE trip_id = ? AND to_status = 'ASSIGNED'", String.class, id))
                .isEqualTo("SYSTEM");
        var userId = jdbc.queryForObject("SELECT user_id FROM driver.drivers WHERE id = ?", UUID.class, assigned.driverId());
        String token = api.driverToken(userId);
        for (String step : List.of("confirm", "en-route", "arrived", "start", "complete")) {
            api.booking.post("/api/v1/driver/trips/" + id + "/" + step, token, Map.of(), null).expectStatus().isOk();
        }
        assertThat(trips.find(id).orElseThrow().status().name()).isEqualTo("COMPLETED");
        jobs.enqueue(UUID.randomUUID(), assigned.rideId(), List.of(id, id));
        assertThat(countJob(id)).isEqualTo(1);
        assertThat(automatic.attempt(id, 1).reason()).isEqualTo("NO_LONGER_UNASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_history WHERE trip_id = ? AND action = 'ASSIGNED'", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void simultaneousAssignmentsOfOverlappingTripsCannotShareADriver() throws Exception {
        var driver = drivers.approvedDriver();
        Instant pickup = Instant.now().plus(Duration.ofDays(4));
        api.shift(driver.token(), pickup.minusSeconds(3600), pickup.plusSeconds(7200));
        UUID a = api.trip(pickup, false), b = api.trip(pickup.plusSeconds(60), false);
        var statuses = AssignmentIT.race(
                () -> attemptAssignment(a, driver.driverId()),
                () -> attemptAssignment(b, driver.driverId()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(List.of(trips.find(a).orElseThrow(), trips.find(b).orElseThrow()).stream()
                .filter(t -> driver.driverId().equals(t.driverId())).count()).isEqualTo(1);
    }

    @Test
    void automaticAndManualAssignmentShareTheSameConcurrencyGuard() throws Exception {
        var driver = drivers.approvedDriver();
        Instant pickup = Instant.now().plus(Duration.ofDays(4)).plusSeconds(14400);
        api.shift(driver.token(), pickup.minusSeconds(3600), pickup.plusSeconds(7200));
        UUID a = api.trip(pickup, false), b = api.trip(pickup, false);
        var statuses = AssignmentIT.race(() -> attemptAssignment(a, driver.driverId()),
                () -> api.assign(b, driver.driverId(), null, "PLANNED", null).returnResult(String.class).getStatus().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void backupReservationsAlsoBlockAnotherPrimary() {
        var primary = drivers.approvedDriver();
        var backup = drivers.approvedDriver();
        Instant pickup = Instant.now().plus(Duration.ofDays(2));
        UUID first = api.trip(pickup, true), second = api.trip(pickup.plusSeconds(60), false);
        api.assignOk(first, primary.driverId(), backup.driverId());
        api.assign(second, backup.driverId(), null, "PLANNED", null).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("dispatch.driver-overlap");
    }

    @Test
    void noSupplyRetriesDurablyAndEventuallyGoesToDispatcher() {
        UUID id = api.trip(Instant.now().plus(Duration.ofDays(6)), false);
        var trip = trips.find(id).orElseThrow();
        jobs.enqueue(UUID.randomUUID(), trip.rideId(), List.of(id));
        jdbc.update("UPDATE dispatch.assignment_jobs SET next_attempt_at = ? WHERE trip_id = ?",
                java.sql.Timestamp.from(Instant.EPOCH), id);
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM dispatch.assignment_jobs WHERE trip_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch.assignment_jobs WHERE trip_id = ?", String.class, id))
                .isEqualTo("PENDING");
        assertThat(automatic.attempt(id, 60).status()).isEqualTo("MANUAL");
        assertThat(api.openException(id, "UNASSIGNED")).isNotNull();
        assertThat(trips.find(id).orElseThrow().driverId()).isNull();
    }

    @Test
    void futureRecurringWorkWaitsUntilHorizonAndCancelledTripsAreSkipped() {
        UUID id = api.trip(Instant.now().plus(Duration.ofDays(10)), false);
        var result = automatic.attempt(id, 1);
        assertThat(result.reason()).isEqualTo("OUTSIDE_HORIZON");
        assertThat(result.nextAttempt()).isEqualTo(trips.find(id).orElseThrow().pickupAt().minus(Duration.ofDays(7)));
        api.booking.post("/api/v1/ops/trips/" + id + "/cancel", api.dispatcher(),
                Map.of("reason", "Passenger requested cancellation"), null).expectStatus().isOk();
        assertThat(automatic.attempt(id, 1).reason()).isEqualTo("NO_LONGER_UNASSIGNED");
    }

    @Test
    void airportDoesNotAssignWithoutRequiredBackup() {
        Instant pickup = Instant.now().plus(Duration.ofDays(2)).plusSeconds(18000);
        var driver = drivers.approvedDriver();
        api.shift(driver.token(), pickup.minusSeconds(3600), pickup.plusSeconds(7200));
        UUID id = api.trip(pickup, true);
        assertThat(automatic.attempt(id, 1).reason()).isEqualTo("NO_BACKUP");
        assertThat(trips.find(id).orElseThrow().driverId()).isNull();
        var backup = drivers.approvedDriver();
        api.shift(backup.token(), pickup.minusSeconds(3600), pickup.plusSeconds(7200));
        assertThat(automatic.attempt(id, 2).reason()).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT backup_driver_id FROM dispatch.trip_assignments WHERE trip_id = ?", UUID.class, id)).isNotNull();
    }

    @Test
    void malformedRabbitMessagesGoToDeadLetterQueue() {
        rabbit.send("mobility.events", "booking.ride-booked",
                new org.springframework.amqp.core.Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rabbit.receive("dispatch.ride-booked.dead")).isNotNull());
    }

    @Test
    void twoTripStartsForOneDriverCannotBothSucceed() throws Exception {
        var driver = drivers.approvedDriver();
        Instant pickup = Instant.now().plus(Duration.ofDays(1));
        UUID a = api.trip(pickup, false);
        UUID b = api.trip(pickup.plusSeconds(14400), false);
        api.assignOk(a, driver.driverId(), null);
        api.assignOk(b, driver.driverId(), null);
        for (UUID id : List.of(a, b)) {
            for (String step : List.of("confirm", "en-route", "arrived")) {
                api.booking.post("/api/v1/driver/trips/" + id + "/" + step,
                        driver.token(), Map.of(), null).expectStatus().isOk();
            }
        }
        var statuses = AssignmentIT.race(
                () -> api.booking.post("/api/v1/driver/trips/" + a + "/start", driver.token(), Map.of(), null)
                        .returnResult(String.class).getStatus().value(),
                () -> api.booking.post("/api/v1/driver/trips/" + b + "/start", driver.token(), Map.of(), null)
                        .returnResult(String.class).getStatus().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    private int attemptAssignment(UUID trip, UUID driver) {
        try {
            assignments.assignAutomatically(trip, driver, null, 0);
            return 200;
        } catch (com.mobility.core.shared.web.ApiException conflict) {
            return 409;
        }
    }
    private int countJob(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_jobs WHERE trip_id = ?", Integer.class, id);
    }
}

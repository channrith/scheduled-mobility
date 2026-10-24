package com.mobility.core.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ShiftsAndPlanningIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	static DispatchApi api;

	DriverApi drivers;

	@BeforeEach
	void setUp() {
		if (api == null) {
			api = new DispatchApi(client, issuer);
		}
		drivers = new DriverApi(client, issuer);
	}

	/** A date 5 days ahead (Phnom Penh), so nothing of today's tests is on it. */
	static LocalDate day() {
		return LocalDate.now(BusinessTime.ZONE).plusDays(5);
	}

	static Instant at(int hour) {
		return day().atTime(hour, 0).atZone(BusinessTime.ZONE).toInstant();
	}

	@Test
	void driversDeclareShiftsThatDoNotOverlapAndCannotDropOnesWithTrips() {
		Registered driver = drivers.approvedDriver();
		JsonNode block = api.shift(driver.token(), at(6), at(14));

		api.booking.post("/api/v1/driver/shifts", driver.token(), Map.of("startsAt", at(13).toString(), "endsAt", at(18).toString()), null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("dispatch.shift-overlap");
		api.booking.post("/api/v1/driver/shifts", driver.token(), Map.of("startsAt", at(15).toString(), "endsAt",
				at(15).plus(Duration.ofHours(15)).toString()), null).expectStatus().isBadRequest();
		api.booking.post("/api/v1/driver/shifts", api.booking.tokenFor(Role.PASSENGER), Map.of("startsAt", at(15).toString(),
				"endsAt", at(18).toString()), null).expectStatus().isForbidden();

		List<String> seen = api.booking.json(api.booking.get("/api/v1/dispatch/shifts?date=" + day(), api.dispatcher())).valueStream()
			.map(s -> s.get("id").asString()).toList();
		assertThat(seen).contains(block.get("id").asString());

		UUID trip = api.trip(at(9), false);
		JsonNode assigned = api.assignOk(trip, driver.driverId(), null);
		assertThat(assigned.get("warnings")).isEmpty();
		api.booking.client.delete().uri("/api/v1/driver/shifts/" + block.get("id").asString())
			.header("Authorization", "Bearer " + driver.token()).exchange()
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("dispatch.shift-has-trips");
	}

	@Test
	void suggestionsProposeAPrimaryAndBackupThatCanBeAccepted() {
		Registered a = drivers.approvedDriver();
		Registered b = drivers.approvedDriver();
		api.shift(a.token(), at(5), at(17));
		api.shift(b.token(), at(5), at(17));
		UUID first = api.trip(at(8), false);
		UUID second = api.trip(at(11), false);

		JsonNode plan = api.booking.json(api.booking.get("/api/v1/dispatch/suggestions?date=" + day(), api.dispatcher()));
		JsonNode s1 = suggestionFor(plan, first);
		JsonNode s2 = suggestionFor(plan, second);
		assertThat(s1.get("primary").isNull()).isFalse();
		assertThat(s1.get("backup").get("driverId").asString()).isNotEqualTo(s1.get("primary").get("driverId").asString());

		JsonNode applied = api.booking.json(api.booking.post("/api/v1/dispatch/suggestions/accept", api.dispatcher(), Map.of("items",
				List.of(item(first, s1), item(second, s2), Map.of("tripId", first.toString(), "driverId",
						s1.get("primary").get("driverId").asString(), "tripVersion", 0))), null));
		assertThat(applied.valueStream().map(r -> r.get("error").isNull() ? "ok" : r.get("error").asString()))
			.containsExactly("ok", "ok", "concurrent-update");
		JsonNode detail = api.detail(first);
		assertThat(detail.get("trip").get("status").asString()).isEqualTo("ASSIGNED");
		assertThat(detail.get("trip").get("assignment").get("reasonCode").asString()).isEqualTo("SUGGESTION_ACCEPTED");
	}

	@Test
	void timelineShowsEachDriversShiftsAndTripsAndTheUnassignedOnes() {
		Registered driver = drivers.approvedDriver();
		Registered backup = drivers.approvedDriver();
		api.shift(driver.token(), at(6), at(12));
		UUID assigned = api.trip(at(7), false);
		UUID open = api.trip(at(10), false);
		api.assignOk(assigned, driver.driverId(), backup.driverId());

		JsonNode timeline = api.booking.json(api.booking.get("/api/v1/dispatch/timeline?date=" + day(), api.dispatcher()));

		JsonNode lane = laneOf(timeline, driver.driverId());
		assertThat(lane.get("shifts")).hasSize(1);
		assertThat(lane.get("trips").get(0).get("role").asString()).isEqualTo("PRIMARY");
		assertThat(laneOf(timeline, backup.driverId()).get("trips").get(0).get("role").asString()).isEqualTo("BACKUP");
		assertThat(timeline.get("unassigned").valueStream().map(t -> t.get("tripId").asString())).contains(open.toString())
			.doesNotContain(assigned.toString());
		api.booking.get("/api/v1/dispatch/timeline?date=" + day(), api.booking.tokenFor(Role.SUPPORT)).expectStatus().isForbidden();
	}

	static JsonNode suggestionFor(JsonNode plan, UUID trip) {
		return plan.get("suggestions").valueStream().filter(s -> s.get("tripId").asString().equals(trip.toString())).findFirst()
			.orElseThrow();
	}

	static Map<String, Object> item(UUID trip, JsonNode s) {
		return Map.of("tripId", trip.toString(), "driverId", s.get("primary").get("driverId").asString(), "backupDriverId",
				s.get("backup").get("driverId").asString(), "tripVersion", 0);
	}

	static JsonNode laneOf(JsonNode timeline, UUID driverId) {
		return timeline.get("lanes").valueStream().filter(l -> l.get("driverId").asString().equals(driverId.toString())).findFirst()
			.orElseThrow();
	}
}

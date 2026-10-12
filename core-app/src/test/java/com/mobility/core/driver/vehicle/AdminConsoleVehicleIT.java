package com.mobility.core.driver.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.SamplePhones;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Vehicle ownership, editing, listing and assignment periods. */
@IntegrationTest
class AdminConsoleVehicleIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	MutableClock clock;

	DriverApi api;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	@Test
	void rentedTukTukNeedsItsOwner() {
		api.post("/api/v1/admin/vehicles", api.adminToken, vehicle(plate(), "TUKTUK_REMORK", "THIRD_PARTY", null, null))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("vehicle.owner-required");

		api.post("/api/v1/admin/vehicles", api.adminToken, vehicle(plate(), "TUKTUK_REMORK", "THIRD_PARTY", "Mr. Rith", "012 777 888"))
			.expectStatus().isCreated()
			.expectBody()
			.jsonPath("$.ownership").isEqualTo("THIRD_PARTY")
			.jsonPath("$.ownerName").isEqualTo("Mr. Rith")
			.jsonPath("$.ownerPhone").isEqualTo("+85512777888");

		api.post("/api/v1/admin/vehicles", api.adminToken, vehicle(plate(), "CAR", "DRIVER", "ignored", "012 777 888"))
			.expectStatus().isCreated().expectBody().jsonPath("$.ownerName").isEmpty();
	}

	@Test
	void editWithVersionAndNoDeactivationWhileAssigned() {
		JsonNode created = create(vehicle(plate(), "CAR", null, null, null));
		String id = created.get("id").asString();
		Map<String, Object> edit = new HashMap<>(vehicle(created.get("plateNumber").asString(), "SUV", "COMPANY", "Mobility Co", null));
		edit.put("color", "White");
		edit.put("status", "ACTIVE");
		edit.put("version", 0);

		put(id, edit).expectStatus().isOk().expectBody()
			.jsonPath("$.vehicleClass").isEqualTo("SUV").jsonPath("$.color").isEqualTo("White").jsonPath("$.version").isEqualTo(1);
		put(id, edit).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("concurrent-update");

		edit.put("plateNumber", create(vehicle(plate(), "CAR", null, null, null)).get("plateNumber").asString());
		edit.put("version", 1);
		put(id, edit).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("vehicle.plate-taken");

		api.assignVehicle(api.register().driverId(), UUID.fromString(id));
		edit.put("plateNumber", created.get("plateNumber").asString());
		edit.put("status", "INACTIVE");
		put(id, edit).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("vehicle.has-assignment");
	}

	@Test
	void listSearchesPlateAndShowsTheAssignedDriver() {
		Registered driver = api.register(Map.of("fullName", "Listed Driver"));
		JsonNode created = create(vehicle(plate(), "VAN", null, null, null));
		api.assignVehicle(driver.driverId(), UUID.fromString(created.get("id").asString()));
		String compact = created.get("plateNumber").asString().replaceAll("[^A-Z0-9]", "").toLowerCase();

		JsonNode page = api.get("/api/v1/admin/vehicles?q=" + compact + "&vehicleClass=VAN&status=ACTIVE", api.adminToken)
			.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(page.get("totalElements").asLong()).isEqualTo(1);
		assertThat(page.at("/content/0/vehicle/id").asString()).isEqualTo(created.get("id").asString());
		assertThat(page.at("/content/0/assignedDriverName").asString()).isEqualTo("Listed Driver");
		api.get("/api/v1/admin/vehicles?q=" + compact + "&vehicleClass=CAR", api.adminToken)
			.expectStatus().isOk().expectBody().jsonPath("$.totalElements").isEqualTo(0);
	}

	@Test
	void futureAssignmentIsNotCurrentAndBlocksOverlaps() {
		Registered first = api.register();
		Registered second = api.register();
		UUID vehicle = UUID.fromString(create(vehicle(plate(), "MOTO", null, null, null)).get("id").asString());
		Instant from = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		Instant to = from.plus(3, ChronoUnit.DAYS);

		JsonNode detail = assign(first.driverId(), vehicle, from, to).expectStatus().isOk()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
		assertThat(detail.get("vehicle").isNull()).isTrue();
		assertThat(detail.at("/vehicleAssignments/0/active").asBoolean()).isFalse();
		assertThat(Instant.parse(detail.at("/vehicleAssignments/0/validFrom").asString())).isEqualTo(from);

		assign(second.driverId(), vehicle, from.plus(1, ChronoUnit.DAYS), null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("vehicle.already-assigned");
		assign(second.driverId(), vehicle, to, to.plus(1, ChronoUnit.DAYS)).expectStatus().isOk();
		UUID other = UUID.fromString(create(vehicle(plate(), "MOTO", null, null, null)).get("id").asString());
		assign(first.driverId(), other, from.plus(1, ChronoUnit.DAYS), null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("driver.assignment-overlap");
	}

	@Test
	void reassigningWithAStartSlightlyBeforeTheCurrentOneReplacesIt() {
		Registered driver = api.register();
		UUID first = UUID.fromString(create(vehicle(plate(), "CAR", null, null, null)).get("id").asString());
		UUID second = UUID.fromString(create(vehicle(plate(), "CAR", null, null, null)).get("id").asString());
		assign(driver.driverId(), first, null, null).expectStatus().isOk();

		// A form rounds the start down to the minute: earlier than the running assignment's start.
		assign(driver.driverId(), second, Instant.now().minus(1, ChronoUnit.MINUTES), null)
			.expectStatus().isOk()
			.expectBody().jsonPath("$.vehicle.id").isEqualTo(second.toString());
	}

	@Test
	void invalidPeriodsAreRejected() {
		Registered driver = api.register();
		UUID vehicle = UUID.fromString(create(vehicle(plate(), "CAR", null, null, null)).get("id").asString());
		Instant now = Instant.now();

		assign(driver.driverId(), vehicle, now.minus(1, ChronoUnit.DAYS), null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("vehicle.assignment-in-past");
		assign(driver.driverId(), vehicle, now.plus(2, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("vehicle.assignment-invalid-period");
	}

	@Test
	void cancellingAFutureAssignmentFreesTheVehicle() {
		Registered first = api.register();
		UUID vehicle = UUID.fromString(create(vehicle(plate(), "CAR", null, null, null)).get("id").asString());
		Instant from = Instant.now().plus(1, ChronoUnit.DAYS);
		String assignment = assign(first.driverId(), vehicle, from, null).expectBody(JsonNode.class).returnResult()
			.getResponseBody().at("/vehicleAssignments/0/id").asString();

		client.delete().uri("/api/v1/admin/drivers/" + first.driverId() + "/vehicle-assignments/" + assignment)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + api.adminToken).exchange().expectStatus().isOk();

		assign(api.register().driverId(), vehicle, from, null).expectStatus().isOk();
	}

	@Test
	void assignmentStopsBeingCurrentAfterValidTo() {
		Registered driver = api.register();
		UUID vehicle = UUID.fromString(create(vehicle(plate(), "CAR", null, null, null)).get("id").asString());
		assign(driver.driverId(), vehicle, null, Instant.now().plus(1, ChronoUnit.HOURS)).expectStatus().isOk()
			.expectBody().jsonPath("$.vehicle.id").isEqualTo(vehicle.toString());

		clock.advance(Duration.ofHours(2));
		DriverApi later = new DriverApi(client, issuer);

		assertThat(later.detail(driver.driverId()).get("vehicle").isNull()).isTrue();
		assertThat(later.detail(driver.driverId()).at("/readiness/hasActiveVehicle").asBoolean()).isFalse();
	}

	private RestTestClient.ResponseSpec assign(UUID driverId, UUID vehicleId, Instant from, Instant to) {
		Map<String, Object> body = new HashMap<>(Map.of("vehicleId", vehicleId));
		if (from != null) {
			body.put("validFrom", from.toString());
		}
		if (to != null) {
			body.put("validTo", to.toString());
		}
		return api.admin(driverId, "vehicle-assignments", body);
	}

	private JsonNode create(Map<String, Object> body) {
		return api.post("/api/v1/admin/vehicles", api.adminToken, body).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	private RestTestClient.ResponseSpec put(String id, Map<String, Object> body) {
		return client.put().uri("/api/v1/admin/vehicles/" + id)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + api.adminToken)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private static Map<String, Object> vehicle(String plate, String vehicleClass, String ownership, String ownerName,
			String ownerPhone) {
		Map<String, Object> body = new HashMap<>(Map.of("plateNumber", plate, "vehicleClass", vehicleClass, "seats", 4));
		if (ownership != null) {
			body.put("ownership", ownership);
		}
		if (ownerName != null) {
			body.put("ownerName", ownerName);
		}
		if (ownerPhone != null) {
			body.put("ownerPhone", ownerPhone);
		}
		return body;
	}

	private static String plate() {
		return "PP " + UUID.randomUUID().toString().substring(0, 2).toUpperCase() + "-"
				+ SamplePhones.next().substring(8);
	}
}

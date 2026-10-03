package com.mobility.core.driver.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class VehicleIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	DriverApi api;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
	}

	@Test
	void registersVehicleWithNormalizedUniquePlate() {
		String plate = "pp  2b-" + UUID.randomUUID().toString().substring(0, 6);

		api.post("/api/v1/admin/vehicles", api.adminToken,
				Map.of("plateNumber", plate, "vehicleClass", "TUKTUK_REMORK", "seats", 4, "make", "Bajaj"))
			.expectStatus().isCreated()
			.expectBody()
			.jsonPath("$.plateNumber").isEqualTo(plate.replaceAll("\\s+", " ").toUpperCase())
			.jsonPath("$.vehicleClass").isEqualTo("TUKTUK_REMORK")
			.jsonPath("$.status").isEqualTo("ACTIVE");

		api.post("/api/v1/admin/vehicles", api.adminToken,
				Map.of("plateNumber", plate.toUpperCase(), "vehicleClass", "CAR", "seats", 4))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("vehicle.plate-taken");
	}

	@Test
	void validatesInputAndRequiresAdmin() {
		api.post("/api/v1/admin/vehicles", api.adminToken, Map.of("plateNumber", "X-1", "vehicleClass", "BUS", "seats", 4))
			.expectStatus().isBadRequest();
		api.post("/api/v1/admin/vehicles", api.adminToken, Map.of("plateNumber", "X-2", "vehicleClass", "CAR", "seats", 0))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("validation");
		api.post("/api/v1/admin/vehicles", api.tokenFor(Role.DISPATCHER),
				Map.of("plateNumber", "X-3", "vehicleClass", "CAR", "seats", 4)).expectStatus().isForbidden();
	}

	@Test
	void assignmentEnablesVehicleDocumentsAndIsAudited() {
		Registered driver = api.register();
		UUID vehicle = api.registerVehicle("MOTO");

		api.admin(driver.driverId(), "vehicle-assignment", Map.of("vehicleId", vehicle))
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.vehicle.id").isEqualTo(vehicle.toString())
			.jsonPath("$.vehicle.vehicleClass").isEqualTo("MOTO");

		api.uploadOk(driver.token(), "VEHICLE_REGISTRATION", SampleFiles.pdf(), DriverApi.inOneYear(), vehicle);
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class,
				driver.driverId().toString())).contains("driver.vehicle.assigned");
	}

	@Test
	void vehicleCannotBeAssignedToTwoDrivers() {
		Registered first = api.register();
		Registered second = api.register();
		UUID vehicle = api.registerVehicle("CAR");
		api.assignVehicle(first.driverId(), vehicle);

		api.admin(second.driverId(), "vehicle-assignment", Map.of("vehicleId", vehicle))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("vehicle.already-assigned");
		// Re-assigning to the same driver is a no-op.
		api.admin(first.driverId(), "vehicle-assignment", Map.of("vehicleId", vehicle)).expectStatus().isOk();
	}

	@Test
	void reassigningEndsThePreviousAssignmentAndFreesTheOldVehicle() {
		Registered driver = api.register();
		UUID oldVehicle = api.registerVehicle("CAR");
		UUID newVehicle = api.registerVehicle("SUV");
		api.assignVehicle(driver.driverId(), oldVehicle);

		api.assignVehicle(driver.driverId(), newVehicle);

		assertThat(api.detail(driver.driverId()).get("vehicle").get("id").asString()).isEqualTo(newVehicle.toString());
		assertThat(jdbc.queryForObject(
				"SELECT unassigned_at IS NOT NULL FROM driver.driver_vehicle_assignments WHERE vehicle_id = ?",
				Boolean.class, oldVehicle)).isTrue();
		api.assignVehicle(api.register().driverId(), oldVehicle);
	}

	@Test
	void unassign() {
		Registered driver = api.register();
		api.assignVehicle(driver.driverId(), api.registerVehicle("VAN"));

		client.delete()
			.uri("/api/v1/admin/drivers/" + driver.driverId() + "/vehicle-assignment")
			.header("Authorization", "Bearer " + api.adminToken)
			.exchange()
			.expectStatus().isOk()
			.expectBody().jsonPath("$.vehicle").isEmpty();
		client.delete()
			.uri("/api/v1/admin/drivers/" + driver.driverId() + "/vehicle-assignment")
			.header("Authorization", "Bearer " + api.adminToken)
			.exchange()
			.expectStatus().isEqualTo(409)
			.expectBody().jsonPath("$.code").isEqualTo("vehicle.not-assigned");
	}

	@Test
	void unknownVehicleOrDriverIs404() {
		api.admin(api.register().driverId(), "vehicle-assignment", Map.of("vehicleId", UUID.randomUUID()))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("vehicle.not-found");
		api.admin(UUID.randomUUID(), "vehicle-assignment", Map.of("vehicleId", api.registerVehicle("CAR")))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("driver.not-found");
	}
}

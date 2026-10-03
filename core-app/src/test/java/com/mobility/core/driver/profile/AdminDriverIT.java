package com.mobility.core.driver.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.AuthTestClient;
import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.crypto.PiiCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AdminDriverIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PiiCrypto crypto;

	@Autowired
	AuthTestClient auth;

	DriverApi api;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
	}

	@Test
	void registerCreatesPendingDriverWithLoginAndMaskedPersonalData() {
		String phone = SamplePhones.next();
		JsonNode body = api.post("/api/v1/admin/drivers", api.adminToken,
				Map.of("phone", phone, "fullName", "  Sok Dara ", "nationalId", "0123 456-78", "bankName", "ABA",
						"bankAccountName", "SOK DARA", "bankAccountNumber", "000 123 456"))
			.expectStatus()
			.isCreated()
			.expectHeader().exists("Location")
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();

		UUID driverId = UUID.fromString(body.get("id").asString());
		UUID userId = UUID.fromString(body.get("userId").asString());
		assertThat(body.get("status").asString()).isEqualTo("PENDING");
		assertThat(body.get("fullName").asString()).isEqualTo("Sok Dara");
		assertThat(body.get("phone").asString()).isEqualTo(phone);
		assertThat(body.get("nationalIdMasked").asString()).isEqualTo("•••••5678");
		assertThat(body.get("bankAccount").get("accountNumberMasked").asString()).isEqualTo("•••••3456");
		assertThat(body.get("readiness").get("missing").toString()).contains("NATIONAL_ID", "DRIVING_LICENSE", "PROFILE_PHOTO");

		// Encrypted at rest, decryptable with the application key.
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT national_id_enc, bank_account_enc FROM driver.drivers WHERE id = ?", driverId);
		byte[] nationalIdEnc = (byte[]) row.get("national_id_enc");
		assertThat(new String(nationalIdEnc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("012345678");
		assertThat(crypto.decrypt(nationalIdEnc)).isEqualTo("012345678");
		assertThat(crypto.decrypt((byte[]) row.get("bank_account_enc"))).isEqualTo("000123456");

		// The driver can now log in with OTP.
		assertThat(jdbc.queryForList("SELECT role FROM identity.user_roles WHERE user_id = ?", String.class, userId))
			.containsExactly("DRIVER");

		assertThat(jdbc.queryForList("SELECT to_status FROM driver.driver_status_history WHERE driver_id = ?",
				String.class, driverId)).containsExactly("PENDING");
		assertThat(auditActions(driverId.toString())).containsExactly("driver.registered");
		assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit.audit_logs WHERE target_id = ?", UUID.class,
				driverId.toString())).isEqualTo(api.adminId);
		assertThat(auditActions(userId.toString())).containsExactly("identity.role.granted");
	}

	@Test
	void existingPassengerKeepsTheirAccountAndGainsDriverRole() {
		String phone = SamplePhones.next();
		auth.login(phone);
		UUID passengerId = jdbc.queryForObject("SELECT id FROM identity.users WHERE phone_e164 = ?", UUID.class, phone);

		Registered driver = api.register(Map.of("phone", phone));

		assertThat(driver.userId()).isEqualTo(passengerId);
		assertThat(jdbc.queryForList("SELECT role FROM identity.user_roles WHERE user_id = ?", String.class, passengerId))
			.containsExactlyInAnyOrder("PASSENGER", "DRIVER");
	}

	@Test
	void samePhoneCannotBeRegisteredTwiceAndNothingIsLeftBehind() {
		Registered first = api.register();

		api.post("/api/v1/admin/drivers", api.adminToken, Map.of("phone", first.phone(), "fullName", "Other"))
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.already-registered");

		assertThat(auditActions(first.userId().toString())).containsExactly("identity.role.granted");
	}

	@Test
	void nationalIdIsUniqueRegardlessOfFormatting() {
		String nationalId = String.valueOf(100_000_000 + new java.util.Random().nextInt(899_999_999));
		api.register(Map.of("nationalId", nationalId));

		api.post("/api/v1/admin/drivers", api.adminToken, Map.of("phone", SamplePhones.next(), "fullName", "Other",
				"nationalId", nationalId.substring(0, 4) + " " + nationalId.substring(4)))
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.national-id-taken");
	}

	@Test
	void rejectsInvalidInput() {
		api.post("/api/v1/admin/drivers", api.adminToken, Map.of("phone", "123", "fullName", "X"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("phone.invalid");
		api.post("/api/v1/admin/drivers", api.adminToken,
				Map.of("phone", SamplePhones.next(), "fullName", "X", "nationalId", "12"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("driver.national-id-invalid");
		api.post("/api/v1/admin/drivers", api.adminToken, Map.of("phone", SamplePhones.next()))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("validation");
	}

	@Test
	void listFiltersByStatusAndPaginates() {
		Registered pending = api.register();
		Registered rejected = api.register();
		api.admin(rejected.driverId(), "reject", Map.of("reason", DriverApi.REASON)).expectStatus().isOk();

		JsonNode page = api.get("/api/v1/admin/drivers?status=REJECTED&size=100", api.adminToken)
			.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
		List<String> ids = page.get("content").valueStream().map(n -> n.get("id").asString()).toList();
		assertThat(ids).contains(rejected.driverId().toString()).doesNotContain(pending.driverId().toString());
		assertThat(page.get("content").valueStream().map(n -> n.get("status").asString())).containsOnly("REJECTED");

		api.get("/api/v1/admin/drivers?size=1", api.adminToken)
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.content.length()").isEqualTo(1)
			.jsonPath("$.size").isEqualTo(1);

		api.get("/api/v1/admin/drivers?status=NOPE", api.adminToken).expectStatus().isBadRequest();
	}

	@Test
	void rejectionNeedsAReasonIsFinalAndIsAuditedWithoutTheReason() {
		Registered driver = api.register();

		api.admin(driver.driverId(), "reject", Map.of("reason", "short"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("driver.reason-required");
		api.admin(driver.driverId(), "reject", Map.of("reason", DriverApi.REASON))
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("REJECTED");
		api.admin(driver.driverId(), "training", null)
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.invalid-transition")
			.jsonPath("$.detail").isEqualTo("Cannot change status from REJECTED to TRAINING.");

		JsonNode history = api.detail(driver.driverId()).get("history");
		assertThat(history.get(1).get("reason").asString()).isEqualTo(DriverApi.REASON);
		assertThat(history.get(1).get("actorUserId").asString()).isEqualTo(api.adminId.toString());
		String details = jdbc.queryForObject(
				"SELECT details::text FROM audit.audit_logs WHERE target_id = ? AND action = 'driver.rejected'",
				String.class, driver.driverId().toString());
		assertThat(details).contains("\"to\": \"REJECTED\"").doesNotContain("complaint");
	}

	@Test
	void trainingRequiresDocuments() {
		Registered driver = api.register();
		api.post("/api/v1/drivers/me/submit", driver.token(), null).expectStatus().isEqualTo(409);

		api.admin(driver.driverId(), "training", null)
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.invalid-transition");
	}

	@Test
	void unknownDriverIs404() {
		api.get("/api/v1/admin/drivers/" + UUID.randomUUID(), api.adminToken)
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("driver.not-found");
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER" })
	void staffCanViewDrivers(Role role) {
		Registered driver = api.register();
		String token = api.tokenFor(role);

		api.get("/api/v1/admin/drivers", token).expectStatus().isOk();
		api.get("/api/v1/admin/drivers/" + driver.driverId(), token).expectStatus().isOk();
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "PASSENGER", "DRIVER", "CORPORATE_ADMIN", "CORPORATE_BOOKER" })
	void nonStaffCannotViewDrivers(Role role) {
		api.get("/api/v1/admin/drivers", api.tokenFor(role))
			.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("auth.forbidden");
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "DISPATCHER", "SUPPORT", "SAFETY_OFFICER" })
	void onlyAdminsRegisterAndRunOnboarding(Role role) {
		Registered driver = api.register();
		String token = api.tokenFor(role);

		api.post("/api/v1/admin/drivers", token, Map.of("phone", SamplePhones.next(), "fullName", "X"))
			.expectStatus().isForbidden();
		for (String action : List.of("training", "approve", "reject", "reinstate")) {
			api.post("/api/v1/admin/drivers/" + driver.driverId() + "/" + action, token,
					Map.of("reason", DriverApi.REASON)).expectStatus().isForbidden();
		}
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "DISPATCHER", "SUPPORT", "PASSENGER" })
	void onlyAdminsAndSafetyOfficersSuspend(Role role) {
		Registered driver = api.register();

		api.post("/api/v1/admin/drivers/" + driver.driverId() + "/suspend", api.tokenFor(role),
				Map.of("reason", DriverApi.REASON)).expectStatus().isForbidden();
	}

	private List<String> auditActions(String targetId) {
		return jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ? ORDER BY occurred_at",
				String.class, targetId);
	}
}

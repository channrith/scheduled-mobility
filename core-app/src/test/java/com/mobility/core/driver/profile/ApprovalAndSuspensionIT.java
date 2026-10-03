package com.mobility.core.driver.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.CapturedEvents;
import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.DriverEvents.DriverApproved;
import com.mobility.core.driver.DriverEvents.DriverReinstated;
import com.mobility.core.driver.DriverEvents.DriverSuspended;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ApprovalAndSuspensionIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	CapturedEvents events;

	DriverApi api;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
	}

	@Test
	void approvalRequiresVehicleAndItsDocuments() {
		Registered driver = api.register();
		List<UUID> docs = api.uploadPersonalDocuments(driver);
		api.post("/api/v1/drivers/me/submit", driver.token(), null).expectStatus().isOk();
		docs.forEach(doc -> api.approveDocument(driver.driverId(), doc));
		api.admin(driver.driverId(), "training", null).expectStatus().isOk();

		api.admin(driver.driverId(), "approve", null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("driver.vehicle-required");

		UUID vehicle = api.registerVehicle("TUKTUK_RICKSHAW");
		api.assignVehicle(driver.driverId(), vehicle);
		api.admin(driver.driverId(), "approve", null)
			.expectStatus().isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.documents-missing")
			.jsonPath("$.missing[0]").isEqualTo("VEHICLE_INSURANCE")
			.jsonPath("$.missing[1]").isEqualTo("VEHICLE_REGISTRATION");

		api.approveDocument(driver.driverId(),
				api.uploadOk(driver.token(), "VEHICLE_REGISTRATION", SampleFiles.pdf(), DriverApi.inOneYear(), vehicle));
		api.approveDocument(driver.driverId(),
				api.uploadOk(driver.token(), "VEHICLE_INSURANCE", SampleFiles.pdf(), DriverApi.inOneYear(), vehicle));

		api.admin(driver.driverId(), "approve", null)
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("APPROVED");
		assertThat(events.ofType(DriverApproved.class)).contains(new DriverApproved(driver.driverId(), driver.userId()));
		assertThat(history(driver.driverId())).containsExactly("PENDING", "DOCS_SUBMITTED", "TRAINING", "APPROVED");
	}

	@Test
	void suspensionRecordsReasonNoticeAndActorAndIsAudited() {
		Registered driver = api.approvedDriver();
		UUID safetyOfficer = UUID.randomUUID();
		Instant notice = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

		JsonNode body = api.post("/api/v1/admin/drivers/" + driver.driverId() + "/suspend",
				SignedTokens.forUser(issuer, safetyOfficer, Role.SAFETY_OFFICER),
				Map.of("reason", DriverApi.REASON, "noticeAt", notice.toString()))
			.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(body.get("status").asString()).isEqualTo("SUSPENDED");
		assertThat(body.get("suspension").get("reason").asString()).isEqualTo(DriverApi.REASON);
		assertThat(Instant.parse(body.get("suspension").get("noticeAt").asString())).isEqualTo(notice);
		assertThat(body.get("suspension").get("suspendedBy").asString()).isEqualTo(safetyOfficer.toString());

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT suspension_reason, suspension_notice_at, suspended_by FROM driver.drivers WHERE id = ?",
				driver.driverId());
		assertThat(row.get("suspension_reason")).isEqualTo(DriverApi.REASON);
		assertThat(((java.sql.Timestamp) row.get("suspension_notice_at")).toInstant()).isEqualTo(notice);
		assertThat(row.get("suspended_by")).isEqualTo(safetyOfficer);

		Map<String, Object> historyRow = jdbc.queryForMap("""
				SELECT reason, notice_at, actor_user_id FROM driver.driver_status_history
				WHERE driver_id = ? AND to_status = 'SUSPENDED'""", driver.driverId());
		assertThat(historyRow.get("reason")).isEqualTo(DriverApi.REASON);
		assertThat(historyRow.get("actor_user_id")).isEqualTo(safetyOfficer);

		Map<String, Object> audit = jdbc.queryForMap("""
				SELECT actor_user_id, details::text AS details FROM audit.audit_logs
				WHERE target_id = ? AND action = 'driver.suspended'""", driver.driverId().toString());
		assertThat(audit.get("actor_user_id")).isEqualTo(safetyOfficer);
		assertThat((String) audit.get("details")).contains(notice.toString()).doesNotContain("complaint");

		assertThat(events.ofType(DriverSuspended.class))
			.contains(new DriverSuspended(driver.driverId(), driver.userId(), notice));
	}

	@Test
	void noticeDefaultsToNowAndCannotBeInTheFuture() {
		Registered driver = api.approvedDriver();

		api.admin(driver.driverId(), "suspend",
				Map.of("reason", DriverApi.REASON, "noticeAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("driver.notice-in-future");

		Instant before = Instant.now().minusSeconds(1);
		JsonNode body = api.admin(driver.driverId(), "suspend", Map.of("reason", DriverApi.REASON))
			.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
		assertThat(Instant.parse(body.get("suspension").get("noticeAt").asString())).isAfter(before);
	}

	@Test
	void suspensionWithoutAProperReasonIsRejectedAndChangesNothing() {
		Registered driver = api.approvedDriver();

		api.admin(driver.driverId(), "suspend", Map.of()).expectStatus().isBadRequest()
			.expectBody().jsonPath("$.code").isEqualTo("driver.reason-required");
		api.admin(driver.driverId(), "suspend", Map.of("reason", "bad")).expectStatus().isBadRequest();

		assertThat(api.detail(driver.driverId()).get("status").asString()).isEqualTo("APPROVED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_logs WHERE target_id = ? AND action = 'driver.suspended'",
				Integer.class, driver.driverId().toString())).isZero();
	}

	@Test
	void onlyApprovedDriversCanBeSuspended() {
		Registered pending = api.register();

		api.admin(pending.driverId(), "suspend", Map.of("reason", DriverApi.REASON))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("driver.invalid-transition");
	}

	@Test
	void reinstatementIsAdminOnlyNeedsAReasonAndClearsTheSuspension() {
		Registered driver = api.approvedDriver();
		api.admin(driver.driverId(), "suspend", Map.of("reason", DriverApi.REASON)).expectStatus().isOk();

		api.post("/api/v1/admin/drivers/" + driver.driverId() + "/reinstate", api.tokenFor(Role.SAFETY_OFFICER),
				Map.of("reason", "Investigation closed, no fault")).expectStatus().isForbidden();
		api.admin(driver.driverId(), "reinstate", Map.of("reason", "")).expectStatus().isBadRequest();

		api.admin(driver.driverId(), "reinstate", Map.of("reason", "Investigation closed, no fault"))
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("APPROVED")
			.jsonPath("$.suspension").isEmpty();

		assertThat(history(driver.driverId())).endsWith("APPROVED", "SUSPENDED", "APPROVED");
		assertThat(events.ofType(DriverReinstated.class)).contains(new DriverReinstated(driver.driverId(), driver.userId()));
	}

	@Test
	void reinstatementIsBlockedWhileDocumentsAreExpired() {
		Registered driver = api.approvedDriver();
		api.admin(driver.driverId(), "suspend", Map.of("reason", DriverApi.REASON)).expectStatus().isOk();
		jdbc.update("UPDATE driver.driver_documents SET expires_on = current_date - 1 WHERE driver_id = ? AND type = 'DRIVING_LICENSE'",
				driver.driverId());

		api.admin(driver.driverId(), "reinstate", Map.of("reason", "Investigation closed, no fault"))
			.expectStatus().isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.documents-expired")
			.jsonPath("$.expired[0]").isEqualTo("DRIVING_LICENSE");
	}

	@Test
	void databaseRefusesASuspensionWithoutNotice() {
		Registered driver = api.approvedDriver();

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE driver.drivers SET status = 'SUSPENDED', suspension_reason = 'x', suspended_at = now() WHERE id = ?",
				driver.driverId())).isInstanceOf(DataIntegrityViolationException.class);
	}

	private List<String> history(UUID driverId) {
		return jdbc.queryForList(
				"SELECT to_status FROM driver.driver_status_history WHERE driver_id = ? ORDER BY occurred_at",
				String.class, driverId);
	}
}

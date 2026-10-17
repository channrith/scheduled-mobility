package com.mobility.core.corporate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.corporate.CorporateApi.Company;
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

/** A corporate admin manages only their own company and must never see another company's data. */
@IntegrationTest
class CompanyScopeIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	CorporateApi api;

	Company a;

	Company b;

	@BeforeEach
	void setUp() {
		api = new CorporateApi(client, issuer);
		a = api.company();
		b = api.company();
	}

	@Test
	void adminManagesOwnCompany() {
		JsonNode costCenter = api.addCostCenter(a, "fin");
		assertThat(costCenter.get("code").asString()).isEqualTo("FIN");

		JsonNode member = api.addMember(a, "CORPORATE_BOOKER", Map.of("costCenterId", costCenter.get("id").asString(),
				"employeeRef", "E-17", "policy", Map.of("allowedVehicleClasses", List.of("CAR", "SUV"),
						"allowedHours", List.of(Map.of("days", List.of("MONDAY", "FRIDAY"), "from", "07:00", "to", "20:00")),
						"maxFare", Map.of("amountMinor", 2500, "currency", "USD"))));

		assertThat(member.at("/costCenter/code").asString()).isEqualTo("FIN");
		assertThat(member.at("/policy/allowedVehicleClasses").toString()).contains("CAR", "SUV");
		assertThat(member.at("/policy/allowedHours/0/from").asString()).startsWith("07:00");
		assertThat(member.at("/policy/maxFare/amountMinor").asLong()).isEqualTo(2500);
		assertThat(api.get("/api/v1/corporates/" + a.id() + "/members", a.adminToken()).expectStatus().isOk()
			.expectBody(JsonNode.class).returnResult().getResponseBody().valueStream()
			.map(m -> m.get("role").asString())).containsExactlyInAnyOrder("CORPORATE_ADMIN", "CORPORATE_BOOKER");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_logs WHERE action = 'corporate.member.added' AND details->>'corporateId' = ?",
				Integer.class, a.id().toString())).isEqualTo(2);
	}

	@Test
	void anotherCompanysUrlsAreForbidden() {
		String other = "/api/v1/corporates/" + b.id();

		api.get(other, a.adminToken()).expectStatus().isForbidden();
		api.get(other + "/members", a.adminToken()).expectStatus().isForbidden();
		api.get(other + "/cost-centers", a.adminToken()).expectStatus().isForbidden();
		api.post(other + "/members", a.adminToken(), Map.of("phone", "012345678", "fullName", "X", "role", "RIDER"))
			.expectStatus().isForbidden();
		api.post(other + "/cost-centers", a.adminToken(), Map.of("code", "X", "name", "X")).expectStatus().isForbidden();
	}

	@Test
	void anotherCompanysIdsAreNotFoundUnderOwnUrl() {
		JsonNode bMember = api.addMember(b, "RIDER", Map.of());
		JsonNode bCostCenter = api.addCostCenter(b, "HR");
		String own = "/api/v1/corporates/" + a.id();

		api.put(own + "/members/" + bMember.get("id").asString(), a.adminToken(),
				Map.of("version", 0, "role", "CORPORATE_BOOKER"))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("corporate.member-not-found");
		api.delete(own + "/members/" + bMember.get("id").asString(), a.adminToken()).expectStatus().isNotFound();
		api.put(own + "/cost-centers/" + bCostCenter.get("id").asString(), a.adminToken(),
				Map.of("version", 0, "name", "Stolen", "active", false)).expectStatus().isNotFound();
		api.post(own + "/members", a.adminToken(), Map.of("phone", com.mobility.core.SamplePhones.next(), "fullName", "X",
				"role", "RIDER", "costCenterId", bCostCenter.get("id").asString()))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("corporate.cost-center-not-found");

		// B's data is untouched and never listed for A.
		assertThat(jdbc.queryForObject("SELECT status FROM corporate.corporate_members WHERE id = ?::uuid", String.class,
				bMember.get("id").asString())).isEqualTo("ACTIVE");
		String aMembers = api.get(own + "/members", a.adminToken()).expectStatus().isOk().expectBody(String.class)
			.returnResult().getResponseBody();
		assertThat(aMembers).doesNotContain(bMember.get("id").asString());
		assertThat(api.get(own + "/cost-centers", a.adminToken()).expectBody(String.class).returnResult().getResponseBody())
			.doesNotContain(bCostCenter.get("id").asString());
	}

	@Test
	void databaseRejectsACostCentreFromAnotherCompany() {
		JsonNode bCostCenter = api.addCostCenter(b, "LEGAL");

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO corporate.corporate_members (id, account_id, user_id, role, cost_center_id)
				VALUES (?, ?, ?, 'RIDER', ?::uuid)""", UUID.randomUUID(), a.id(), UUID.randomUUID(),
				bCostCenter.get("id").asString())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void bookersAndOtherCustomersCannotManageTheCompany() {
		String booker = SignedTokens.corporate(issuer, a.id(), Role.CORPORATE_BOOKER);

		api.get("/api/v1/corporates/" + a.id() + "/members", booker).expectStatus().isForbidden();
		api.post("/api/v1/corporates/" + a.id() + "/cost-centers", booker, Map.of("code", "X", "name", "X"))
			.expectStatus().isForbidden();
		api.get("/api/v1/corporates/" + a.id(), api.tokenFor(Role.PASSENGER)).expectStatus().isForbidden();
	}

	@Test
	void staffCanReadButOnlyAdminsChange() {
		api.get("/api/v1/corporates/" + a.id() + "/members", api.tokenFor(Role.SUPPORT)).expectStatus().isOk();
		api.post("/api/v1/corporates/" + a.id() + "/cost-centers", api.tokenFor(Role.SUPPORT), Map.of("code", "X", "name", "X"))
			.expectStatus().isForbidden();
		api.post("/api/v1/corporates/" + a.id() + "/cost-centers", api.adminToken, Map.of("code", "PLAT", "name", "By platform"))
			.expectStatus().isCreated();
	}

	@Test
	void membershipControlsTheIdentityRole() {
		JsonNode booker = api.addMember(a, "CORPORATE_BOOKER", Map.of());
		String userId = booker.get("userId").asString();
		String path = "/api/v1/corporates/" + a.id() + "/members/" + booker.get("id").asString();

		assertThat(scopedRoles(userId)).containsExactly("CORPORATE_BOOKER");
		api.put(path, a.adminToken(), Map.of("version", 0, "role", "RIDER")).expectStatus().isOk();
		assertThat(scopedRoles(userId)).isEmpty();
		assertThat(jdbc.queryForList("SELECT role FROM identity.user_roles WHERE user_id = ?::uuid", String.class, userId))
			.contains("PASSENGER");

		api.delete(path, a.adminToken()).expectStatus().isNoContent();
		JsonNode readded = api.addMember(a, "CORPORATE_BOOKER", Map.of("phone",
				jdbc.queryForObject("SELECT phone_e164 FROM identity.users WHERE id = ?::uuid", String.class, userId)));
		assertThat(readded.get("id").asString()).isEqualTo(booker.get("id").asString());
		assertThat(scopedRoles(userId)).containsExactly("CORPORATE_BOOKER");
	}

	@Test
	void theLastAdminCannotBeRemovedOrDemoted() {
		JsonNode admin = api.get("/api/v1/corporates/" + a.id() + "/members", a.adminToken()).expectBody(JsonNode.class)
			.returnResult().getResponseBody().get(0);
		String path = "/api/v1/corporates/" + a.id() + "/members/" + admin.get("id").asString();

		api.delete(path, a.adminToken()).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("corporate.last-admin");
		api.put(path, a.adminToken(), Map.of("version", admin.get("version").asLong(), "role", "CORPORATE_BOOKER"))
			.expectStatus().isEqualTo(409);

		api.addMember(a, "CORPORATE_ADMIN", Map.of());
		api.delete(path, a.adminToken()).expectStatus().isNoContent();
	}

	@Test
	void duplicateMembersAndInvalidPoliciesAreRejected() {
		String phone = com.mobility.core.SamplePhones.next();
		api.addMember(a, "RIDER", Map.of("phone", phone));

		api.post("/api/v1/corporates/" + a.id() + "/members", a.adminToken(), Map.of("phone", phone, "fullName", "X", "role", "RIDER"))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("corporate.member-exists");
		api.post("/api/v1/corporates/" + a.id() + "/members", a.adminToken(), Map.of("phone", com.mobility.core.SamplePhones.next(),
				"fullName", "X", "role", "RIDER", "policy", Map.of("maxFare", Map.of("amountMinor", 0, "currency", "USD"))))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("corporate.policy-invalid");
		api.addCostCenter(a, "DUP");
		api.post("/api/v1/corporates/" + a.id() + "/cost-centers", a.adminToken(), Map.of("code", "dup", "name", "Again"))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("corporate.cost-center-code-taken");
	}

	private List<String> scopedRoles(String userId) {
		return jdbc.queryForList("SELECT role FROM identity.user_roles WHERE user_id = ?::uuid AND scope_id = ?", String.class,
				userId, a.id());
	}
}

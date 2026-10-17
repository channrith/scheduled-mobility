package com.mobility.core.corporate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.AuthTestClient;
import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.corporate.CorporateApi.Company;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AdminCorporateIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthTestClient auth;

	CorporateApi api;

	@BeforeEach
	void setUp() {
		api = new CorporateApi(client, issuer);
	}

	@Test
	void createsAnAccountWithDefaultsAndAudits() {
		JsonNode account = api.post("/api/v1/admin/corporates", api.adminToken, Map.of("legalName", "  ACME Bank Plc. "))
			.expectStatus().isCreated().expectHeader().exists("Location")
			.expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(account.get("legalName").asString()).isEqualTo("ACME Bank Plc.");
		assertThat(account.get("currency").asString()).isEqualTo("USD");
		assertThat(account.get("paymentTermsDays").asInt()).isEqualTo(30);
		assertThat(account.get("serviceFeePct").decimalValue()).isEqualByComparingTo("8.00");
		assertThat(account.get("creditLimitMinor").asLong()).isZero();
		assertThat(account.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(audits(account.get("id").asString())).containsExactly("corporate.account.created");
	}

	@Test
	void validatesFieldsAndKeepsTaxIdUnique() {
		String tin = "K00" + (100_000 + new java.util.Random().nextInt(899_999));
		api.post("/api/v1/admin/corporates", api.adminToken, Map.of("legalName", "First", "taxId", tin.toLowerCase()))
			.expectStatus().isCreated().expectBody().jsonPath("$.taxId").isEqualTo(tin);
		api.post("/api/v1/admin/corporates", api.adminToken, Map.of("legalName", "Second", "taxId", " " + tin + " "))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("corporate.tax-id-taken");
		api.post("/api/v1/admin/corporates", api.adminToken, Map.of("legalName", "X", "currency", "EUR"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("currency");
		api.post("/api/v1/admin/corporates", api.adminToken, Map.of("legalName", "X", "serviceFeePct", 120))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("serviceFeePct");
		api.post("/api/v1/admin/corporates", api.adminToken, Map.of("legalName", "X", "paymentTermsDays", 200))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("paymentTermsDays");
	}

	@Test
	void editsWithOptimisticLocking() {
		Company company = api.company();
		Map<String, Object> edit = Map.of("version", 0, "legalName", "Renamed Ltd", "currency", "KHR",
				"creditLimitMinor", 4_000_000, "serviceFeePct", "7.50", "paymentTermsDays", 15);

		api.put("/api/v1/admin/corporates/" + company.id(), api.adminToken, edit).expectStatus().isOk()
			.expectBody().jsonPath("$.currency").isEqualTo("KHR").jsonPath("$.serviceFeePct").isEqualTo(7.5)
			.jsonPath("$.version").isEqualTo(1);
		api.put("/api/v1/admin/corporates/" + company.id(), api.adminToken, edit)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("concurrent-update");
	}

	@Test
	void listSearchesByNameOrExactTaxIdAndFiltersByStatus() {
		String unique = "Zeta" + UUID.randomUUID().toString().substring(0, 6);
		String tin = "L00" + (100_000 + new java.util.Random().nextInt(899_999));
		Company company = api.company(Map.of("legalName", unique + " Hotel Group", "taxId", tin));

		assertThat(ids("/api/v1/admin/corporates?q=" + unique.toLowerCase())).containsExactly(company.id().toString());
		assertThat(ids("/api/v1/admin/corporates?q=" + tin)).containsExactly(company.id().toString());
		assertThat(ids("/api/v1/admin/corporates?size=100&status=SUSPENDED&q=" + unique)).isEmpty();
	}

	@Test
	void addAdminCreatesOrReusesTheUserAndGrantsTheScopedRole() {
		Company company = api.company();
		String phone = SamplePhones.next();
		auth.login(phone);
		UUID existing = jdbc.queryForObject("SELECT id FROM identity.users WHERE phone_e164 = ?", UUID.class, phone);

		JsonNode member = api.post("/api/v1/admin/corporates/" + company.id() + "/admins", api.adminToken,
				Map.of("phone", phone, "fullName", "Second Admin")).expectStatus().isOk()
			.expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(member.get("userId").asString()).isEqualTo(existing.toString());
		assertThat(member.get("role").asString()).isEqualTo("CORPORATE_ADMIN");
		assertThat(jdbc.queryForList("SELECT role || ':' || coalesce(scope_id::text, '') FROM identity.user_roles WHERE user_id = ?",
				String.class, existing)).containsExactlyInAnyOrder("PASSENGER:", "CORPORATE_ADMIN:" + company.id());
	}

	@Test
	void addAdminPromotesAnExistingMember() {
		Company company = api.company();
		String phone = SamplePhones.next();
		JsonNode booker = api.addMember(company, "CORPORATE_BOOKER", Map.of("phone", phone));

		api.post("/api/v1/admin/corporates/" + company.id() + "/admins", api.adminToken,
				Map.of("phone", phone, "fullName", "Promoted")).expectStatus().isOk()
			.expectBody().jsonPath("$.id").isEqualTo(booker.get("id").asString())
			.jsonPath("$.role").isEqualTo("CORPORATE_ADMIN");
		assertThat(jdbc.queryForList("SELECT role FROM identity.user_roles WHERE user_id = ?::uuid AND scope_id = ?",
				String.class, booker.get("userId").asString(), company.id())).containsExactly("CORPORATE_ADMIN");
	}

	@Test
	void suspendAndReactivateNeedReasonsAndBlockCompanyChanges() {
		Company company = api.company();
		String base = "/api/v1/admin/corporates/" + company.id();

		api.post(base + "/suspend", api.adminToken, Map.of("reason", "late"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("corporate.reason-required");
		api.post(base + "/suspend", api.adminToken, Map.of("reason", CorporateApi.REASON))
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUSPENDED")
			.jsonPath("$.suspension.reason").isEqualTo(CorporateApi.REASON);
		api.post(base + "/suspend", api.adminToken, Map.of("reason", CorporateApi.REASON))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("corporate.invalid-transition");

		api.post("/api/v1/corporates/" + company.id() + "/cost-centers", company.adminToken(), Map.of("code", "OPS", "name", "Ops"))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("corporate.suspended");
		api.get("/api/v1/corporates/" + company.id() + "/members", company.adminToken()).expectStatus().isOk();

		api.post(base + "/reactivate", api.adminToken, Map.of("reason", "Invoices settled in full"))
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE").jsonPath("$.suspension").isEmpty();

		JsonNode detail = api.get(base, api.adminToken).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
		assertThat(detail.get("history").valueStream().map(h -> h.get("to").asString()).toList())
			.containsExactly("ACTIVE", "SUSPENDED", "ACTIVE");
		assertThat(detail.get("history").get(1).get("reason").asString()).isEqualTo(CorporateApi.REASON);
		assertThat(detail.get("activeMembers").asLong()).isEqualTo(1);
		String auditDetails = jdbc.queryForObject("SELECT details::text FROM audit.audit_logs WHERE target_id = ? AND action = 'corporate.account.suspended'",
				String.class, company.id().toString());
		assertThat(auditDetails).doesNotContain("Unpaid");
	}

	@Test
	void permissions() {
		Company company = api.company();
		String base = "/api/v1/admin/corporates";

		api.get(base, api.tokenFor(Role.SUPPORT)).expectStatus().isOk();
		api.get(base + "/" + company.id(), api.tokenFor(Role.DISPATCHER)).expectStatus().isOk();
		api.post(base, api.tokenFor(Role.SUPPORT), Map.of("legalName", "X")).expectStatus().isForbidden();
		api.get(base, company.adminToken()).expectStatus().isForbidden();
		api.post(base + "/" + company.id() + "/suspend", company.adminToken(), Map.of("reason", CorporateApi.REASON))
			.expectStatus().isForbidden();
		api.get(base, api.tokenFor(Role.PASSENGER)).expectStatus().isForbidden();
	}

	private List<String> ids(String uri) {
		return api.get(uri, api.adminToken).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody()
			.get("content").valueStream().map(n -> n.get("id").asString()).toList();
	}

	private List<String> audits(String targetId) {
		return jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ? ORDER BY occurred_at", String.class, targetId);
	}
}

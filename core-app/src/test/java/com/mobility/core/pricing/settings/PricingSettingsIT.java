package com.mobility.core.pricing.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.pricing.PricingApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PricingSettingsIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	PricingApi api;

	@BeforeEach
	void setUp() {
		api = new PricingApi(client, issuer);
	}

	@Test
	void changesAreKeptWithWhoAndWhenAndAudited() {
		JsonNode fuel = api.json(api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 1.08)));
		JsonNode rate = api.json(api.put("/api/v1/admin/pricing/settings/exchange-rate", api.adminToken, Map.of("value", 4050)));

		assertThat(fuel.get("value").decimalValue()).isEqualByComparingTo("1.08");
		assertThat(fuel.get("changedBy").get("id").asString()).isEqualTo(api.adminId.toString());
		assertThat(fuel.get("configuredDefault").asBoolean()).isFalse();
		assertThat(rate.get("value").decimalValue()).isEqualByComparingTo("4050");

		JsonNode settings = api.json(api.get("/api/v1/admin/pricing/settings", api.tokenFor(Role.SUPPORT)));
		assertThat(settings.get("fuelIndex").get("value").decimalValue()).isEqualByComparingTo("1.08");
		assertThat(settings.get("usdKhrRate").get("value").decimalValue()).isEqualByComparingTo("4050");
		assertThat(settings.get("fuelIndexHistory").get(0).get("changedAt").asString()).isNotEmpty();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit.audit_logs WHERE action = 'pricing.setting.changed' AND actor_user_id = ?""",
				Integer.class, api.adminId)).isEqualTo(2);
	}

	@Test
	void theNewestChangeIsCurrent() {
		api.json(api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 1.1)));
		api.json(api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 0.95)));

		JsonNode settings = api.json(api.get("/api/v1/admin/pricing/settings", api.adminToken));
		assertThat(settings.get("fuelIndex").get("value").decimalValue()).isEqualByComparingTo("0.95");
		assertThat(settings.get("fuelIndexHistory").get(1).get("value").decimalValue()).isEqualByComparingTo("1.1");
	}

	@Test
	void rangesAndRoles() {
		api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 4))
			.expectStatus().isBadRequest().expectBody()
			.jsonPath("$.code").isEqualTo("pricing.setting-invalid")
			.jsonPath("$.setting").isEqualTo("FUEL_INDEX");
		api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 1.0005))
			.expectStatus().isBadRequest();
		api.put("/api/v1/admin/pricing/settings/exchange-rate", api.adminToken, Map.of("value", 41))
			.expectStatus().isBadRequest();
		api.put("/api/v1/admin/pricing/settings/exchange-rate", api.tokenFor(Role.DISPATCHER), Map.of("value", 4100))
			.expectStatus().isForbidden();
	}
}

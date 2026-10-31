package com.mobility.core.pricing.rule;

import static com.mobility.core.pricing.PricingApi.usdRule;
import static com.mobility.core.pricing.PricingApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.corporate.CorporateApi;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.pricing.PricingApi;
import com.mobility.core.pricing.TestCities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PricingRuleIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	PricingApi api;

	String city;

	@BeforeEach
	void setUp() {
		api = new PricingApi(client, issuer);
		city = TestCities.next();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	JsonNode lines(String query) {
		return api.json(api.get("/api/v1/admin/pricing/rules?city=" + city + query, api.tokenFor(Role.DISPATCHER)));
	}

	@Test
	void createsAVersionAndAuditsIt() {
		JsonNode rule = api.createRule(usdRule(city, "CAR"));

		assertThat(rule.get("city").asString()).isEqualTo(city);
		assertThat(rule.get("baseMinor").asLong()).isEqualTo(200);
		assertThat(rule.get("fuelIndexFactor").decimalValue()).isEqualByComparingTo("1.000");
		assertThat(rule.get("active").asBoolean()).isTrue();
		assertThat(rule.get("createdBy").get("id").asString()).isEqualTo(api.adminId.toString());
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class,
				rule.get("id").asString())).containsExactly("pricing.rule.version-created");
	}

	@Test
	void validatesAndOnlyAdminsChangePrices() {
		api.post("/api/v1/admin/pricing/rules", api.adminToken, usdRule("ATLANTIS", "CAR"))
			.expectStatus().isBadRequest().expectBody()
			.jsonPath("$.code").isEqualTo("pricing.invalid-field")
			.jsonPath("$.field").isEqualTo("city");
		api.post("/api/v1/admin/pricing/rules", api.adminToken, with(usdRule(city, "CAR"), "maxSurge", 6))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("maxSurge");
		api.post("/api/v1/admin/pricing/rules", api.adminToken, with(usdRule(city, "CAR"), "currency", "EUR"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("currency");
		api.post("/api/v1/admin/pricing/rules", api.adminToken, with(usdRule(city, "CAR"), "baseMinor", -1))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("baseMinor");
		api.post("/api/v1/admin/pricing/rules", api.adminToken,
				with(usdRule(city, "CAR"), "validFrom", Instant.now().minus(Duration.ofHours(1)).toString()))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("pricing.valid-from-past");
		api.post("/api/v1/admin/pricing/rules", api.tokenFor(Role.DISPATCHER), usdRule(city, "CAR"))
			.expectStatus().isForbidden();
		api.get("/api/v1/admin/pricing/rules", api.tokenFor(Role.SAFETY_OFFICER)).expectStatus().isForbidden();
		api.get("/api/v1/admin/pricing/rules", api.tokenFor(Role.PASSENGER)).expectStatus().isForbidden();
	}

	@Test
	void aNewVersionTakesOverAtItsStartAndTheOldOneStaysAsItWas() {
		JsonNode v1 = api.createRule(usdRule(city, "SUV"));
		Instant start = Instant.now().plus(Duration.ofHours(1));
		JsonNode v2 = api.createRule(with(usdRule(city, "SUV"), "baseMinor", 300, "validFrom", start.toString()));

		JsonNode line = lines("&vehicleClass=SUV").get(0);
		assertThat(line.get("current").get("id").asString()).isEqualTo(v1.get("id").asString());
		assertThat(line.get("upcoming").get(0).get("id").asString()).isEqualTo(v2.get("id").asString());

		clock.advance(Duration.ofHours(2));
		JsonNode later = lines("&vehicleClass=SUV").get(0);
		assertThat(later.get("current").get("id").asString()).isEqualTo(v2.get("id").asString());
		assertThat(later.get("upcoming")).isEmpty();
		// Tokens issued before the clock moved have expired.
		String admin = api.tokenFor(Role.ADMIN);
		assertThat(api.json(api.get("/api/v1/admin/pricing/rules/" + v1.get("id").asString(), admin))
			.get("baseMinor").asLong()).isEqualTo(200);

		JsonNode history = api.json(api.get("/api/v1/admin/pricing/rules/history?city=" + city + "&vehicleClass=SUV", admin));
		assertThat(history.valueStream().map(h -> h.get("period").asString())).containsExactly("CURRENT", "PAST");
		assertThat(history.get(1).get("effectiveUntil").asString()).isEqualTo(v2.get("validFrom").asString());
	}

	@Test
	void timestampsAreReturnedAsStoredEvenWithNanoseconds() {
		// Java on Linux reads the clock in nanoseconds; PostgreSQL keeps microseconds.
		Instant start = Instant.now().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123_456_789);
		JsonNode created = api.createRule(with(usdRule(city, "TUKTUK_RICKSHAW"), "validFrom", start.toString(),
				"validTo", start.plus(Duration.ofDays(1)).toString()));

		JsonNode read = api.json(api.get("/api/v1/admin/pricing/rules/" + created.get("id").asString(), api.adminToken));
		assertThat(created.get("validFrom").asString()).isEqualTo(read.get("validFrom").asString()).endsWith(".123456Z");
		assertThat(created.get("validTo").asString()).isEqualTo(read.get("validTo").asString());
		assertThat(created.get("createdAt").asString()).isEqualTo(read.get("createdAt").asString());
	}

	@Test
	void versionsAreInsertOnlyInTheDatabase() {
		String id = api.createRule(usdRule(city, "MOTO")).get("id").asString();

		assertThatThrownBy(() -> jdbc.update("UPDATE pricing.pricing_rules SET base_minor = 1 WHERE id = ?::uuid", id))
			.hasMessageContaining("insert-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM pricing.pricing_rules WHERE id = ?::uuid", id))
			.hasMessageContaining("insert-only");
	}

	@Test
	void corporateOverridesAreTheirOwnLines() {
		UUID company = new CorporateApi(client, issuer).company().id();
		api.createRule(usdRule(city, "CAR"));
		JsonNode override = api.createRule(with(usdRule(city, "CAR"), "corporateId", company.toString(), "baseMinor", 150));

		assertThat(lines("").valueStream().map(l -> l.get("current").get("baseMinor").asLong())).containsExactly(200L);
		JsonNode overrides = lines("&corporateId=" + company);
		assertThat(overrides).hasSize(1);
		assertThat(overrides.get(0).get("current").get("id").asString()).isEqualTo(override.get("id").asString());

		api.post("/api/v1/admin/pricing/rules", api.adminToken,
				with(usdRule(city, "CAR"), "corporateId", UUID.randomUUID().toString()))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("pricing.corporate-not-found");
	}

	@Test
	void retiringALineLeavesItWithoutAPrice() {
		api.createRule(usdRule(city, "VAN"));

		JsonNode retired = api.json(api.post("/api/v1/admin/pricing/rules/retire", api.adminToken,
				java.util.Map.of("city", city, "vehicleClass", "VAN")));

		assertThat(retired.get("active").asBoolean()).isFalse();
		JsonNode line = lines("&vehicleClass=VAN").get(0);
		assertThat(line.get("current").isNull()).isTrue();
		assertThat(line.get("latest").get("active").asBoolean()).isFalse();
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class,
				retired.get("id").asString())).containsExactly("pricing.rule.retired");
		api.post("/api/v1/admin/pricing/rules/retire", api.adminToken, java.util.Map.of("city", city, "vehicleClass", "MOTO"))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("pricing.rule-not-found");
	}
}

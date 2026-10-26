package com.mobility.core.pricing.zone;

import static com.mobility.core.pricing.PricingApi.square;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.pricing.PricingApi;
import com.mobility.core.pricing.TestCities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ZoneIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ZoneService zones;

	PricingApi api;

	String city;

	double[] spot;

	@BeforeEach
	void setUp() {
		api = new PricingApi(client, issuer);
		city = TestCities.next();
		spot = PricingApi.nextSpot();
	}

	@Test
	void createsAZoneThatContainsItsInsideAndIsAudited() {
		JsonNode airport = api.createZone(city, "AIRPORT", square(spot[0], spot[1], 2), 1000L);

		assertThat(airport.get("polygon")).hasSize(4);
		assertThat(airport.get("surchargeMinor").asLong()).isEqualTo(1000);
		assertThat(airport.get("active").asBoolean()).isTrue();
		assertThat(zones.zonesAt(new LatLng(spot[0], spot[1]))).extracting(ZoneService.ZoneHit::type).contains(ZoneType.AIRPORT);
		assertThat(zones.zonesAt(new LatLng(spot[0] + 0.05, spot[1]))).noneMatch(h -> h.city().equals(city));
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ?", String.class,
				airport.get("id").asString())).containsExactly("pricing.zone.created");
	}

	@Test
	void editsWithVersionAndDeactivates() {
		JsonNode zone = api.createZone(city, "CITY", square(spot[0], spot[1], 4), null);
		Map<String, Object> body = new HashMap<>(Map.of("version", zone.get("version").asLong(), "city", city, "type", "CITY",
				"nameKm", "តំបន់ក្រុង", "polygon", square(spot[0], spot[1], 6), "active", false));

		JsonNode edited = api.json(api.put("/api/v1/admin/pricing/zones/" + zone.get("id").asString(), api.adminToken, body));

		assertThat(edited.get("active").asBoolean()).isFalse();
		assertThat(edited.get("nameKm").asString()).isEqualTo("តំបន់ក្រុង");
		assertThat(zones.zonesAt(new LatLng(spot[0], spot[1]))).noneMatch(h -> h.city().equals(city));
		api.put("/api/v1/admin/pricing/zones/" + zone.get("id").asString(), api.adminToken, body)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("concurrent-update");
	}

	@Test
	void rejectsBadShapesAndMissingSurcharge() {
		List<Map<String, Object>> bowTie = List.of(Map.of("latitude", spot[0], "longitude", spot[1]),
				Map.of("latitude", spot[0] + 0.01, "longitude", spot[1] + 0.01),
				Map.of("latitude", spot[0], "longitude", spot[1] + 0.01),
				Map.of("latitude", spot[0] + 0.01, "longitude", spot[1]));
		zonePost(Map.of("city", city, "type", "CITY", "nameEn", "x", "polygon", bowTie))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("pricing.zone-invalid");
		zonePost(Map.of("city", city, "type", "CITY", "nameEn", "x", "polygon", square(1.0, 1.0, 2)))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("pricing.zone-invalid");
		zonePost(Map.of("city", city, "type", "CITY", "nameEn", "x", "polygon", square(spot[0], spot[1], 2).subList(0, 2)))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("pricing.zone-invalid");
		zonePost(Map.of("city", city, "type", "AIRPORT", "nameEn", "x", "polygon", square(spot[0], spot[1], 2)))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("surchargeMinor");
		zonePost(Map.of("city", city, "type", "CITY", "polygon", square(spot[0], spot[1], 2)))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.field").isEqualTo("name");
	}

	@Test
	void staffReadAdminsWrite() {
		api.createZone(city, "RESTRICTED", square(spot[0], spot[1], 1), null);

		JsonNode list = api.json(api.get("/api/v1/admin/pricing/zones?city=" + city, api.tokenFor(Role.DISPATCHER)));
		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("surchargeMinor").isNull()).isTrue();
		api.post("/api/v1/admin/pricing/zones", api.tokenFor(Role.DISPATCHER),
				Map.of("city", city, "type", "CITY", "nameEn", "x", "polygon", square(spot[0], spot[1], 2)))
			.expectStatus().isForbidden();
	}

	private RestTestClient.ResponseSpec zonePost(Map<String, Object> body) {
		return api.post("/api/v1/admin/pricing/zones", api.adminToken, body);
	}
}

package com.mobility.core.pricing.quote;

import static com.mobility.core.pricing.PricingApi.square;
import static com.mobility.core.pricing.PricingApi.usdRule;
import static com.mobility.core.pricing.PricingApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.corporate.CorporateApi;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import com.mobility.core.pricing.PricingApi;
import com.mobility.core.pricing.TestCities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class QuoteIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	PricingApi api;

	String city;

	/** City zone centre; the airport zone sits 5 km north, inside it. */
	double lat;

	double lng;

	double airportLat;

	String passenger;

	UUID passengerId;

	@BeforeEach
	void setUp() {
		api = new PricingApi(client, issuer);
		city = TestCities.next();
		double[] spot = PricingApi.nextSpot();
		lat = spot[0];
		lng = spot[1];
		airportLat = lat + 0.045;
		api.createZone(city, "CITY", square(lat, lng, 20), null);
		api.createZone(city, "AIRPORT", square(airportLat, lng, 2), 1000L);
		// Settings are global; pin them so totals do not depend on other tests.
		api.json(api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 1)));
		api.json(api.put("/api/v1/admin/pricing/settings/exchange-rate", api.adminToken, Map.of("value", 4100)));
		passengerId = UUID.randomUUID();
		passenger = SignedTokens.forUser(issuer, passengerId, Role.PASSENGER);
	}

	static Map<String, Object> at(double latitude, double longitude) {
		return Map.of("latitude", latitude, "longitude", longitude);
	}

	Map<String, Object> request(Map<String, Object> pickup, Map<String, Object> dropoff, String vehicleClass) {
		return new HashMap<>(Map.of("pickup", pickup, "dropoff", dropoff, "vehicleClass", vehicleClass));
	}

	RestTestClient.ResponseSpec quote(String token, Map<String, Object> body) {
		return api.post("/api/v1/quotes", token, body);
	}

	JsonNode quoteOk(String token, Map<String, Object> body) {
		return quote(token, body).expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	static long line(JsonNode quote, String type) {
		return quote.get("breakdown").valueStream().filter(l -> l.get("type").asString().equals(type))
			.mapToLong(l -> l.get("amountMinor").asLong()).sum();
	}

	@Test
	void cityToAirportQuoteWithBreakdownKhrEquivalentEtaAndExpiry() {
		api.createRule(usdRule(city, "CAR"));
		// Nanoseconds on purpose (as on Linux): the quote keeps PostgreSQL's microseconds.
		Instant pickupAt = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusNanos(789);
		Map<String, Object> body = request(at(lat - 0.03, lng), at(airportLat, lng), "CAR");
		body.put("pickupAt", pickupAt.toString());

		JsonNode q = quoteOk(passenger, body);

		assertThat(q.get("city").asString()).isEqualTo(city);
		assertThat(q.get("currency").asString()).isEqualTo("USD");
		assertThat(q.get("routingSource").asString()).isEqualTo("STRAIGHT_LINE");
		assertThat(line(q, "BASE")).isEqualTo(200);
		assertThat(line(q, "AIRPORT")).isEqualTo(1000);
		long km10 = q.get("distanceMeters").asLong();
		assertThat(line(q, "DISTANCE")).isEqualTo(Math.round(km10 * 50 / 1000.0));
		long sum = q.get("breakdown").valueStream().mapToLong(l -> l.get("amountMinor").asLong()).sum();
		assertThat(q.get("totalMinor").asLong()).isEqualTo(sum);
		long khr = q.get("equivalent").get("amountMinor").asLong();
		assertThat(q.get("equivalent").get("currency").asString()).isEqualTo("KHR");
		assertThat(khr % 100).isZero();
		assertThat(Math.abs(khr - q.get("totalMinor").asLong() * 41)).isLessThanOrEqualTo(50);
		Instant storedPickup = pickupAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		assertThat(Instant.parse(q.get("pickupAt").asString())).isEqualTo(storedPickup);
		assertThat(Instant.parse(q.get("eta").asString()))
			.isEqualTo(storedPickup.plusSeconds(q.get("durationSeconds").asLong()));
		assertThat(Duration.between(Instant.parse(q.get("createdAt").asString()), Instant.parse(q.get("expiresAt").asString())))
			.isEqualTo(Duration.ofMinutes(15));
		assertThat(q.get("corporateOverride").asBoolean()).isFalse();
		assertThat(q.get("serviceFee").isNull()).isTrue();
	}

	@Test
	void quotesNeverChangeAfterNewPricesOrSettings() {
		api.createRule(usdRule(city, "SUV"));
		JsonNode q = quoteOk(passenger, request(at(lat, lng), at(lat + 0.02, lng + 0.02), "SUV"));
		String id = q.get("id").asString();

		api.createRule(with(usdRule(city, "SUV"), "baseMinor", 900));
		api.json(api.put("/api/v1/admin/pricing/settings/fuel-index", api.adminToken, Map.of("value", 1.5)));

		JsonNode again = api.json(api.get("/api/v1/quotes/" + id, passenger));
		assertThat(again.get("totalMinor").asLong()).isEqualTo(q.get("totalMinor").asLong());
		assertThat(again.get("ruleId").asString()).isEqualTo(q.get("ruleId").asString());
		assertThat(quoteOk(passenger, request(at(lat, lng), at(lat + 0.02, lng + 0.02), "SUV")).get("totalMinor").asLong())
			.isGreaterThan(q.get("totalMinor").asLong());
		assertThatThrownBy(() -> jdbc.update("UPDATE pricing.fare_quotes SET total_minor = 1 WHERE id = ?::uuid", id))
			.hasMessageContaining("insert-only");
	}

	@Test
	void quoteTimestampsAreReturnedAsStored() {
		api.createRule(usdRule(city, "MOTO"));
		Map<String, Object> body = request(at(lat, lng), at(lat + 0.01, lng), "MOTO");
		body.put("pickupAt", Instant.now().plus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
			.plusNanos(987_654_321).toString());

		JsonNode created = quoteOk(passenger, body);
		JsonNode read = api.json(api.get("/api/v1/quotes/" + created.get("id").asString(), passenger));

		for (String field : List.of("pickupAt", "eta", "expiresAt", "createdAt")) {
			assertThat(created.get(field).asString()).as(field).isEqualTo(read.get(field).asString());
		}
		assertThat(created.get("pickupAt").asString()).endsWith(".987654Z");
	}

	@Test
	void theVersionInForceAtPickupTimeIsUsed() {
		api.createRule(usdRule(city, "VAN"));
		Instant nextMonth = Instant.now().plus(Duration.ofDays(30));
		JsonNode future = api.createRule(with(usdRule(city, "VAN"), "baseMinor", 400, "validFrom", nextMonth.toString()));

		Map<String, Object> soon = request(at(lat, lng), at(lat + 0.01, lng), "VAN");
		Map<String, Object> later = request(at(lat, lng), at(lat + 0.01, lng), "VAN");
		later.put("pickupAt", nextMonth.plus(Duration.ofDays(1)).toString());

		assertThat(line(quoteOk(passenger, soon), "BASE")).isEqualTo(200);
		JsonNode q = quoteOk(passenger, later);
		assertThat(line(q, "BASE")).isEqualTo(400);
		assertThat(q.get("ruleId").asString()).isEqualTo(future.get("id").asString());
	}

	@Test
	void corporateOverrideAndServiceFee() {
		CorporateApi corporate = new CorporateApi(client, issuer);
		CorporateApi.Company company = corporate.company(Map.of("serviceFeePct", 10));
		api.createRule(usdRule(city, "CAR"));
		api.createRule(with(usdRule(city, "CAR"), "corporateId", company.id().toString(), "baseMinor", 100));
		Map<String, Object> body = request(at(lat, lng), at(lat + 0.03, lng), "CAR");
		body.put("corporateId", company.id().toString());

		JsonNode q = quoteOk(company.adminToken(), body);

		assertThat(q.get("corporateOverride").asBoolean()).isTrue();
		assertThat(line(q, "BASE")).isEqualTo(100);
		assertThat(q.get("serviceFee").get("pct").decimalValue()).isEqualByComparingTo("10");
		assertThat(q.get("serviceFee").get("amountMinor").asLong()).isEqualTo(Math.round(q.get("totalMinor").asLong() / 10.0));

		CorporateApi.Company other = corporate.company();
		quote(other.adminToken(), body).expectStatus().isForbidden();
		quote(passenger, body).expectStatus().isForbidden();
		// Staff may quote for any company; a company without an override gets the city price.
		Map<String, Object> forOther = request(at(lat, lng), at(lat + 0.03, lng), "CAR");
		forOther.put("corporateId", other.id().toString());
		JsonNode cityPrice = quoteOk(api.tokenFor(Role.DISPATCHER), forOther);
		assertThat(cityPrice.get("corporateOverride").asBoolean()).isFalse();
		assertThat(line(cityPrice, "BASE")).isEqualTo(200);
	}

	@Test
	void serviceAreaRestrictedZonesAndMissingPrices() {
		api.createRule(usdRule(city, "CAR"));
		api.createZone(city, "RESTRICTED", square(lat - 0.05, lng - 0.05, 1), null);

		quote(passenger, request(at(lat - 0.05, lng - 0.05), at(lat, lng), "CAR"))
			.expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("pricing.restricted-zone")
			.jsonPath("$.end").isEqualTo("pickup");
		// From the city to somewhere outside it (another province) is fine...
		quoteOk(passenger, request(at(lat, lng), at(lat + 0.5, lng + 0.5), "CAR"));
		// ...but not with neither end in the service area.
		quote(passenger, request(at(lat + 0.5, lng + 0.5), at(lat + 0.6, lng + 0.5), "CAR"))
			.expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("pricing.outside-service-area");
		quote(passenger, request(at(lat, lng), at(lat + 0.01, lng), "MOTO"))
			.expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("pricing.no-rule")
			.jsonPath("$.detail").isEqualTo("There is no price for this vehicle class at the pickup time.");
		quote(passenger, request(Map.of(), at(lat, lng), "CAR"))
			.expectStatus().isBadRequest().expectBody()
			.jsonPath("$.code").isEqualTo("pricing.location-required")
			.jsonPath("$.end").isEqualTo("pickup");
		Map<String, Object> past = request(at(lat, lng), at(lat + 0.01, lng), "CAR");
		past.put("pickupAt", Instant.now().minus(Duration.ofHours(1)).toString());
		quote(passenger, past).expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("pricing.pickup-time-invalid");
	}

	@Test
	void landmarksCanBeUsedForEitherEnd() {
		api.createRule(usdRule(city, "CAR"));
		String dispatcher = api.tokenFor(Role.DISPATCHER);
		JsonNode place = api.post("/api/v1/admin/places", dispatcher, Map.of("nameEn", "Test pagoda " + city, "latitude",
				airportLat, "longitude", lng, "type", "LANDMARK"))
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();

		JsonNode q = quoteOk(passenger, request(at(lat, lng), Map.of("placeId", place.get("id").asString()), "CAR"));

		assertThat(q.get("dropoff").get("placeId").asString()).isEqualTo(place.get("id").asString());
		assertThat(q.get("dropoff").get("latitude").asDouble()).isEqualTo(airportLat);
		assertThat(line(q, "AIRPORT")).isEqualTo(1000);
		quote(passenger, request(at(lat, lng), Map.of("placeId", UUID.randomUUID().toString()), "CAR"))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("pricing.place-not-found");
	}

	@Test
	void onlyTheRequesterOrStaffCanReadAQuote() {
		api.createRule(usdRule(city, "CAR"));
		String id = quoteOk(passenger, request(at(lat, lng), at(lat + 0.01, lng), "CAR")).get("id").asString();

		api.get("/api/v1/quotes/" + id, SignedTokens.forUser(issuer, UUID.randomUUID(), Role.PASSENGER))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("pricing.quote-not-found");
		api.get("/api/v1/quotes/" + id, api.tokenFor(Role.SUPPORT)).expectStatus().isOk();
		assertThat(jdbc.queryForObject("SELECT requested_by FROM pricing.fare_quotes WHERE id = ?::uuid", UUID.class, id))
			.isEqualTo(passengerId);
		client.post().uri("/api/v1/quotes").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of()).exchange().expectStatus().isUnauthorized();
	}

	@Test
	void khrRulesQuoteInRielWithAUsdEquivalent() {
		api.createRule(with(usdRule(city, "TUKTUK_REMORK"), "currency", "KHR", "baseMinor", 8000, "perKmMinor", 2000,
				"perMinMinor", 0, "minFareMinor", 10000));

		JsonNode q = quoteOk(passenger, request(at(lat, lng), at(lat + 0.03, lng + 0.01), "TUKTUK_REMORK"));

		assertThat(q.get("currency").asString()).isEqualTo("KHR");
		assertThat(q.get("totalMinor").asLong() % 100).isZero();
		assertThat(q.get("equivalent").get("currency").asString()).isEqualTo("USD");
		List<String> types = q.get("breakdown").valueStream().map(l -> l.get("type").asString()).toList();
		assertThat(types).startsWith("BASE", "DISTANCE", "TIME");
	}
}

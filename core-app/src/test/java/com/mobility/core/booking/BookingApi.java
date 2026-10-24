package com.mobility.core.booking;

import static com.mobility.core.pricing.PricingApi.square;
import static com.mobility.core.pricing.PricingApi.usdRule;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import com.mobility.core.pricing.PricingApi;
import com.mobility.core.pricing.TestCities;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * A priced test city (its own city zone, airport zone and CAR price) and helpers for quotes, bookings and trip steps.
 */
public class BookingApi {

	public final RestTestClient client;

	public final AccessTokenIssuer issuer;

	public final PricingApi pricing;

	public final String city;

	public final double lat;

	public final double lng;

	/** Inside the airport zone, 5 km north of the city centre. */
	public final double airportLat;

	public BookingApi(RestTestClient client, AccessTokenIssuer issuer) {
		this.client = client;
		this.issuer = issuer;
		this.pricing = new PricingApi(client, issuer);
		this.city = TestCities.next();
		double[] spot = PricingApi.randomSpot();
		this.lat = spot[0];
		this.lng = spot[1];
		this.airportLat = lat + 0.045;
		pricing.createZone(city, "CITY", square(lat, lng, 20), null);
		pricing.createZone(city, "AIRPORT", square(airportLat, lng, 2), 1000L);
		pricing.createRule(usdRule(city, "CAR"));
	}

	public record Passenger(UUID userId, String token) {
	}

	public Passenger passenger() {
		UUID id = UUID.randomUUID();
		return new Passenger(id, SignedTokens.forUser(issuer, id, Role.PASSENGER));
	}

	public String tokenFor(Role... roles) {
		return SignedTokens.withRoles(issuer, roles);
	}

	/** A CAR quote in the city (to the airport when {@code toAirport}); {@code corporateId} may be null. */
	public UUID quote(String token, Instant pickupAt, UUID corporateId, boolean toAirport) {
		Map<String, Object> body = new HashMap<>(Map.of("pickup", Map.of("latitude", lat, "longitude", lng), "dropoff",
				Map.of("latitude", toAirport ? airportLat : lat + 0.02, "longitude", lng), "vehicleClass", "CAR", "pickupAt",
				pickupAt.toString()));
		if (corporateId != null) {
			body.put("corporateId", corporateId.toString());
		}
		JsonNode quote = post("/api/v1/quotes", token, body, null).expectStatus().isCreated().expectBody(JsonNode.class)
			.returnResult().getResponseBody();
		return UUID.fromString(quote.get("id").asString());
	}

	public static Map<String, Object> cashRide(UUID quoteId) {
		return new HashMap<>(Map.of("quoteId", quoteId.toString(), "paymentMethod", "CASH", "pickupNotes",
				"Blue gate behind the pharmacy"));
	}

	public RestTestClient.ResponseSpec book(String token, Map<String, Object> body) {
		return post("/api/v1/rides", token, body, UUID.randomUUID().toString());
	}

	public JsonNode bookOk(String token, Map<String, Object> body) {
		return book(token, body).expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public static UUID firstTrip(JsonNode ride) {
		return UUID.fromString(ride.get("trips").get(0).get("id").asString());
	}

	/** Through dispatch (assigning moved there; reason PLANNED). The response's {@code trip} holds the new state. */
	public RestTestClient.ResponseSpec assign(UUID tripId, UUID driverId, long tripVersion) {
		return post("/api/v1/dispatch/trips/" + tripId + "/assign", tokenFor(Role.DISPATCHER),
				Map.of("driverId", driverId.toString(), "reasonCode", "PLANNED", "tripVersion", tripVersion), null);
	}

	public RestTestClient.ResponseSpec driver(String driverToken, UUID tripId, String step) {
		return post("/api/v1/driver/trips/" + tripId + "/" + step, driverToken, Map.of(), null);
	}

	public JsonNode json(RestTestClient.ResponseSpec spec) {
		return spec.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public RestTestClient.ResponseSpec get(String path, String token) {
		return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").exchange();
	}

	public RestTestClient.ResponseSpec post(String path, String token, Object body, String idempotencyKey) {
		RestTestClient.RequestBodySpec spec = client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").contentType(MediaType.APPLICATION_JSON);
		if (idempotencyKey != null) {
			spec = spec.header("Idempotency-Key", idempotencyKey);
		}
		return spec.body(body == null ? Map.of() : body).exchange();
	}
}

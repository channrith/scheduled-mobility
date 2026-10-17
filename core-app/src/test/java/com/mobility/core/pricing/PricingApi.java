package com.mobility.core.pricing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.SignedTokens;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Drives the pricing module through its HTTP API. */
public class PricingApi {

	public final RestTestClient client;

	public final AccessTokenIssuer issuer;

	public final UUID adminId = UUID.randomUUID();

	public final String adminToken;

	public PricingApi(RestTestClient client, AccessTokenIssuer issuer) {
		this.client = client;
		this.issuer = issuer;
		this.adminToken = SignedTokens.forUser(issuer, adminId, Role.ADMIN);
	}

	/** A USD rule: $2.00 base, $0.50/km, $0.10/min, $5.00 minimum, fuel factor 1, max surge 2. */
	public static Map<String, Object> usdRule(String city, String vehicleClass) {
		Map<String, Object> body = new HashMap<>();
		body.put("city", city);
		body.put("vehicleClass", vehicleClass);
		body.put("currency", "USD");
		body.put("baseMinor", 200);
		body.put("perKmMinor", 50);
		body.put("perMinMinor", 10);
		body.put("minFareMinor", 500);
		body.put("fuelIndexFactor", 1);
		body.put("maxSurge", 2);
		return body;
	}

	public static Map<String, Object> with(Map<String, Object> body, Object... keyValues) {
		Map<String, Object> copy = new HashMap<>(body);
		for (int i = 0; i < keyValues.length; i += 2) {
			copy.put((String) keyValues[i], keyValues[i + 1]);
		}
		return copy;
	}

	/** A square of about {@code sizeKm} km centred on the position, as the API's polygon. */
	public static java.util.List<Map<String, Object>> square(double lat, double lng, double sizeKm) {
		double d = sizeKm / 2 / 111.0;
		return java.util.List.of(Map.of("latitude", lat - d, "longitude", lng - d), Map.of("latitude", lat - d, "longitude", lng + d),
				Map.of("latitude", lat + d, "longitude", lng + d), Map.of("latitude", lat + d, "longitude", lng - d));
	}

	/** A random spot in Cambodia far enough inside the borders for small test zones. */
	public static double[] randomSpot() {
		java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
		return new double[] { r.nextDouble(10.5, 14.0), r.nextDouble(103.0, 107.0) };
	}

	public JsonNode createZone(String city, String type, java.util.List<Map<String, Object>> polygon, Long surchargeMinor) {
		Map<String, Object> body = new HashMap<>(Map.of("city", city, "type", type, "nameEn", type + " zone", "polygon", polygon));
		if (surchargeMinor != null) {
			body.put("surchargeMinor", surchargeMinor);
			body.put("surchargeCurrency", "USD");
		}
		return post("/api/v1/admin/pricing/zones", adminToken, body).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public JsonNode createRule(Map<String, Object> body) {
		return post("/api/v1/admin/pricing/rules", adminToken, body).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public JsonNode json(RestTestClient.ResponseSpec spec) {
		return spec.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	public String tokenFor(Role... roles) {
		return SignedTokens.withRoles(issuer, roles);
	}

	public RestTestClient.ResponseSpec get(String path, String token) {
		return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").exchange();
	}

	public RestTestClient.ResponseSpec post(String path, String token, Object body) {
		return send(client.post().uri(path), token, body);
	}

	public RestTestClient.ResponseSpec put(String path, String token, Object body) {
		return send(client.put().uri(path), token, body);
	}

	private RestTestClient.ResponseSpec send(RestTestClient.RequestBodySpec spec, String token, Object body) {
		return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body).exchange();
	}
}

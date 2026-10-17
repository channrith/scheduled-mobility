package com.mobility.core.place;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PlaceIT {

	/** Sen Sok (north-west) and Chamkar Mon (south) — to test the distance bias between the two AEON malls. */
	static final double SEN_SOK_LAT = 11.600, SEN_SOK_LNG = 104.880, BKK_LAT = 11.548, BKK_LNG = 104.930;

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlaceDirectory directory;

	String admin;

	String dispatcher;

	String passenger;

	@BeforeEach
	void setUp() {
		admin = SignedTokens.withRoles(issuer, Role.ADMIN);
		dispatcher = SignedTokens.withRoles(issuer, Role.DISPATCHER);
		passenger = SignedTokens.forUser(issuer, UUID.randomUUID(), Role.PASSENGER);
	}

	// --- Search --------------------------------------------------------------------------------

	@Test
	void seededLandmarksAreFoundByPartialKhmerAndMisspeltEnglish() {
		assertThat(firstName(search("អាកាសយានដ្ឋាន"), "nameEn")).isEqualTo("Techo International Airport");
		assertThat(firstName(search("techo airprt"), "nameEn")).isEqualTo("Techo International Airport");
		assertThat(firstName(search("ផ្សារទួលទំពូង"), "nameEn")).isEqualTo("Russian Market (Toul Tom Poung)");
		assertThat(search("អាកាសយានដ្ឋាន").get(0).get("verified").asBoolean()).isFalse();
	}

	@Test
	void nearbyPlacesRankHigher() {
		List<JsonNode> fromSenSok = search("aeon mall", SEN_SOK_LAT, SEN_SOK_LNG);
		List<JsonNode> fromBkk = search("aeon mall", BKK_LAT, BKK_LNG);

		assertThat(fromSenSok.get(0).get("nameEn").asString()).isEqualTo("AEON Mall Sen Sok City");
		assertThat(fromBkk.get(0).get("nameEn").asString()).isEqualTo("AEON Mall Phnom Penh");
		assertThat(fromSenSok.get(0).get("distanceMeters").asDouble()).isBetween(0.0, 3_000.0);
		assertThat(search("aeon mall").get(0).get("distanceMeters").isNull()).isTrue();
	}

	@Test
	void searchNeedsAQueryAndBothCoordinates() {
		searchRequest(" ", null, null).expectStatus().isBadRequest();
		searchRequest("wat", 11.5, null).expectStatus().isBadRequest();
		client.get().uri("/api/v1/places/search?q=wat").exchange().expectStatus().isUnauthorized();
	}

	// --- Admin ---------------------------------------------------------------------------------

	@Test
	void dispatcherCreatesEditsAndVerifiesAPlace() {
		String name = "Test Guesthouse " + UUID.randomUUID().toString().substring(0, 6);
		JsonNode created = post("/api/v1/admin/places", dispatcher, place(name, "ផ្ទះសំណាក់សាកល្បង", 11.5600, 104.9200))
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
		String id = created.get("id").asString();
		assertThat(created.get("verified").asBoolean()).isFalse();
		assertThat(jdbc.queryForObject("SELECT ST_Y(location::geometry) FROM place.places WHERE id = ?::uuid", Double.class, id))
			.isEqualTo(11.56);

		Map<String, Object> edit = new HashMap<>(place(name, "ផ្ទះសំណាក់សាកល្បង", 11.5610, 104.9210));
		edit.put("version", 0);
		edit.put("verified", true);
		edit.put("pickupNotesKm", "ច្រកទ្វារទី២ ទល់មុខឱសថស្ថាន");
		put("/api/v1/admin/places/" + id, dispatcher, edit).expectStatus().isOk()
			.expectBody().jsonPath("$.verified").isEqualTo(true).jsonPath("$.latitude").isEqualTo(11.561)
			.jsonPath("$.pickupNotesKm").isEqualTo("ច្រកទ្វារទី២ ទល់មុខឱសថស្ថាន");
		put("/api/v1/admin/places/" + id, dispatcher, edit).expectStatus().isEqualTo(409);

		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ? ORDER BY occurred_at", String.class, id))
			.containsExactly("place.created", "place.verified");
		assertThat(jdbc.queryForObject("SELECT ST_X(location::geometry) FROM place.places WHERE id = ?::uuid", Double.class, id))
			.isEqualTo(104.921);
	}

	@Test
	void validatesNamesAndCoordinates() {
		post("/api/v1/admin/places", admin, place(null, null, 11.56, 104.92))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("place.name-required");
		// Latitude and longitude swapped.
		post("/api/v1/admin/places", admin, place("Swapped", null, 104.92, 11.56))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("place.outside-cambodia");
	}

	@Test
	void listFiltersUnverifiedPlaces() {
		String unique = "Filter " + UUID.randomUUID().toString().substring(0, 6);
		Map<String, Object> verified = new HashMap<>(place(unique + " verified", null, 11.56, 104.92));
		verified.put("verified", true);
		post("/api/v1/admin/places", admin, verified).expectStatus().isCreated();
		post("/api/v1/admin/places", admin, place(unique + " pending", null, 11.56, 104.92)).expectStatus().isCreated();

		assertThat(names(b -> b.path("/api/v1/admin/places").queryParam("verified", false).queryParam("q", unique).build()))
			.containsExactly(unique + " pending");
		assertThat(names(b -> b.path("/api/v1/admin/places").queryParam("q", unique).build())).hasSize(2);
		assertThat(get("/api/v1/admin/places?verified=false&size=100", admin).expectBody(JsonNode.class).returnResult()
			.getResponseBody().get("totalElements").asLong()).isGreaterThanOrEqualTo(23);
	}

	@Test
	void mergeMovesSavedPlacesAndUsageAndHidesTheDuplicate() {
		String unique = "Merge Test Kiosk " + UUID.randomUUID().toString().substring(0, 4);
		String target = id(post("/api/v1/admin/places", admin, place(unique, null, 11.5700, 104.9200)));
		String duplicate = id(post("/api/v1/admin/places", admin, place(unique + " dup", null, 11.5701, 104.9201)));
		directory.recordUsage(UUID.fromString(duplicate));
		directory.recordUsage(UUID.fromString(duplicate));
		String saved = id(post("/api/v1/me/saved-places", passenger, Map.of("label", "CUSTOM", "customLabel", "Kiosk",
				"placeId", duplicate)));

		post("/api/v1/admin/places/" + duplicate + "/merge", dispatcher, Map.of("intoPlaceId", target)).expectStatus().isForbidden();
		post("/api/v1/admin/places/" + duplicate + "/merge", admin, Map.of("intoPlaceId", target)).expectStatus().isOk()
			.expectBody().jsonPath("$.id").isEqualTo(target).jsonPath("$.usageCount").isEqualTo(2);

		assertThat(search(unique).stream().map(n -> n.get("id").asString())).containsExactly(target);
		assertThat(jdbc.queryForObject("SELECT place_id FROM place.saved_places WHERE id = ?::uuid", String.class, saved))
			.isEqualTo(target);
		assertThat(directory.get(UUID.fromString(duplicate))).get().extracting(PlaceDirectory.PlaceSummary::id)
			.isEqualTo(UUID.fromString(target));
		post("/api/v1/admin/places/" + target + "/merge", admin, Map.of("intoPlaceId", target))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("place.merge-into-self");
	}

	@Test
	void onlyAdminsAndDispatchersManagePlaces() {
		post("/api/v1/admin/places", SignedTokens.withRoles(issuer, Role.SUPPORT), place("X", null, 11.56, 104.92))
			.expectStatus().isForbidden();
		get("/api/v1/admin/places", passenger).expectStatus().isForbidden();
	}

	// --- Saved places --------------------------------------------------------------------------

	@Test
	void savedPlacesPerUser() {
		String landmark = id(post("/api/v1/admin/places", admin, place("Saved Test Tower " + UUID.randomUUID().toString().substring(0, 4),
				null, 11.57, 104.92)));

		post("/api/v1/me/saved-places", passenger, Map.of("label", "OFFICE", "placeId", landmark)).expectStatus().isCreated()
			.expectBody().jsonPath("$.place.id").isEqualTo(landmark);
		post("/api/v1/me/saved-places", passenger, Map.of("label", "OFFICE", "latitude", 11.55, "longitude", 104.9))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("place.saved-label-taken");
		String home = id(post("/api/v1/me/saved-places", passenger,
				Map.of("label", "HOME", "latitude", 11.551, "longitude", 104.901, "note", "Blue gate, house 12, St 271")));
		post("/api/v1/me/saved-places", passenger, Map.of("label", "CUSTOM", "latitude", 11.5, "longitude", 104.9))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("place.custom-label-required");
		post("/api/v1/me/saved-places", passenger, Map.of("label", "CUSTOM", "customLabel", "Gym"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("place.saved-target-required");

		List<JsonNode> mine = get("/api/v1/me/saved-places", passenger).expectStatus().isOk().expectBody(JsonNode.class)
			.returnResult().getResponseBody().valueStream().toList();
		assertThat(mine).extracting(n -> n.get("label").asString()).containsExactly("OFFICE", "HOME");

		String other = SignedTokens.forUser(issuer, UUID.randomUUID(), Role.PASSENGER);
		get("/api/v1/me/saved-places", other).expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
		put("/api/v1/me/saved-places/" + home, other, Map.of("version", 0, "label", "HOME", "latitude", 11.5, "longitude", 104.9))
			.expectStatus().isNotFound();
		client.delete().uri("/api/v1/me/saved-places/" + home).header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
			.exchange().expectStatus().isNotFound();
		client.delete().uri("/api/v1/me/saved-places/" + home).header(HttpHeaders.AUTHORIZATION, "Bearer " + passenger)
			.exchange().expectStatus().isNoContent();
	}

	// --- Helpers -------------------------------------------------------------------------------

	private static Map<String, Object> place(String nameEn, String nameKm, double lat, double lng) {
		Map<String, Object> body = new HashMap<>(Map.of("latitude", lat, "longitude", lng, "type", "OTHER"));
		if (nameEn != null) body.put("nameEn", nameEn);
		if (nameKm != null) body.put("nameKm", nameKm);
		return body;
	}

	private List<JsonNode> search(String q) {
		return searchRequest(q, null, null).expectStatus().isOk().expectBody(JsonNode.class).returnResult()
			.getResponseBody().valueStream().toList();
	}

	private List<JsonNode> search(String q, double lat, double lng) {
		return searchRequest(q, lat, lng).expectStatus().isOk().expectBody(JsonNode.class).returnResult()
			.getResponseBody().valueStream().toList();
	}

	/** Raw values go through the URI builder, which encodes them exactly once (Khmer, spaces). */
	private RestTestClient.ResponseSpec searchRequest(String q, Double lat, Double lng) {
		return client.get().uri(b -> {
			b.path("/api/v1/places/search").queryParam("q", q);
			if (lat != null) b.queryParam("lat", lat);
			if (lng != null) b.queryParam("lng", lng);
			return b.build();
		}).header(HttpHeaders.AUTHORIZATION, "Bearer " + passenger).exchange();
	}

	private static String firstName(List<JsonNode> hits, String field) {
		assertThat(hits).isNotEmpty();
		return hits.get(0).get(field).asString();
	}

	private List<String> names(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri) {
		return client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange().expectStatus().isOk()
			.expectBody(JsonNode.class).returnResult().getResponseBody()
			.get("content").valueStream().map(n -> n.get("nameEn").asString()).toList();
	}

	private static String id(RestTestClient.ResponseSpec response) {
		return response.expectStatus().is2xxSuccessful().expectBody(JsonNode.class).returnResult().getResponseBody()
			.get("id").asString();
	}

	private RestTestClient.ResponseSpec get(String uri, String token) {
		return client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.exchange();
	}

	private RestTestClient.ResponseSpec post(String uri, String token, Object body) {
		return client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private RestTestClient.ResponseSpec put(String uri, String token, Object body) {
		return client.put().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}
}

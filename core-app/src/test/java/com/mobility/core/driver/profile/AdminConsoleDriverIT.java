package com.mobility.core.driver.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Endpoints added for the admin web console: register/edit, search, staff uploads, queue, audit. */
@IntegrationTest
class AdminConsoleDriverIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	DriverApi api;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
	}

	// --- Register and edit ---------------------------------------------------------------------

	@Test
	void registerStoresLicenceZoneAndLanguage() {
		String licence = "PP-" + uniqueDigits();
		JsonNode body = api.post("/api/v1/admin/drivers", api.adminToken,
				Map.of("phone", SamplePhones.next(), "fullName", "Chan Sophea", "licenseNumber", licence, "homeZone",
						"SEN_SOK", "preferredLanguage", "EN"))
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(body.get("licenseNumberMasked").asString()).startsWith("•").endsWith(licence.substring(licence.length() - 4));
		assertThat(body.get("homeZone").asString()).isEqualTo("SEN_SOK");
		assertThat(body.get("preferredLanguage").asString()).isEqualTo("EN");
		assertThat(body.get("version").asLong()).isZero();
		assertThat(jdbc.queryForObject("SELECT preferred_lang FROM identity.users WHERE id = ?::uuid", String.class,
				body.get("userId").asString())).isEqualTo("en");
		byte[] enc = jdbc.queryForObject("SELECT license_number_enc FROM driver.drivers WHERE id = ?::uuid", byte[].class,
				body.get("id").asString());
		assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(licence.substring(3));

		api.post("/api/v1/admin/drivers", api.adminToken, Map.of("phone", SamplePhones.next(), "fullName", "Other",
				"licenseNumber", licence.replace("-", " ")))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("driver.license-number-taken");
		api.post("/api/v1/admin/drivers", api.adminToken, Map.of("phone", SamplePhones.next(), "fullName", "Other",
				"licenseNumber", "x"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("driver.license-number-invalid");
	}

	@Test
	void editUpdatesFieldsAuditsOnlyFieldNamesAndSyncsTheLogin() {
		Registered driver = api.register();
		String nationalId = uniqueDigits();

		JsonNode body = patch(driver.driverId(), Map.of("version", 0, "fullName", "Sok Dara II", "nationalId", nationalId,
				"homeZone", "DANGKAO", "preferredLanguage", "EN", "bankName", "ABA", "bankAccountNumber", "000 111 222"))
			.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(body.get("fullName").asString()).isEqualTo("Sok Dara II");
		assertThat(body.get("homeZone").asString()).isEqualTo("DANGKAO");
		assertThat(body.get("nationalIdMasked").asString()).endsWith(nationalId.substring(nationalId.length() - 4));
		assertThat(body.at("/bankAccount/accountNumberMasked").asString()).endsWith("1222");
		assertThat(body.get("version").asLong()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT full_name FROM identity.users WHERE id = ?", String.class, driver.userId()))
			.isEqualTo("Sok Dara II");
		String details = jdbc.queryForObject(
				"SELECT details::text FROM audit.audit_logs WHERE target_id = ? AND action = 'driver.updated'", String.class,
				driver.driverId().toString());
		assertThat(details).contains("fullName", "nationalId", "homeZone", "bankAccount")
			.doesNotContain(nationalId).doesNotContain("Sok Dara");
	}

	@Test
	void editIsRejectedForStaleVersionDuplicatesAndNonAdmins() {
		Registered driver = api.register();
		String taken = uniqueDigits();
		api.register(Map.of("nationalId", taken));

		patch(driver.driverId(), Map.of("version", 7, "fullName", "X Y"))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("concurrent-update");
		patch(driver.driverId(), Map.of("version", 0, "nationalId", taken))
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("driver.national-id-taken");
		patch(driver.driverId(), Map.of("fullName", "No version"))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("validation");
		client.patch().uri("/api/v1/admin/drivers/" + driver.driverId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + api.tokenFor(Role.SUPPORT))
			.contentType(MediaType.APPLICATION_JSON).body(Map.of("version", 0, "fullName", "X Y"))
			.exchange().expectStatus().isForbidden();
	}

	// --- List and search -----------------------------------------------------------------------

	@Test
	void filtersByVehicleClassAndExpiringDocuments() {
		Registered moto = api.register();
		Registered car = api.register();
		api.assignVehicle(moto.driverId(), api.registerVehicle("MOTO"));
		api.assignVehicle(car.driverId(), api.registerVehicle("CAR"));
		UUID licence = api.uploadOk(car.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		api.approveDocument(car.driverId(), licence);
		jdbc.update("UPDATE driver.driver_documents SET expires_on = current_date + 10 WHERE id = ?", licence);

		List<String> motoIds = ids("/api/v1/admin/drivers?vehicleClass=MOTO&size=100");
		assertThat(motoIds).contains(moto.driverId().toString()).doesNotContain(car.driverId().toString());

		JsonNode expiring = page("/api/v1/admin/drivers?expiringSoon=true&size=100");
		JsonNode row = rowFor(expiring, car.driverId());
		assertThat(row.get("documentsExpiringSoon").asBoolean()).isTrue();
		assertThat(row.at("/vehicle/vehicleClass").asString()).isEqualTo("CAR");
		assertThat(ids(expiring)).doesNotContain(moto.driverId().toString());
	}

	@Test
	void searchesByNamePhonePlateAndExactNationalId() {
		String name = "Search Name " + UUID.randomUUID().toString().substring(0, 6);
		String nationalId = uniqueDigits();
		Registered driver = api.register(Map.of("fullName", name, "nationalId", nationalId));
		UUID vehicle = api.registerVehicle("TUKTUK_REMORK");
		api.assignVehicle(driver.driverId(), vehicle);
		String plate = api.get("/api/v1/admin/vehicles/" + vehicle, api.adminToken).expectBody(JsonNode.class)
			.returnResult().getResponseBody().get("plateNumber").asString();
		String localPhone = "0" + driver.phone().substring(4);

		assertThat(search(name.toLowerCase())).containsExactly(driver.driverId().toString());
		assertThat(search(localPhone.substring(0, 3) + " " + localPhone.substring(3))).contains(driver.driverId().toString());
		assertThat(search(plate.replaceAll("[^A-Za-z0-9]", "").toLowerCase())).containsExactly(driver.driverId().toString());
		assertThat(search(nationalId)).containsExactly(driver.driverId().toString());
		assertThat(search(nationalId.substring(0, 6))).doesNotContain(driver.driverId().toString());
	}

	// --- Staff uploads, submit, review queue ---------------------------------------------------

	@Test
	void staffUploadOnBehalfAndSubmitForTheDriver() {
		Registered driver = api.register();
		UUID id = adminUpload(driver.driverId(), "NATIONAL_ID", SampleFiles.jpeg(), SampleFiles.png(), DriverApi.inOneYear());
		adminUpload(driver.driverId(), "DRIVING_LICENSE", SampleFiles.pdf(), null, DriverApi.inOneYear());
		adminUpload(driver.driverId(), "PROFILE_PHOTO", SampleFiles.png(), null, null);

		assertThat(jdbc.queryForObject("SELECT uploaded_by FROM driver.driver_documents WHERE id = ?", UUID.class, id))
			.isEqualTo(api.adminId);
		api.admin(driver.driverId(), "submit-documents", null)
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("DOCS_SUBMITTED");
		assertThat(jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ? OR details->>'driverId' = ?",
				String.class, driver.driverId().toString(), driver.driverId().toString()))
			.contains("driver.documents-submitted", "driver.document.uploaded");
		api.get("/api/v1/drivers/me/documents", driver.token()).expectStatus().isOk()
			.expectBody().jsonPath("$[0].uploadedBy").doesNotExist();
	}

	@Test
	void staffUploadFollowsTheSameRulesAndIsAdminOnly() {
		Registered driver = api.register();

		upload(driver.driverId(), api.adminToken, "NATIONAL_ID", SampleFiles.jpeg(), null, DriverApi.inOneYear())
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.back-required");
		upload(driver.driverId(), api.tokenFor(Role.DISPATCHER), "DRIVING_LICENSE", SampleFiles.pdf(), null,
				DriverApi.inOneYear()).expectStatus().isForbidden();
		api.admin(driver.driverId(), "reject", Map.of("reason", DriverApi.REASON)).expectStatus().isOk();
		upload(driver.driverId(), api.adminToken, "DRIVING_LICENSE", SampleFiles.pdf(), null, DriverApi.inOneYear())
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("document.upload-not-allowed");
	}

	@Test
	void reviewQueueListsPendingDocumentsWithTheirDriver() {
		Registered driver = api.register(Map.of("fullName", "Queue Driver"));
		UUID pending = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		UUID approved = api.uploadOk(driver.token(), "PROFILE_PHOTO", SampleFiles.png(), null, null);
		api.approveDocument(driver.driverId(), approved);

		JsonNode queue = page("/api/v1/admin/documents?size=100&type=DRIVING_LICENSE");
		List<String> docIds = queue.get("content").valueStream().map(n -> n.at("/document/id").asString()).toList();
		assertThat(docIds).contains(pending.toString()).doesNotContain(approved.toString());
		JsonNode item = queue.get("content").valueStream()
			.filter(n -> n.at("/document/id").asString().equals(pending.toString())).findFirst().orElseThrow();
		assertThat(item.at("/driver/fullName").asString()).isEqualTo("Queue Driver");
		assertThat(item.at("/document/status").asString()).isEqualTo("PENDING_REVIEW");

		api.get("/api/v1/admin/documents", api.tokenFor(Role.SUPPORT)).expectStatus().isForbidden();
	}

	// --- Audit trail ---------------------------------------------------------------------------

	@Test
	void auditTrailShowsActionsOnTheDriverTheirDocumentsAndAccountWithActorNames() {
		UUID adminUser = UUID.randomUUID();
		jdbc.update("INSERT INTO identity.users (id, phone_e164, full_name) VALUES (?, ?, 'Ops Admin')", adminUser,
				SamplePhones.next());
		String admin = SignedTokens.forUser(issuer, adminUser, Role.ADMIN);
		JsonNode created = api.post("/api/v1/admin/drivers", admin, Map.of("phone", SamplePhones.next(), "fullName", "Audit Me"))
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
		UUID driverId = UUID.fromString(created.get("id").asString());
		UUID doc = adminUpload(driverId, "DRIVING_LICENSE", SampleFiles.pdf(), null, DriverApi.inOneYear());
		api.post("/api/v1/admin/drivers/" + driverId + "/documents/" + doc + "/approve", admin, null).expectStatus().isOk();

		JsonNode trail = api.get("/api/v1/admin/drivers/" + driverId + "/audit", api.tokenFor(Role.SAFETY_OFFICER))
			.expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(trail.valueStream().map(e -> e.get("action").asString()))
			.contains("driver.registered", "identity.role.granted", "driver.document.uploaded", "driver.document.approved");
		JsonNode registered = trail.valueStream().filter(e -> e.get("action").asString().equals("driver.registered"))
			.findFirst().orElseThrow();
		assertThat(registered.get("actorName").asString()).isEqualTo("Ops Admin");
		assertThat(registered.get("actorRoles").toString()).contains("ADMIN");

		api.get("/api/v1/admin/drivers/" + driverId + "/audit", api.tokenFor(Role.DISPATCHER)).expectStatus().isForbidden();
	}

	// --- Helpers -------------------------------------------------------------------------------

	private RestTestClient.ResponseSpec patch(UUID driverId, Map<String, Object> body) {
		return client.patch().uri("/api/v1/admin/drivers/" + driverId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + api.adminToken)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private UUID adminUpload(UUID driverId, String type, byte[] front, byte[] back, String expiresOn) {
		return UUID.fromString(upload(driverId, api.adminToken, type, front, back, expiresOn).expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody().get("id").asString());
	}

	private RestTestClient.ResponseSpec upload(UUID driverId, String token, String type, byte[] front, byte[] back,
			String expiresOn) {
		MultipartBodyBuilder parts = new MultipartBodyBuilder();
		parts.part("type", type);
		if (expiresOn != null) {
			parts.part("expiresOn", expiresOn);
		}
		parts.part("front", file(front));
		if (back != null) {
			parts.part("back", file(back));
		}
		return client.post().uri("/api/v1/admin/drivers/" + driverId + "/documents")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.MULTIPART_FORM_DATA).body(parts.build()).exchange();
	}

	private static ByteArrayResource file(byte[] content) {
		return new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return "scan.bin";
			}
		};
	}

	private JsonNode page(String uri) {
		return api.get(uri, api.adminToken).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	private List<String> ids(String uri) {
		return ids(page(uri));
	}

	private static List<String> ids(JsonNode page) {
		return page.get("content").valueStream().map(n -> n.get("id").asString()).toList();
	}

	private List<String> search(String q) {
		return ids("/api/v1/admin/drivers?size=100&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
	}

	private static JsonNode rowFor(JsonNode page, UUID driverId) {
		return page.get("content").valueStream().filter(n -> n.get("id").asString().equals(driverId.toString()))
			.findFirst().orElseThrow();
	}

	private static String uniqueDigits() {
		return String.valueOf(100_000_000L + Math.floorMod(System.nanoTime(), 899_999_999L));
	}
}

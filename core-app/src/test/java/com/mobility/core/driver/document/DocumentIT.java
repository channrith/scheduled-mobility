package com.mobility.core.driver.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class DocumentIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Value("${storage.local.dir}")
	Path storageDir;

	DriverApi api;

	Registered driver;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
		driver = api.register();
	}

	@Test
	void uploadDetectsRealTypeAndStoresFileEncrypted() {
		JsonNode doc = api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null, null)
			.expectStatus()
			.isCreated()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();

		// Declared as image/png by the client; detected as PDF.
		assertThat(doc.get("contentType").asString()).isEqualTo("application/pdf");
		assertThat(doc.get("status").asString()).isEqualTo("PENDING_REVIEW");
		String key = jdbc.queryForObject("SELECT storage_key FROM driver.driver_documents WHERE id = ?::uuid",
				String.class, doc.get("id").asString());
		byte[] onDisk = readFile(key);
		assertThat(new String(onDisk, StandardCharsets.ISO_8859_1)).doesNotContain("%PDF");
	}

	@Test
	void rejectsFilesThatAreNotJpegPngOrPdf() {
		api.upload(driver.token(), "DRIVING_LICENSE", "just text".getBytes(), DriverApi.inOneYear(), null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.unsupported-type");
		api.upload(driver.token(), "PROFILE_PHOTO", SampleFiles.pdf(), null, null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.unsupported-type");
		api.upload(driver.token(), "DRIVING_LICENSE", new byte[0], DriverApi.inOneYear(), null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.empty");
	}

	@Test
	void rejectsFilesOverTenMegabytes() {
		byte[] big = new byte[DocumentService.MAX_BYTES + 1];
		System.arraycopy(SampleFiles.pdf(), 0, big, 0, SampleFiles.pdf().length);

		api.upload(driver.token(), "DRIVING_LICENSE", big, DriverApi.inOneYear(), null, null)
			.expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("document.too-large");
	}

	@Test
	void expiryRules() {
		api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), null, null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.expiry-required");
		api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), LocalDate.now().minusDays(2).toString(), null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.expiry-in-past");
		// Photos don't expire; a given date is ignored.
		api.upload(driver.token(), "PROFILE_PHOTO", SampleFiles.png(), DriverApi.inOneYear(), null, null)
			.expectStatus().isCreated().expectBody().jsonPath("$.expiresOn").isEmpty();
	}

	@Test
	void vehicleDocumentsNeedTheAssignedVehicle() {
		api.upload(driver.token(), "VEHICLE_INSURANCE", SampleFiles.pdf(), DriverApi.inOneYear(), null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.vehicle-required");
		api.upload(driver.token(), "VEHICLE_INSURANCE", SampleFiles.pdf(), DriverApi.inOneYear(), UUID.randomUUID(), null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.vehicle-required");
		api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), UUID.randomUUID(), null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.vehicle-not-allowed");
	}

	@Test
	void reuploadSupersedesThePreviousDocument() {
		UUID first = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		UUID second = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.jpeg(), DriverApi.inOneYear(), null);

		assertThat(status(first)).isEqualTo("SUPERSEDED");
		assertThat(status(second)).isEqualTo("PENDING_REVIEW");
		api.get("/api/v1/drivers/me/documents", driver.token())
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.length()").isEqualTo(1)
			.jsonPath("$[0].id").isEqualTo(second.toString());
	}

	@Test
	void retriedUploadWithIdempotencyKeyCreatesOneDocument() {
		String key = UUID.randomUUID().toString();
		String first = api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null, key)
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody().get("id").asString();
		String retry = api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null, key)
			.expectStatus().isCreated()
			.expectHeader().valueEquals("Idempotent-Replayed", "true")
			.expectBody(JsonNode.class).returnResult().getResponseBody().get("id").asString();

		assertThat(retry).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM driver.driver_documents WHERE driver_id = ?", Integer.class,
				driver.driverId())).isEqualTo(1);
	}

	@Test
	void adminReviewRecordsReviewerAndTimeAndIsAudited() {
		UUID doc = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);

		api.admin(driver.driverId(), "documents/" + doc + "/approve", null)
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("APPROVED")
			.jsonPath("$.reviewedBy").isEqualTo(api.adminId.toString())
			.jsonPath("$.reviewedAt").isNotEmpty();
		api.admin(driver.driverId(), "documents/" + doc + "/approve", null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("document.not-pending-review");

		assertThat(auditActions(doc)).containsExactly("driver.document.approved");
	}

	@Test
	void rejectedDocumentNeedsAReasonAndCountsAsMissing() {
		UUID doc = api.uploadOk(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), DriverApi.inOneYear(), null);

		api.admin(driver.driverId(), "documents/" + doc + "/reject", Map.of("reason", "blurry"))
			.expectStatus().isBadRequest();
		api.admin(driver.driverId(), "documents/" + doc + "/reject", Map.of("reason", "Photo is blurry, please retake"))
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("REJECTED")
			.jsonPath("$.rejectionReason").isEqualTo("Photo is blurry, please retake");

		assertThat(api.detail(driver.driverId()).get("readiness").get("missing").toString()).contains("NATIONAL_ID");
	}

	@Test
	void staffCanDownloadTheDecryptedFileAndEveryViewIsAudited() {
		UUID doc = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		String path = "/api/v1/admin/drivers/" + driver.driverId() + "/documents/" + doc + "/content";

		byte[] content = api.get(path, api.adminToken)
			.expectStatus().isOk()
			.expectHeader().contentType(MediaType.APPLICATION_PDF)
			.expectHeader().valueEquals("Cache-Control", "no-store")
			.expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(content).isEqualTo(SampleFiles.pdf());

		api.get(path, api.tokenFor(Role.SAFETY_OFFICER)).expectStatus().isOk();
		api.get(path, api.tokenFor(Role.DISPATCHER)).expectStatus().isForbidden();
		api.get(path, driver.token()).expectStatus().isForbidden();
		assertThat(auditActions(doc)).containsExactly("driver.document.viewed", "driver.document.viewed");
	}

	@Test
	void documentsAreScopedToTheirDriver() {
		UUID doc = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		Registered other = api.register();

		api.get("/api/v1/admin/drivers/" + other.driverId() + "/documents/" + doc + "/content", api.adminToken)
			.expectStatus().isNotFound();
		api.admin(other.driverId(), "documents/" + doc + "/approve", null).expectStatus().isNotFound();
		api.get("/api/v1/drivers/me/documents", other.token()).expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
	}

	@Test
	void onboardingFlowFromUploadToTraining() {
		List<UUID> docs = api.uploadPersonalDocuments(driver);

		api.post("/api/v1/drivers/me/submit", driver.token(), null)
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("DOCS_SUBMITTED");
		api.admin(driver.driverId(), "training", null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("driver.documents-not-approved");

		docs.forEach(doc -> api.approveDocument(driver.driverId(), doc));

		api.admin(driver.driverId(), "training", null)
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("TRAINING");
	}

	@Test
	void rejectedDriversCannotUpload() {
		api.admin(driver.driverId(), "reject", Map.of("reason", DriverApi.REASON)).expectStatus().isOk();

		api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null, null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("document.upload-not-allowed");
	}

	@Test
	void driversCannotReviewDocuments() {
		UUID doc = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);

		api.post("/api/v1/admin/drivers/" + driver.driverId() + "/documents/" + doc + "/approve", driver.token(), null)
			.expectStatus().isForbidden();
	}

	private String status(UUID documentId) {
		return jdbc.queryForObject("SELECT status FROM driver.driver_documents WHERE id = ?", String.class, documentId);
	}

	private List<String> auditActions(UUID target) {
		return jdbc.queryForList("SELECT action FROM audit.audit_logs WHERE target_id = ? ORDER BY occurred_at",
				String.class, target.toString());
	}

	private byte[] readFile(String key) {
		try {
			return Files.readAllBytes(storageDir.resolve(key));
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}
}

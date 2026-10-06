package com.mobility.core.driver.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class TwoSidedDocumentIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	DriverApi api;

	Registered driver;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
		driver = api.register();
	}

	@ParameterizedTest
	@ValueSource(strings = { "NATIONAL_ID", "DRIVING_LICENSE" })
	void cardPhotoWithoutBackIsRejected(String type) {
		api.upload(driver.token(), type, SampleFiles.jpeg(), DriverApi.inOneYear(), null, null)
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.code").isEqualTo("document.back-required")
			.jsonPath("$.detail").isEqualTo("Please also upload the back side of this document (or one PDF with both sides).");
	}

	@Test
	void vehicleRegistrationCardNeedsBothSidesToo() {
		UUID vehicle = api.registerVehicle("MOTO");
		api.assignVehicle(driver.driverId(), vehicle);

		api.upload(driver.token(), "VEHICLE_REGISTRATION", SampleFiles.png(), DriverApi.inOneYear(), vehicle, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.back-required");
		api.uploadOk(driver.token(), "VEHICLE_REGISTRATION", SampleFiles.png(), SampleFiles.jpeg(), DriverApi.inOneYear(),
				vehicle);
	}

	@Test
	void frontAndBackAreStoredAsOneDocument() {
		JsonNode doc = api.upload(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), SampleFiles.png(),
				DriverApi.inOneYear(), null, null)
			.expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();

		assertThat(doc.get("status").asString()).isEqualTo("PENDING_REVIEW");
		assertThat(doc.get("files").valueStream().map(f -> f.get("side").asString() + ":" + f.get("contentType").asString()))
			.containsExactly("FRONT:image/jpeg", "BACK:image/png");
		List<String> keys = jdbc.queryForList(
				"SELECT storage_key FROM driver.driver_document_files WHERE document_id = ?::uuid ORDER BY side DESC",
				String.class, doc.get("id").asString());
		assertThat(keys).hasSize(2).doesNotHaveDuplicates();
		assertThat(keys.get(0)).endsWith("-front");
		assertThat(keys.get(1)).endsWith("-back");
	}

	@Test
	void onePdfCanCoverBothSides() {
		api.upload(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null, null)
			.expectStatus().isCreated()
			.expectBody()
			.jsonPath("$.files.length()").isEqualTo(1)
			.jsonPath("$.files[0].side").isEqualTo("FRONT");
	}

	@Test
	void photoHasNoBackSide() {
		api.upload(driver.token(), "PROFILE_PHOTO", SampleFiles.png(), SampleFiles.png(), null, null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.back-not-allowed");
	}

	@Test
	void backSideIsValidatedLikeTheFront() {
		api.upload(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), "not an image".getBytes(), DriverApi.inOneYear(),
				null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.unsupported-type");
		api.upload(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), new byte[0], DriverApi.inOneYear(), null, null)
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("document.empty");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM driver.driver_documents WHERE driver_id = ?", Integer.class,
				driver.driverId())).isZero();
	}

	@Test
	void eachSideIsDownloadedSeparatelyAndEachViewIsAudited() {
		byte[] front = SampleFiles.jpeg();
		byte[] back = SampleFiles.png();
		UUID doc = api.uploadOk(driver.token(), "NATIONAL_ID", front, back, DriverApi.inOneYear(), null);
		String base = "/api/v1/admin/drivers/" + driver.driverId() + "/documents/" + doc + "/files/";

		assertThat(download(base + "front", MediaType.IMAGE_JPEG)).isEqualTo(front);
		assertThat(download(base + "BACK", MediaType.IMAGE_PNG)).isEqualTo(back);
		api.get(base + "side/content", api.adminToken).expectStatus().isNotFound();

		assertThat(jdbc.queryForList("""
				SELECT details->>'side' FROM audit.audit_logs
				WHERE target_id = ? AND action = 'driver.document.viewed' ORDER BY occurred_at""",
				String.class, doc.toString())).containsExactly("FRONT", "BACK");
	}

	@Test
	void pdfDocumentHasNoBackToDownload() {
		UUID doc = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);

		api.get("/api/v1/admin/drivers/" + driver.driverId() + "/documents/" + doc + "/files/back/content",
				api.adminToken).expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("document.not-found");
	}

	@Test
	void reuploadReplacesBothSides() {
		UUID first = api.uploadOk(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), SampleFiles.jpeg(),
				DriverApi.inOneYear(), null);
		UUID second = api.uploadOk(driver.token(), "NATIONAL_ID", SampleFiles.pdf(), null, DriverApi.inOneYear(), null);

		assertThat(jdbc.queryForObject("SELECT status FROM driver.driver_documents WHERE id = ?", String.class, first))
			.isEqualTo("SUPERSEDED");
		api.get("/api/v1/drivers/me/documents", driver.token())
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.length()").isEqualTo(1)
			.jsonPath("$[0].id").isEqualTo(second.toString())
			.jsonPath("$[0].files.length()").isEqualTo(1);
	}

	@Test
	void reviewIsOncePerDocument() {
		UUID doc = api.uploadOk(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), SampleFiles.jpeg(),
				DriverApi.inOneYear(), null);

		api.admin(driver.driverId(), "documents/" + doc + "/approve", null)
			.expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("APPROVED").jsonPath("$.files.length()").isEqualTo(2);
	}

	private byte[] download(String sidePath, MediaType expected) {
		return api.get(sidePath + "/content", api.adminToken)
			.expectStatus().isOk()
			.expectHeader().contentType(expected)
			.expectBody(byte[].class).returnResult().getResponseBody();
	}
}

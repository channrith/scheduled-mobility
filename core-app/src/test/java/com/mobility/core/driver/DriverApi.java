package com.mobility.core.driver;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.SamplePhones;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Drives the driver module through its real HTTP API. */
public class DriverApi {

	public static final String REASON = "Serious complaint from a corporate client";

	public final RestTestClient client;

	public final AccessTokenIssuer issuer;

	public final UUID adminId = UUID.randomUUID();

	public final String adminToken;

	public DriverApi(RestTestClient client, AccessTokenIssuer issuer) {
		this.client = client;
		this.issuer = issuer;
		this.adminToken = SignedTokens.forUser(issuer, adminId, Role.ADMIN);
	}

	public record Registered(UUID driverId, UUID userId, String phone, String token) {
	}

	public Registered register() {
		return register(Map.of());
	}

	public Registered register(Map<String, Object> overrides) {
		Map<String, Object> body = new HashMap<>(Map.of("phone", SamplePhones.next(), "fullName", "Sok Dara"));
		body.putAll(overrides);
		JsonNode json = post("/api/v1/admin/drivers", adminToken, body).expectStatus()
			.isCreated()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		UUID userId = UUID.fromString(json.get("userId").asString());
		return new Registered(UUID.fromString(json.get("id").asString()), userId, json.get("phone").asString(),
				SignedTokens.forUser(issuer, userId, Role.DRIVER));
	}

	public String tokenFor(Role... roles) {
		return SignedTokens.withRoles(issuer, roles);
	}

	public RestTestClient.ResponseSpec get(String path, String token) {
		return client.get()
			.uri(path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.exchange();
	}

	public RestTestClient.ResponseSpec post(String path, String token, Object body) {
		return client.post()
			.uri(path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_JSON)
			.body(body == null ? Map.of() : body)
			.exchange();
	}

	public RestTestClient.ResponseSpec admin(UUID driverId, String action, Object body) {
		return post("/api/v1/admin/drivers/" + driverId + "/" + action, adminToken, body);
	}

	/** Front-only multipart upload as the driver. Pass nulls for optional fields. */
	public RestTestClient.ResponseSpec upload(String driverToken, String type, byte[] file, String expiresOn,
			UUID vehicleId, String idempotencyKey) {
		return upload(driverToken, type, file, null, expiresOn, vehicleId, idempotencyKey);
	}

	public RestTestClient.ResponseSpec upload(String driverToken, String type, byte[] front, byte[] back,
			String expiresOn, UUID vehicleId, String idempotencyKey) {
		MultipartBodyBuilder parts = new MultipartBodyBuilder();
		parts.part("type", type);
		if (expiresOn != null) {
			parts.part("expiresOn", expiresOn);
		}
		if (vehicleId != null) {
			parts.part("vehicleId", vehicleId.toString());
		}
		parts.part("front", file(front)).contentType(MediaType.IMAGE_PNG);
		if (back != null) {
			parts.part("back", file(back)).contentType(MediaType.IMAGE_PNG);
		}
		var spec = client.post()
			.uri("/api/v1/drivers/me/documents")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.MULTIPART_FORM_DATA);
		if (idempotencyKey != null) {
			spec = spec.header("Idempotency-Key", idempotencyKey);
		}
		return spec.body(parts.build()).exchange();
	}

	private static ByteArrayResource file(byte[] content) {
		return new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return "upload.bin";
			}
		};
	}

	public UUID uploadOk(String driverToken, String type, byte[] file, String expiresOn, UUID vehicleId) {
		return uploadOk(driverToken, type, file, null, expiresOn, vehicleId);
	}

	public UUID uploadOk(String driverToken, String type, byte[] front, byte[] back, String expiresOn, UUID vehicleId) {
		JsonNode doc = upload(driverToken, type, front, back, expiresOn, vehicleId, null).expectStatus()
			.isCreated()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		return UUID.fromString(doc.get("id").asString());
	}

	public static String inOneYear() {
		return java.time.LocalDate.now().plusYears(1).toString();
	}

	/**
	 * Uploads NATIONAL_ID (front + back photos), DRIVING_LICENSE (one PDF of both sides) and PROFILE_PHOTO;
	 * returns their ids in that order.
	 */
	public java.util.List<UUID> uploadPersonalDocuments(Registered driver) {
		return java.util.List.of(
				uploadOk(driver.token(), "NATIONAL_ID", SampleFiles.jpeg(), SampleFiles.jpeg(), inOneYear(), null),
				uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), inOneYear(), null),
				uploadOk(driver.token(), "PROFILE_PHOTO", SampleFiles.png(), null, null));
	}

	public void approveDocument(UUID driverId, UUID documentId) {
		admin(driverId, "documents/" + documentId + "/approve", null).expectStatus().isOk();
	}

	public UUID registerVehicle(String vehicleClass) {
		String plate = "PP " + (1 + (int) (System.nanoTime() % 9)) + "A-" + String.format("%04d", (int) (System.nanoTime() % 10_000));
		JsonNode vehicle = post("/api/v1/admin/vehicles", adminToken,
				Map.of("plateNumber", plate + UUID.randomUUID().toString().substring(0, 4), "vehicleClass", vehicleClass,
						"seats", 4))
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
		return UUID.fromString(vehicle.get("id").asString());
	}

	public void assignVehicle(UUID driverId, UUID vehicleId) {
		admin(driverId, "vehicle-assignment", Map.of("vehicleId", vehicleId)).expectStatus().isOk();
	}

	/** Takes a new driver through the whole onboarding to APPROVED, with an assigned vehicle. */
	public Registered approvedDriver() {
		Registered driver = register();
		java.util.List<UUID> docs = new java.util.ArrayList<>(uploadPersonalDocuments(driver));
		post("/api/v1/drivers/me/submit", driver.token(), null).expectStatus().isOk();
		docs.forEach(doc -> approveDocument(driver.driverId(), doc));
		admin(driver.driverId(), "training", null).expectStatus().isOk();
		UUID vehicle = registerVehicle("CAR");
		assignVehicle(driver.driverId(), vehicle);
		approveDocument(driver.driverId(), uploadOk(driver.token(), "VEHICLE_REGISTRATION", SampleFiles.png(),
				SampleFiles.png(), inOneYear(), vehicle));
		admin(driver.driverId(), "approve", null).expectStatus().isOk();
		return driver;
	}

	public JsonNode detail(UUID driverId) {
		return get("/api/v1/admin/drivers/" + driverId, adminToken).expectStatus()
			.isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
	}
}

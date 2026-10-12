package com.mobility.core.shared.web;

import java.time.Duration;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.token.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CorsAndPreviewIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	@Test
	void corsAllowsOnlyTheConfiguredConsoleOrigin() {
		preflight("http://localhost:3000").expectStatus().isOk()
			.expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");
		preflight("https://evil.example").expectStatus().isForbidden()
			.expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
	}

	@Test
	void previewLinksExpireAndCannotBeTamperedWith() {
		DriverApi api = new DriverApi(client, issuer);
		Registered driver = api.register();
		UUID doc = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		String url = api.previewUrl(driver.driverId(), doc, "front", api.adminToken).expectStatus().isOk()
			.expectBody(JsonNode.class).returnResult().getResponseBody().get("url").asString();

		String tampered = url.substring(0, url.length() - 2) + (url.endsWith("A") ? "BB" : "AA");
		api.openPreview(tampered).expectStatus().isNotFound()
			.expectBody().jsonPath("$.code").isEqualTo("document.preview-invalid");
		api.openPreview("/api/v1/document-previews/not-a-token").expectStatus().isNotFound();
		api.openPreview(url).expectStatus().isOk();

		clock.advance(Duration.ofMinutes(6));
		api.openPreview(url).expectStatus().isNotFound();
	}

	private RestTestClient.ResponseSpec preflight(String origin) {
		return client.options().uri("/api/v1/admin/drivers")
			.header(HttpHeaders.ORIGIN, origin)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
			.exchange();
	}
}

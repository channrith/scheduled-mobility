package com.mobility.gateway;

import java.time.Instant;

import com.mobility.platform.security.MobilityJwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: the gateway checks tokens once for every service; bad ones never reach a backend. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayAuthTests {

	static final StubBackend core = new StubBackend("core-app");

	static final StubBackend location = new StubBackend("location-service");

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) {
		registry.add("CORE_APP_URL", core::url);
		registry.add("LOCATION_SERVICE_URL", location::url);
		registry.add("JWT_JWKS_URI", CoreTokens::jwksUri);
	}

	@AfterAll
	static void stop() {
		core.close();
		location.close();
	}

	@Autowired
	WebTestClient client;

	WebTestClient.ResponseSpec me(String token) {
		WebTestClient.RequestHeadersSpec<?> request = client.get().uri("/api/v1/me").header("Accept-Language", "en");
		if (token != null) {
			request = request.header("Authorization", "Bearer " + token);
		}
		return request.exchange();
	}

	void expectSignInProblem(WebTestClient.ResponseSpec response) {
		response.expectStatus().isUnauthorized()
			.expectHeader().valueEquals("WWW-Authenticate", "Bearer")
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectHeader().exists("X-Request-Id")
			.expectBody()
			.jsonPath("$.code").isEqualTo("auth.unauthorized")
			.jsonPath("$.title").isEqualTo("Unauthorized")
			.jsonPath("$.detail").isEqualTo("Please sign in first.")
			.jsonPath("$.requestId").exists();
	}

	@Test
	void missingExpiredForgedAndForeignTokensStopAtTheGateway() {
		int before = core.hits.get();

		expectSignInProblem(me(null));
		expectSignInProblem(me(CoreTokens.CORE.token("u", MobilityJwt.ISSUER, Instant.now().minusSeconds(60))));
		expectSignInProblem(me(CoreTokens.FORGER.token("u", MobilityJwt.ISSUER, Instant.now().plusSeconds(600))));
		expectSignInProblem(me(CoreTokens.CORE.token("u", "someone-else", Instant.now().plusSeconds(600))));
		expectSignInProblem(me("not-a-jwt"));

		assertThat(core.hits.get()).as("requests that reached core-app").isEqualTo(before);
	}

	@Test
	void theUnauthorizedProblemIsKhmerByDefaultLikeCoreApps() {
		client.get().uri("/api/v1/me").exchange()
			.expectStatus().isUnauthorized()
			.expectBody()
			.jsonPath("$.title").isEqualTo("មិនទាន់បានផ្ទៀងផ្ទាត់អត្តសញ្ញាណ")
			.jsonPath("$.detail").isEqualTo("សូមចូលគណនីរបស់អ្នកជាមុនសិន។");
	}

	@Test
	void aValidTokenReachesTheServiceUnchanged() {
		String token = CoreTokens.valid();

		me(token).expectStatus().isOk()
			.expectBody()
			.jsonPath("$.service").isEqualTo("core-app")
			.jsonPath("$.headers.authorization").isEqualTo("Bearer " + token);
	}

	@Test
	void locationPingsNeedATokenToo() {
		int before = location.hits.get();

		client.post().uri("/api/v1/locations").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isUnauthorized();

		assertThat(location.hits.get()).isEqualTo(before);
	}

	@Test
	void publicRoutesNeedNoToken() {
		client.post().uri("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("core-app");
		client.post().uri("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isOk();
		// Signed document links: core-app checks the token in the URL.
		client.get().uri("/api/v1/document-previews/signed-token").exchange().expectStatus().isOk();
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();
		client.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
	}

	@Test
	void onlyGetIsPublicForDocumentPreviews() {
		client.post().uri("/api/v1/document-previews/signed-token").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void anInvalidTokenIsRejectedEvenOnAPublicRouteAsInCoreApp() {
		client.post().uri("/api/v1/auth/otp/request").header("Authorization", "Bearer not-a-jwt")
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void unroutedPathsStayTheGatewaysOwn404WithoutAToken() {
		client.get().uri("/somewhere").exchange()
			.expectStatus().isNotFound()
			.expectBody().jsonPath("$.code").isEqualTo("gateway.not-found");
	}
}

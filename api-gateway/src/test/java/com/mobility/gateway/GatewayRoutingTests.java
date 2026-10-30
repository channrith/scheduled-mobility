package com.mobility.gateway;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingTests {

	static final StubBackend core = new StubBackend("core-app");

	static final StubBackend location = new StubBackend("location-service");

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) {
		registry.add("CORE_APP_URL", core::url);
		registry.add("LOCATION_SERVICE_URL", location::url);
		registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "1s");
		registry.add("gateway.edge.max-request-size", () -> "1KB");
	}

	@AfterAll
	static void stop() {
		core.close();
		location.close();
	}

	@Autowired
	WebTestClient client;

	@LocalServerPort
	int port;

	@Test
	void apiPathsGoToCoreAppWithARequestId() {
		var result = client.get()
			.uri("/api/v1/quotes/123?x=1")
			.header("Authorization", "Bearer token-passes-through")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.service").isEqualTo("core-app")
			.jsonPath("$.path").isEqualTo("/api/v1/quotes/123?x=1")
			.jsonPath("$.headers.authorization").isEqualTo("Bearer token-passes-through")
			.returnResult();
		String id = result.getResponseHeaders().getFirst("X-Request-Id");
		assertThat(id).isNotBlank();
		assertThat(new String(result.getResponseBodyContent())).contains("\"x-request-id\":\"" + id + "\"");
	}

	@Test
	void locationPathsGoToLocationService() {
		client.post()
			.uri("/api/v1/locations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{}")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.service").isEqualTo("location-service")
			.jsonPath("$.path").isEqualTo("/api/v1/locations");
		client.get().uri("/api/v1/locations-elsewhere").exchange().expectBody().jsonPath("$.service").isEqualTo("core-app");
	}

	@Test
	void apiDocsGoToCoreApp() {
		client.get().uri("/v3/api-docs").exchange().expectBody().jsonPath("$.service").isEqualTo("core-app");
		client.get().uri("/swagger-ui/index.html").exchange().expectBody().jsonPath("$.service").isEqualTo("core-app");
	}

	@Test
	void keepsASaneClientRequestIdAndReplacesAnOddOne() {
		client.get()
			.uri("/api/v1/me")
			.header("X-Request-Id", "app-1234abcd")
			.exchange()
			.expectHeader()
			.valueEquals("X-Request-Id", "app-1234abcd")
			.expectBody()
			.jsonPath("$.headers.x-request-id").isEqualTo("app-1234abcd");
		client.get()
			.uri("/api/v1/me")
			.header("X-Request-Id", "bad id with spaces\" {}")
			.exchange()
			.expectHeader()
			.value("X-Request-Id", id -> assertThat(id).matches("[0-9a-f-]{36}"));
	}

	@Test
	void internalHeadersFromOutsideNeverReachABackend() {
		client.get()
			.uri("/api/v1/me")
			.header("X-User-Id", "someone-else")
			.header("x-roles", "ADMIN")
			.header("X-Internal-Trust", "yes")
			.header("X-Partner-Id", "p1")
			.header("Accept-Language", "en")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.headers.x-user-id").doesNotExist()
			.jsonPath("$.headers.x-roles").doesNotExist()
			.jsonPath("$.headers.x-internal-trust").doesNotExist()
			.jsonPath("$.headers.x-partner-id").doesNotExist()
			.jsonPath("$.headers.accept-language").isEqualTo("en");
	}

	@Test
	void caddysForwardedHeadersPassThroughUnchanged() {
		client.get()
			.uri("/api/v1/me")
			.header("X-Forwarded-For", "203.0.113.7")
			.header("X-Forwarded-Proto", "https")
			.header("X-Forwarded-Host", "api.example.com")
			.exchange()
			.expectBody()
			.jsonPath("$.headers.x-forwarded-for").isEqualTo("203.0.113.7")
			.jsonPath("$.headers.x-forwarded-proto").isEqualTo("https")
			.jsonPath("$.headers.x-forwarded-host").isEqualTo("api.example.com")
			.jsonPath("$.headers.forwarded").doesNotExist();
	}

	@Test
	void unknownPathsAndBackendActuatorsAreLocalizedProblems() {
		client.get()
			.uri("/somewhere")
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectHeader()
			.exists("X-Request-Id")
			.expectBody()
			.jsonPath("$.code").isEqualTo("gateway.not-found")
			.jsonPath("$.title").isEqualTo("រកមិនឃើញ")
			.jsonPath("$.instance").isEqualTo("/somewhere")
			.jsonPath("$.requestId").exists();
		client.get()
			.uri("/actuator/env")
			.header("Accept-Language", "en")
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectBody()
			.jsonPath("$.title").isEqualTo("Not found")
			.jsonPath("$.detail").isEqualTo("There is no API at this address.");
	}

	@Test
	void ownHealthIsUpAndNotLoggedWhileHealthy(CapturedOutput output) {
		client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
		client.get().uri("/api/v1/me").header("X-Request-Id", "log-check-0001").exchange().expectStatus().isOk();

		// The line is written just after the response completes.
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(output.getOut()).contains("GET /api/v1/me -> 200"));
		assertThat(output.getOut()).doesNotContain("/actuator/health/readiness -> 200");
	}

	@Test
	void slowBackendIsAGatewayTimeout() {
		client.get()
			.uri("/api/v1/slow")
			.exchange()
			.expectStatus()
			.isEqualTo(504)
			.expectBody()
			.jsonPath("$.code").isEqualTo("gateway.upstream-timeout");
	}

	@Test
	void tooLargeBodiesStopAtTheGateway() {
		client.post()
			.uri("/api/v1/drivers/me/documents")
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.bodyValue(new byte[2048])
			.exchange()
			.expectStatus()
			.isEqualTo(413)
			.expectBody()
			.jsonPath("$.code").isEqualTo("gateway.too-large");
	}

	@Test
	void webSocketsAreProxiedToLocationService() {
		Sinks.One<String> reply = Sinks.one();
		new ReactorNettyWebSocketClient()
			.execute(URI.create("ws://localhost:" + port + "/ws/echo"),
					session -> session.send(Mono.just(session.textMessage("ping")))
						.thenMany(session.receive().map(WebSocketMessage::getPayloadAsText).take(1))
						.doOnNext(reply::tryEmitValue)
						.then())
			.block(Duration.ofSeconds(5));
		assertThat(reply.asMono().block(Duration.ofSeconds(1))).isEqualTo("location-service:ping");
	}
}

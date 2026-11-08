package com.mobility.gateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

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

/** Rate limits fail open: with Redis unreachable, requests still go through, quickly, and the gateway stays healthy. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RateLimitRedisDownTests {

	static final StubBackend core = new StubBackend("core-app");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		int closed;
		try (ServerSocket socket = new ServerSocket(0)) {
			closed = socket.getLocalPort();
		}
		registry.add("CORE_APP_URL", core::url);
		registry.add("JWT_JWKS_URI", CoreTokens::jwksUri);
		registry.add("spring.data.redis.port", () -> closed);
	}

	@AfterAll
	static void stop() {
		core.close();
	}

	@Autowired
	WebTestClient client;

	@Test
	void requestsPassWithoutRedis() {
		long started = System.nanoTime();
		// More than the auth policy's 30: nothing is counted while Redis is away.
		for (int i = 0; i < 35; i++) {
			client.post().uri("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
				.exchange().expectStatus().isOk();
		}
		assertThat(Duration.ofNanos(System.nanoTime() - started)).as("35 requests").isLessThan(Duration.ofSeconds(10));
	}

	@Test
	void theGatewayStaysHealthy() {
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();
		client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
	}
}

package com.mobility.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3, shadow mode: requests over a limit are logged as "would refuse" and still reach the backend, so the
 * numbers can be checked against real traffic before they are enforced.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class RateLimitShadowIT {

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379);

	static final StubBackend core = new StubBackend("core-app");

	static final StubBackend location = new StubBackend("location-service");

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) {
		registry.add("CORE_APP_URL", core::url);
		registry.add("LOCATION_SERVICE_URL", location::url);
		registry.add("JWT_JWKS_URI", CoreTokens::jwksUri);
		registry.add("gateway.rate-limits.mode", () -> "shadow");
	}

	@AfterAll
	static void stop() {
		core.close();
		location.close();
	}

	@Autowired
	WebTestClient client;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void requestsOverTheLimitAreLoggedAndPassed(CapturedOutput output) {
		String ip = "203.0.113.30";
		int before = core.hits.get();

		for (int i = 0; i < 35; i++) {
			client.post().uri("/api/v1/auth/otp/request").header("X-Forwarded-For", ip)
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
				.expectStatus().isOk()
				.expectHeader().doesNotExist("Retry-After");
		}

		assertThat(core.hits.get() - before).as("every request reaches core-app").isEqualTo(35);
		assertThat(redis.hasKey("rl:auth:ip:" + ip)).as("buckets are still counted").isTrue();
		// Burst 30: about 5 over the limit (a token may drip back during the loop).
		assertThat(output.getAll().lines()
			.filter(line -> line.contains("Rate limit 'auth' would refuse POST /api/v1/auth/otp/request"))
			.count()).isBetween(3L, 5L);
		assertThat(output.getAll()).doesNotContain("Rate limit 'auth' reached");
	}
}

package com.mobility.gateway;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mobility.platform.security.MobilityJwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
 * Phase 3: the policies shipped in application.yml, against a real Redis. Each test uses its own IP address or user,
 * so buckets don't carry over between tests. The test client connects from 127.0.0.1, a trusted proxy, so
 * {@code X-Forwarded-For} names the client as Caddy would.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class RateLimitIT {

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

	static String token(String subject) {
		return CoreTokens.CORE.token(subject, MobilityJwt.ISSUER, Instant.now().plusSeconds(600), "PASSENGER");
	}

	WebTestClient.ResponseSpec otpRequest(String ip) {
		return client.post().uri("/api/v1/auth/otp/request").header("X-Forwarded-For", ip + ", 10.0.0.5")
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
	}

	WebTestClient.ResponseSpec quote(String user) {
		return client.post().uri("/api/v1/quotes").header("Authorization", "Bearer " + token(user))
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
	}

	WebTestClient.ResponseSpec ping(String driver) {
		return client.post().uri("/api/v1/locations").header("Authorization", "Bearer " + token(driver))
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
	}

	@Test
	void signInIsLimitedPerClientIpWithAProblemAndRetryAfter() {
		String ip = "203.0.113.10";
		for (int i = 0; i < 30; i++) {
			otpRequest(ip).expectStatus().isOk();
		}
		int before = core.hits.get();

		otpRequest(ip).expectStatus().isEqualTo(429)
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			// 30 per minute: the next token is back within 2 s.
			.expectHeader().valueMatches("Retry-After", "[12]")
			.expectHeader().exists("X-Request-Id")
			.expectBody()
			.jsonPath("$.status").isEqualTo(429)
			.jsonPath("$.code").isEqualTo("gateway.rate-limited")
			.jsonPath("$.title").isEqualTo("សំណើច្រើនពេក")
			.jsonPath("$.instance").isEqualTo("/api/v1/auth/otp/request")
			.jsonPath("$.requestId").exists();

		assertThat(core.hits.get()).as("refused requests never reach core-app").isEqualTo(before);
		// Another client behind Caddy has its own bucket.
		otpRequest("203.0.113.11").expectStatus().isOk();
	}

	@Test
	void theProblemIsEnglishOnRequest() {
		String ip = "203.0.113.20";
		for (int i = 0; i < 30; i++) {
			otpRequest(ip).expectStatus().isOk();
		}
		client.post().uri("/api/v1/auth/refresh").header("X-Forwarded-For", ip).header("Accept-Language", "en")
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isEqualTo(429)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Too many requests")
			.jsonPath("$.detail").isEqualTo("Too many requests. Wait a moment and try again.");
	}

	@Test
	void quotesAreLimitedPerUserNotPerIp() {
		String alice = "user-" + UUID.randomUUID();
		for (int i = 0; i < 20; i++) {
			quote(alice).expectStatus().isOk();
		}
		quote(alice).expectStatus().isEqualTo(429);

		// Same address, another user: own bucket.
		quote("user-" + UUID.randomUUID()).expectStatus().isOk();
		// Reading a quote is not a new quote: the (larger) default policy applies.
		client.get().uri("/api/v1/quotes/q-1").header("Authorization", "Bearer " + token(alice)).exchange()
			.expectStatus().isOk();
	}

	@Test
	void pingBucketsRefillOverTime() throws InterruptedException {
		String driver = "driver-" + UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			ping(driver).expectStatus().isOk();
		}
		ping(driver).expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "1");

		Thread.sleep(1_100);

		ping(driver).expectStatus().isOk();
	}

	@Test
	void bucketsExpireInRedis() {
		String user = "user-" + UUID.randomUUID();
		quote(user).expectStatus().isOk();

		String key = "rl:quotes:user:" + user;
		assertThat(redis.hasKey(key)).isTrue();
		// 20 tokens at 30 per minute refill in 40 s; the key goes a second after that.
		assertThat(redis.getExpire(key)).isBetween(1L, 41L);
	}

	@Test
	void healthAndUnroutedPathsAreNotLimited() {
		Set<String> before = redis.keys("rl:*");

		for (int i = 0; i < 5; i++) {
			client.get().uri("/actuator/health").exchange().expectStatus().isOk();
			client.get().uri("/nothing-here").exchange().expectStatus().isNotFound();
		}

		assertThat(redis.keys("rl:*")).isEqualTo(before);
	}

	@Test
	void requestsWithoutAValidTokenAreAnswered401BeforeAnyBucket() {
		Set<String> before = redis.keys("rl:*");

		client.post().uri("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isUnauthorized();

		assertThat(redis.keys("rl:*")).isEqualTo(before);
	}
}

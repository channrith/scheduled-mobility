package com.mobility.location.ping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.mobility.location.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Testcontainers
class LocationPingIT {

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("mobility.jwt.jwks-uri", () -> "");
		registry.add("mobility.jwt.public-key", TestKeys::publicPem);
	}

	@Autowired
	RestTestClient client;

	@Autowired
	StringRedisTemplate redis;

	RestTestClient.ResponseSpec ping(String token, Map<String, Object> body) {
		RestTestClient.RequestBodySpec spec = client.post().uri("/api/v1/locations").contentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			spec = spec.header("Authorization", "Bearer " + token);
		}
		return spec.body(body).exchange();
	}

	@Test
	void aDriversPingIsStoredWhereDispatchReadsIt() {
		UUID driver = UUID.randomUUID();
		Instant at = Instant.now().minusSeconds(5);

		ping(TestKeys.token(driver, "mobility-core", "DRIVER"),
				Map.of("latitude", 11.5636, "longitude", 104.9312, "accuracyMeters", 8, "recordedAt", at.toString()))
			.expectStatus().isAccepted().expectBody().jsonPath("$.stored").isEqualTo(true);

		Map<Object, Object> hash = redis.opsForHash().entries("driver:location:" + driver);
		assertThat(hash).containsEntry("lat", "11.5636").containsEntry("lng", "104.9312")
			.containsEntry("recordedAt", Long.toString(at.toEpochMilli()));
		assertThat(redis.getExpire("driver:location:" + driver)).isPositive();
		assertThat(redis.opsForGeo().position("driver:positions", driver.toString())).hasSize(1);
	}

	@Test
	void anOlderPingNeverOverwritesANewerOne() {
		UUID driver = UUID.randomUUID();
		String token = TestKeys.token(driver, "mobility-core", "DRIVER");
		ping(token, Map.of("latitude", 11.5, "longitude", 104.9, "recordedAt", Instant.now().toString())).expectStatus().isAccepted();

		ping(token, Map.of("latitude", 11.6, "longitude", 104.8, "recordedAt", Instant.now().minusSeconds(60).toString()))
			.expectStatus().isAccepted().expectBody().jsonPath("$.stored").isEqualTo(false).jsonPath("$.reason").isEqualTo("stale");
		assertThat(redis.opsForHash().get("driver:location:" + driver, "lat")).isEqualTo("11.5");
	}

	@Test
	void speedAndHeadingAreStoredAndLastSeenIsKeptInTheDriverState() {
		UUID driver = UUID.randomUUID();
		Instant at = Instant.now().minusSeconds(2);

		ping(TestKeys.token(driver, "mobility-core", "DRIVER"), Map.of("latitude", 11.5636, "longitude", 104.9312,
				"accuracyMeters", 12, "speedMps", 9.5, "headingDegrees", 270, "recordedAt", at.toString()))
			.expectStatus().isAccepted().expectBody().jsonPath("$.stored").isEqualTo(true).jsonPath("$.reason").doesNotExist();

		assertThat(redis.opsForHash().entries("driver:location:" + driver)).containsEntry("speed", "9.5")
			.containsEntry("heading", "270.0").containsEntry("accuracy", "12.0");
		Object lastSeen = redis.opsForHash().get("driver:state:" + driver, "lastSeen");
		assertThat(Long.parseLong(lastSeen.toString())).isGreaterThanOrEqualTo(at.toEpochMilli());
		assertThat(redis.getExpire("driver:state:" + driver)).isPositive();
	}

	@Test
	void anInaccuratePingIsDroppedButTheDriverIsStillSeen() {
		UUID driver = UUID.randomUUID();

		ping(TestKeys.token(driver, "mobility-core", "DRIVER"),
				Map.of("latitude", 11.5, "longitude", 104.9, "accuracyMeters", 250, "recordedAt", Instant.now().toString()))
			.expectStatus().isAccepted().expectBody().jsonPath("$.stored").isEqualTo(false).jsonPath("$.reason").isEqualTo("accuracy");

		assertThat(redis.hasKey("driver:location:" + driver)).isFalse();
		assertThat(redis.opsForGeo().position("driver:positions", driver.toString())).containsOnlyNulls();
		assertThat(redis.opsForHash().get("driver:state:" + driver, "lastSeen")).isNotNull();
	}

	@Test
	void aGpsJumpIsDroppedButNormalDrivingIsNot() {
		UUID driver = UUID.randomUUID();
		String token = TestKeys.token(driver, "mobility-core", "DRIVER");
		Instant start = Instant.now().minusSeconds(20);
		ping(token, Map.of("latitude", 11.5636, "longitude", 104.9312, "recordedAt", start.toString())).expectStatus().isAccepted();

		// About 45 m in 4 s (40 km/h).
		ping(token, Map.of("latitude", 11.5640, "longitude", 104.9312, "recordedAt", start.plusSeconds(4).toString()))
			.expectBody().jsonPath("$.stored").isEqualTo(true);
		// About 5 km in 4 s.
		ping(token, Map.of("latitude", 11.6090, "longitude", 104.9312, "recordedAt", start.plusSeconds(8).toString()))
			.expectBody().jsonPath("$.stored").isEqualTo(false).jsonPath("$.reason").isEqualTo("speed");
		assertThat(redis.opsForHash().get("driver:location:" + driver, "lat")).isEqualTo("11.564");
	}

	@Test
	void theSpeedCheckOnlyComparesWithARecentPosition() {
		UUID driver = UUID.randomUUID();
		String token = TestKeys.token(driver, "mobility-core", "DRIVER");
		Instant old = Instant.now().minusSeconds(180);
		ping(token, Map.of("latitude", 11.5636, "longitude", 104.9312, "recordedAt", old.toString())).expectStatus().isAccepted();

		// 5 km in 3 min is 100 km/h anyway, but 20 km would be 400 km/h: accepted, because the last position is older
		// than the 2-minute window (the stored one may itself have been a jump).
		ping(token, Map.of("latitude", 11.7436, "longitude", 104.9312, "recordedAt", Instant.now().toString()))
			.expectBody().jsonPath("$.stored").isEqualTo(true);
	}

	@Test
	void onlyDriversWithAValidTokenAndAPositionInCambodia() {
		UUID user = UUID.randomUUID();
		Map<String, Object> phnomPenh = Map.of("latitude", 11.5, "longitude", 104.9);

		ping(null, phnomPenh).expectStatus().isUnauthorized();
		ping(TestKeys.token(user, "mobility-core", "PASSENGER"), phnomPenh).expectStatus().isForbidden();
		ping(TestKeys.token(user, "someone-else", "DRIVER"), phnomPenh).expectStatus().isUnauthorized();
		String driver = TestKeys.token(user, "mobility-core", "DRIVER");
		ping(driver, Map.of("latitude", 1.3, "longitude", 103.8)).expectStatus().isBadRequest();
		ping(driver, Map.of("latitude", 11.5, "longitude", 104.9, "recordedAt", Instant.now().minusSeconds(3600).toString()))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("location.recorded-at-invalid");
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();
	}
}

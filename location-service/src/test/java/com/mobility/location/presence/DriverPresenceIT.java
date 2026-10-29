package com.mobility.location.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mobility.location.driver.DriverAccessResolver;
import com.mobility.location.driver.DriverIdentity;
import com.mobility.location.logging.RequestLoggingFilter;
import com.mobility.location.security.JwtDecoderConfig;
import com.mobility.location.security.LocationSecurityConfig;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(classes = DriverPresenceIT.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"location.presence.timeout=60s", "location.jwt.issuer=mobility-core",
                "location.jwt.public-key-location=", "spring.data.redis.password="})
class DriverPresenceIT {

    static final String PATH = "/api/v1/drivers/me/presence";
    static final KeyPair KEYS = keys();
    static final ConcurrentHashMap<String, DriverIdentity> DRIVERS = new ConcurrentHashMap<>();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("location.jwt.public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({DriverPresenceController.class, DriverPresenceService.class, LocationSecurityConfig.class,
            JwtDecoderConfig.class, RequestLoggingFilter.class})
    static class Application {
        @Bean
        DriverAccessResolver driverAccessResolver() {
            return jwt -> {
                DriverIdentity driver = DRIVERS.get(jwt.getSubject());
                if (driver == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
                return driver;
            };
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    StringRedisTemplate redis;

    final UUID userId = UUID.randomUUID();
    final UUID driverId = UUID.randomUUID();
    final String key = "location:driver:{" + driverId + "}:presence";

    @BeforeEach
    void register() {
        DRIVERS.put(userId.toString(), new DriverIdentity(driverId, userId, "PENDING"));
    }

    @AfterEach
    void cleanup() {
        redis.delete(List.of(key, "location:driver:{" + userId + "}:presence"));
        DRIVERS.remove(userId.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED", "SUSPENDED", "REJECTED"})
    void onlineUsesResolvedDriverIdAndWorksRegardlessOfOnboarding(String status) throws Exception {
        DRIVERS.put(userId.toString(), new DriverIdentity(driverId, userId, status));
        String receivedAt = "2026-10-29T07:35:47.099281Z";
        redis.opsForValue().set(key, receivedAt, Duration.ofSeconds(60));
        redis.opsForValue().set("location:driver:{" + userId + "}:presence", "wrong-user-id-key");

        var response = get(PATH, token("DRIVER"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("online", "lastSeenAt", "timeoutSeconds");
        assertThat(body.get("online").asBoolean()).isTrue();
        assertThat(body.get("lastSeenAt").asString()).isEqualTo(receivedAt);
        assertThat(body.get("timeoutSeconds").asLong()).isEqualTo(60);
    }

    @Test
    void missingAndExpiredKeysReturnOfflineWithoutCreatingState() throws Exception {
        assertOffline();
        assertThat(redis.hasKey(key)).isFalse();
        redis.opsForValue().set(key, Instant.now().toString(), Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertOffline();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void repeatedGetsPreserveTheValueAndDoNotRenewItsTtl() throws Exception {
        String receivedAt = Instant.now().toString();
        redis.opsForValue().set(key, receivedAt, Duration.ofSeconds(10));
        Long before = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        for (int i = 0; i < 3; i++) assertThat(get(PATH, token("DRIVER")).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)).isLessThan(before - 50));
        assertThat(redis.opsForValue().get(key)).isEqualTo(receivedAt);
        assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive().isLessThan(before);
    }

    @Test
    void malformedTimestampAndRedisCommandFailureReturn503WithoutRepairingTheKey() throws Exception {
        redis.opsForValue().set(key, "private-invalid-value");
        var malformed = get(PATH, token("DRIVER"));
        assertThat(malformed.statusCode()).isEqualTo(503);
        assertThat(malformed.body()).doesNotContain("private-invalid-value");
        assertThat(redis.opsForValue().get(key)).isEqualTo("private-invalid-value");
        redis.delete(key);
        redis.opsForHash().put(key, "field", "value");
        assertThat(get(PATH, token("DRIVER")).statusCode()).isEqualTo(503);
        assertThat(redis.opsForHash().get(key, "field")).isEqualTo("value");
    }

    @Test
    void jwtAndDriverRoleAreRequiredAndArbitraryDriverRoutesAreNotExposed() throws Exception {
        assertThat(get(PATH, null).statusCode()).isEqualTo(401);
        assertThat(get(PATH, "invalid-token").statusCode()).isEqualTo(401);
        assertThat(get(PATH, token("PASSENGER")).statusCode()).isEqualTo(403);
        assertThat(get(PATH, token("ADMIN")).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/drivers/" + driverId + "/presence", token("DRIVER")).statusCode()).isEqualTo(403);
        DRIVERS.remove(userId.toString());
        assertThat(get(PATH, token("DRIVER")).statusCode()).isEqualTo(404);
    }

    private void assertOffline() throws Exception {
        var response = get(PATH, token("DRIVER"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("online").asBoolean()).isFalse();
        assertThat(body.has("lastSeenAt")).isTrue();
        assertThat(body.get("lastSeenAt").isNull()).isTrue();
        assertThat(body.get("timeoutSeconds").asLong()).isEqualTo(60);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(String role) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .subject(userId.toString()).issuer("mobility-core").claim("roles", List.of(role))
                .expirationTime(Date.from(Instant.now().plusSeconds(120))).build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

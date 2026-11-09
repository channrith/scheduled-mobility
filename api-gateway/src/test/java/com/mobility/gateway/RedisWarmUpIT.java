package com.mobility.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: the gateway connects to Redis and loads the token-bucket script at startup, before any request, so the
 * first request after a restart is counted like any other.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RedisWarmUpIT {

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379);

	static final StubBackend core = new StubBackend("core-app");

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) {
		registry.add("CORE_APP_URL", core::url);
		registry.add("JWT_JWKS_URI", CoreTokens::jwksUri);
	}

	@AfterAll
	static void stop() {
		core.close();
	}

	@Autowired
	StringRedisTemplate redis;

	@Test
	void theScriptIsLoadedBeforeAnyRequest() throws IOException {
		String sha = sha1("ratelimit/token_bucket.lua");

		List<Boolean> exists = redis.execute((RedisCallback<List<Boolean>>) connection -> connection
			.scriptingCommands().scriptExists(sha));

		assertThat(exists).containsExactly(true);
		assertThat(core.hits.get()).as("no request was sent").isZero();
	}

	static String sha1(String resource) throws IOException {
		String script = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

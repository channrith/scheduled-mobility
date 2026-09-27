package com.mobility.core.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.mobility.core.AuthTestClient;
import com.mobility.core.AuthTestClient.Tokens;
import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class IdempotencyIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AuthTestClient auth;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void retriedRefreshReplaysResponseInsteadOfTriggeringReuseDetection() {
		Tokens login = auth.login(SamplePhones.next());
		String key = UUID.randomUUID().toString();

		Tokens first = refresh(login.refreshToken(), key).expectStatus().isOk()
			.expectHeader().doesNotExist(IdempotencyFilter.REPLAYED_HEADER)
			.expectBody(Tokens.class).returnResult().getResponseBody();
		// Client lost the response and retries with the same key.
		Tokens retry = refresh(login.refreshToken(), key).expectStatus().isOk()
			.expectHeader().valueEquals(IdempotencyFilter.REPLAYED_HEADER, "true")
			.expectBody(Tokens.class).returnResult().getResponseBody();

		assertThat(retry).isEqualTo(first);
		// The session survived: the replayed refresh token still works.
		auth.refreshOk(retry.refreshToken());
	}

	@Test
	void cachedResponsesAreEncryptedInRedis() {
		Tokens login = auth.login(SamplePhones.next());
		Tokens refreshed = refresh(login.refreshToken(), UUID.randomUUID().toString()).expectStatus().isOk()
			.expectBody(Tokens.class).returnResult().getResponseBody();

		assertThat(redis.keys("idempotency:*"))
			.allSatisfy(k -> assertThat(redis.opsForValue().get(k)).doesNotContain(refreshed.refreshToken()));
	}

	@Test
	void sameKeyWithDifferentBodyIsRejected() {
		String token = SignedTokens.withRoles(issuer, Role.PASSENGER);
		String key = UUID.randomUUID().toString();
		post("/count", token, key, Map.of("n", 1)).expectStatus().isOk();

		post("/count", token, key, Map.of("n", 2)).expectStatus()
			.isEqualTo(422)
			.expectBody()
			.jsonPath("$.code").isEqualTo("idempotency.key-reused");
	}

	@Test
	void retryExecutesOnceForTheSameUser() {
		String token = SignedTokens.withRoles(issuer, Role.PASSENGER);
		String key = UUID.randomUUID().toString();

		int first = execution(post("/count", token, key, Map.of("n", 1)));
		int retry = execution(post("/count", token, key, Map.of("n", 1)));

		assertThat(retry).isEqualTo(first);
	}

	@Test
	void keysAreScopedPerCaller() {
		String key = UUID.randomUUID().toString();

		int alice = execution(post("/count", SignedTokens.withRoles(issuer, Role.PASSENGER), key, Map.of("n", 1)));
		int bob = execution(post("/count", SignedTokens.withRoles(issuer, Role.PASSENGER), key, Map.of("n", 1)));

		assertThat(bob).isNotEqualTo(alice);
	}

	@Test
	void serverErrorsAreNotStoredSoTheClientCanRetry() {
		String token = SignedTokens.withRoles(issuer, Role.PASSENGER);
		String key = UUID.randomUUID().toString();
		IdempotencyProbeController.failuresLeft.set(1);

		post("/flaky", token, key, Map.of()).expectStatus().is5xxServerError();
		post("/flaky", token, key, Map.of()).expectStatus().isOk()
			.expectHeader().doesNotExist(IdempotencyFilter.REPLAYED_HEADER);
	}

	@Test
	void requestsWithoutKeyAreNotAffected() {
		String token = SignedTokens.withRoles(issuer, Role.PASSENGER);

		int first = execution(post("/count", token, null, Map.of("n", 1)));
		int second = execution(post("/count", token, null, Map.of("n", 1)));

		assertThat(second).isEqualTo(first + 1);
	}

	@Test
	void overlongKeyIsRejected() {
		post("/count", SignedTokens.withRoles(issuer, Role.PASSENGER), "k".repeat(256), Map.of()).expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.code").isEqualTo("idempotency.key-invalid");
	}

	private RestTestClient.ResponseSpec refresh(String refreshToken, String key) {
		return client.post()
			.uri("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.header(IdempotencyFilter.HEADER, key)
			.body(Map.of("refreshToken", refreshToken))
			.exchange();
	}

	private RestTestClient.ResponseSpec post(String path, String token, String key, Map<String, Object> body) {
		var spec = client.post()
			.uri("/api/v1/test/idempotency" + path)
			.contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (key != null) {
			spec = spec.header(IdempotencyFilter.HEADER, key);
		}
		return spec.body(body).exchange();
	}

	@SuppressWarnings("unchecked")
	private static int execution(RestTestClient.ResponseSpec response) {
		Map<String, Object> body = response.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		return ((Number) body.get("execution")).intValue();
	}
}

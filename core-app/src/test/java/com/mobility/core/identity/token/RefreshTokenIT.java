package com.mobility.core.identity.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import com.mobility.core.AuthTestClient;
import com.mobility.core.AuthTestClient.Tokens;
import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.SamplePhones;
import com.mobility.core.identity.user.User;
import com.mobility.core.identity.user.UserRepository;
import com.mobility.core.identity.user.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class RefreshTokenIT {

	@Autowired
	AuthTestClient auth;

	@Autowired
	RestTestClient client;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	@Test
	void refreshRotatesTokens() {
		Tokens login = auth.login(SamplePhones.next());

		Tokens refreshed = auth.refreshOk(login.refreshToken());

		assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
		assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
		// The rotated-out token is dead.
		auth.refresh(login.refreshToken())
			.expectStatus()
			.isUnauthorized()
			.expectBody()
			.jsonPath("$.code").isEqualTo("refresh-token.invalid");
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() {
		Tokens login = auth.login(SamplePhones.next());
		Tokens second = auth.refreshOk(login.refreshToken());
		Tokens third = auth.refreshOk(second.refreshToken());

		// An attacker replays the first token.
		auth.refresh(login.refreshToken()).expectStatus().isUnauthorized();

		// The legitimate client's current token is now revoked too.
		auth.refresh(third.refreshToken()).expectStatus().isUnauthorized();
	}

	@Test
	void reuseInOneSessionDoesNotAffectOtherSessions() {
		String phone = SamplePhones.next();
		Tokens phoneA = auth.login(phone);
		clearOtpCooldown(phone);
		Tokens phoneB = auth.login(phone);

		auth.refreshOk(phoneA.refreshToken());
		auth.refresh(phoneA.refreshToken()).expectStatus().isUnauthorized();

		auth.refreshOk(phoneB.refreshToken());
	}

	@Test
	void onlyTheHashIsStored() {
		Tokens login = auth.login(SamplePhones.next());

		byte[] stored = jdbc.queryForObject("SELECT token_hash FROM identity.refresh_tokens WHERE token_hash = ?",
				byte[].class, RefreshTokenService.hash(login.refreshToken()));
		assertThat(stored).hasSize(32);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.refresh_tokens WHERE encode(token_hash, 'escape') = ?",
				Integer.class, login.refreshToken())).isZero();
	}

	@Test
	void expiredRefreshTokenIsRejected() {
		Tokens login = auth.login(SamplePhones.next());

		clock.advance(Duration.ofDays(30).plusMinutes(1));

		auth.refresh(login.refreshToken()).expectStatus().isUnauthorized();
	}

	@Test
	void suspendedUserCannotRefreshAndSessionIsRevoked() {
		String phone = SamplePhones.next();
		Tokens login = auth.login(phone);
		setStatus(phone, UserStatus.SUSPENDED);

		auth.refresh(login.refreshToken()).expectStatus().isUnauthorized();

		setStatus(phone, UserStatus.ACTIVE);
		// Stays revoked after reactivation: the user must log in again.
		auth.refresh(login.refreshToken()).expectStatus().isUnauthorized();
	}

	@Test
	void logoutRevokesTheSession() {
		Tokens login = auth.login(SamplePhones.next());
		Tokens refreshed = auth.refreshOk(login.refreshToken());

		client.post()
			.uri("/api/v1/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("refreshToken", refreshed.refreshToken()))
			.exchange()
			.expectStatus()
			.isNoContent();

		auth.refresh(refreshed.refreshToken()).expectStatus().isUnauthorized();
	}

	@Test
	void unknownRefreshTokenIsRejected() {
		auth.refresh("definitely-not-a-token").expectStatus().isUnauthorized();
	}

	private void setStatus(String phone, UserStatus status) {
		User user = users.findByPhoneE164(phone).orElseThrow();
		user.changeStatus(status);
		users.save(user);
	}

	private void clearOtpCooldown(String phone) {
		// Second login from "another device" within the resend cooldown.
		redis.delete("identity:otp:cooldown:" + phone);
	}

	@Autowired
	org.springframework.data.redis.core.StringRedisTemplate redis;
}

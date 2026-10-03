package com.mobility.core.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.AuthTestClient;
import com.mobility.core.AuthTestClient.Tokens;
import com.mobility.core.CapturingSmsSender;
import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.SamplePhones;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.user.User;
import com.mobility.core.identity.user.UserRepository;
import com.mobility.core.identity.user.UserStatus;
import com.mobility.core.shared.i18n.Language;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class AuthFlowIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AuthTestClient auth;

	@Autowired
	CapturingSmsSender sms;

	@Autowired
	UserRepository users;

	@Autowired
	JwtDecoder jwtDecoder;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	@Test
	void verifyRegistersNewPassengerAndIssuesTokens() {
		String phone = SamplePhones.next();
		requestOtp(phone);

		Tokens tokens = verify(phone, sms.lastCodeTo(phone), "en").expectStatus()
			.isOk()
			.expectBody(Tokens.class)
			.returnResult()
			.getResponseBody();

		User user = users.findByPhoneE164(phone).orElseThrow();
		assertThat(user.getPreferredLang()).isEqualTo(Language.EN);
		assertThat(tokens.tokenType()).isEqualTo("Bearer");
		assertThat(tokens.expiresIn()).isEqualTo(Duration.ofMinutes(15).toSeconds());
		assertThat(tokens.refreshExpiresIn()).isEqualTo(Duration.ofDays(30).toSeconds());

		Jwt jwt = jwtDecoder.decode(tokens.accessToken());
		assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("PASSENGER");
		assertThat(jwt.getClaimAsString("lang")).isEqualTo("en");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void existingUserTokenCarriesAllRolesAndCorporateScopes() {
		String phone = SamplePhones.next();
		UUID corporateId = UUID.randomUUID();
		User user = User.registerPassenger(phone, Language.KM);
		user.grant(Role.CORPORATE_ADMIN, corporateId);
		users.save(user);

		Jwt jwt = jwtDecoder.decode(auth.login(phone).accessToken());

		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CORPORATE_ADMIN", "PASSENGER");
		assertThat(jwt.getClaimAsMap("corp")).isEqualTo(Map.of(corporateId.toString(), List.of("CORPORATE_ADMIN")));
		assertThat(users.count()).isPositive();
	}

	@Test
	void wrongCodeIsRejectedAndNoUserIsCreated() {
		String phone = SamplePhones.next();
		requestOtp(phone);
		String wrong = sms.lastCodeTo(phone).equals("0000") ? "1111" : "0000";

		verify(phone, wrong, null).expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("otp.invalid");

		assertThat(users.findByPhoneE164(phone)).isEmpty();
	}

	@Test
	void suspendedUserCannotLogIn() {
		String phone = SamplePhones.next();
		User user = User.registerPassenger(phone, Language.KM);
		user.changeStatus(UserStatus.SUSPENDED);
		users.save(user);
		requestOtp(phone);

		verify(phone, sms.lastCodeTo(phone), null).expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.code").isEqualTo("account.inactive");
	}

	@Test
	void protectedEndpointsRequireAValidToken() {
		client.get()
			.uri("/api/v1/me")
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.code").isEqualTo("auth.unauthorized")
			.jsonPath("$.detail").isEqualTo("Please sign in first.");

		client.get()
			.uri("/api/v1/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	@Test
	void accessTokenExpiresAfterFifteenMinutes() {
		String token = auth.login(SamplePhones.next()).accessToken();
		// Authenticated request to an unmapped path: 404 proves the token was accepted.
		client.get().uri("/api/v1/nothing-here").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isNotFound();

		clock.advance(Duration.ofMinutes(16));

		client.get().uri("/api/v1/nothing-here").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
	}

	private void requestOtp(String phone) {
		client.post()
			.uri("/api/v1/auth/otp/request")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("phone", phone))
			.exchange()
			.expectStatus()
			.isAccepted();
	}

	private RestTestClient.ResponseSpec verify(String phone, String code, String language) {
		var spec = client.post().uri("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON);
		if (language != null) {
			spec = spec.header(HttpHeaders.ACCEPT_LANGUAGE, language);
		}
		return spec.body(Map.of("phone", phone, "code", code)).exchange();
	}
}

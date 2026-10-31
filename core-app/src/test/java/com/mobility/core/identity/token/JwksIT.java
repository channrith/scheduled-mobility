package com.mobility.core.identity.token;

import java.time.Clock;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.platform.security.MobilityJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class JwksIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@LocalServerPort
	int port;

	@Test
	void publishesOnlyThePublicKeyThatSignsTokensWithoutLogin() throws Exception {
		String body = client.get().uri(MobilityJwt.JWKS_PATH).exchange()
			.expectStatus().isOk()
			.expectHeader().cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
			.expectBody(String.class).returnResult().getResponseBody();

		JWKSet jwks = JWKSet.parse(body);
		assertThat(jwks.getKeys()).hasSize(1);
		assertThat(jwks.getKeys().getFirst().isPrivate()).isFalse();
		assertThat(body).doesNotContain("\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\"");

		String token = SignedTokens.withRoles(issuer, Role.DRIVER);
		assertThat(SignedJWT.parse(token).getHeader().getKeyID()).isEqualTo(jwks.getKeys().getFirst().getKeyID());
	}

	@Test
	void anotherServiceVerifiesCoreTokensFromTheJwksUrl() {
		JwtDecoder decoder = MobilityJwt.decoder(
				MobilityJwt.KeySource.of("http://localhost:" + port + MobilityJwt.JWKS_PATH, null, null, "JWT_JWKS_URI"),
				MobilityJwt.ISSUER, Clock.systemUTC());

		String token = SignedTokens.withRoles(issuer, Role.DISPATCHER);

		assertThat(decoder.decode(token).getClaimAsStringList(MobilityJwt.ROLES_CLAIM)).containsExactly("DISPATCHER");
	}
}

package com.mobility.platform.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MobilityJwtTest {

	final Clock clock = Clock.systemUTC();

	final TestTokens core = new TestTokens("key-1");

	final Instant inTenMinutes = Instant.now().plusSeconds(600);

	@Test
	void jwksDecoderAcceptsCoreTokensAndMapsRoles() throws Exception {
		HttpServer jwks = TestTokens.jwksServer(core);
		try {
			JwtDecoder decoder = MobilityJwt.decoder(MobilityJwt.KeySource.of(TestTokens.url(jwks), null, null, "JWT_JWKS_URI"),
					MobilityJwt.ISSUER, clock);

			Jwt jwt = decoder.decode(core.token("user-1", MobilityJwt.ISSUER, inTenMinutes, "DRIVER", "ADMIN"));

			assertThat(jwt.getSubject()).isEqualTo("user-1");
			var auth = MobilityJwt.authenticationConverter().convert(jwt);
			assertThat(auth.getName()).isEqualTo("user-1");
			// Spring Security 7 also adds FACTOR_BEARER (how the user authenticated); core-app has always had it.
			assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_DRIVER", "ROLE_ADMIN")
				.allMatch(a -> a.startsWith("ROLE_") || a.equals("FACTOR_BEARER"));
		}
		finally {
			jwks.stop(0);
		}
	}

	@Test
	void jwksDecoderFindsANewKeyAfterRotationWithoutARestart() throws Exception {
		TestTokens rotated = new TestTokens("key-2");
		HttpServer jwks = TestTokens.jwksServer(core, rotated);
		try {
			JwtDecoder decoder = MobilityJwt.decoder(MobilityJwt.KeySource.of(TestTokens.url(jwks), null, null, "JWT_JWKS_URI"),
					MobilityJwt.ISSUER, clock);

			assertThat(decoder.decode(core.token("old-key", MobilityJwt.ISSUER, inTenMinutes)).getSubject()).isEqualTo("old-key");
			assertThat(decoder.decode(rotated.token("new-key", MobilityJwt.ISSUER, inTenMinutes)).getSubject()).isEqualTo("new-key");
		}
		finally {
			jwks.stop(0);
		}
	}

	@Test
	void rejectsForgedExpiredAndForeignTokens() {
		JwtDecoder decoder = MobilityJwt.decoder(MobilityJwt.KeySource.of(null, core.publicPem(), null, "JWT_JWKS_URI"),
				MobilityJwt.ISSUER, clock);
		TestTokens forger = new TestTokens("key-1");

		assertThatThrownBy(() -> decoder.decode(forger.token("u", MobilityJwt.ISSUER, inTenMinutes))).isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> decoder.decode(core.token("u", MobilityJwt.ISSUER, Instant.now().minusSeconds(31))))
			.isInstanceOf(JwtException.class).hasMessageContaining("expired");
		assertThatThrownBy(() -> decoder.decode(core.token("u", "someone-else", inTenMinutes))).isInstanceOf(JwtException.class)
			.hasMessageContaining("iss");
		// Within the 30 s clock skew, a just-expired token still passes.
		assertThat(decoder.decode(core.token("u", MobilityJwt.ISSUER, Instant.now().minusSeconds(10))).getSubject()).isEqualTo("u");
	}

	@Test
	void reactiveDecoderBehavesTheSame() throws Exception {
		HttpServer jwks = TestTokens.jwksServer(core);
		try {
			ReactiveJwtDecoder decoder = ReactiveMobilityJwt.decoder(
					MobilityJwt.KeySource.of(TestTokens.url(jwks), null, null, "JWT_JWKS_URI"), MobilityJwt.ISSUER, clock);

			Jwt jwt = decoder.decode(core.token("user-2", MobilityJwt.ISSUER, inTenMinutes, "DISPATCHER")).block();

			var auth = ReactiveMobilityJwt.authenticationConverter().convert(jwt).block();
			assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_DISPATCHER");
			assertThatThrownBy(() -> decoder.decode(core.token("u", "someone-else", inTenMinutes)).block())
				.isInstanceOf(JwtException.class);
		}
		finally {
			jwks.stop(0);
		}
	}

	@Test
	void keySourceNeedsExactlyOneSetting(@TempDir Path dir) throws Exception {
		Path pemFile = Files.writeString(dir.resolve("jwt-public.pem"), core.publicPem());

		assertThat(MobilityJwt.KeySource.of(null, null, pemFile.toString(), "JWT_JWKS_URI").publicKey()).isEqualTo(core.publicKey());
		assertThatThrownBy(() -> MobilityJwt.KeySource.of(null, null, null, "JWT_JWKS_URI"))
			.hasMessageContaining("Set exactly one of JWT_JWKS_URI");
		assertThatThrownBy(() -> MobilityJwt.KeySource.of("http://core-app:8080/.well-known/jwks.json", core.publicPem(), null, "JWT_JWKS_URI"))
			.hasMessageContaining("Set exactly one of JWT_JWKS_URI");
		assertThatThrownBy(() -> MobilityJwt.KeySource.of(null, "not a key", null, "JWT_JWKS_URI"))
			.hasMessageContaining("Invalid JWT public key");
	}

	@Test
	void pemTakesContentsOrAFileButNotBoth(@TempDir Path dir) throws Exception {
		Path file = Files.writeString(dir.resolve("k.pem"), "PEM");

		assertThat(MobilityJwt.pem("JWT_PUBLIC_KEY", null, file.toString())).isEqualTo("PEM");
		assertThat(MobilityJwt.pem("JWT_PUBLIC_KEY", "INLINE", null)).isEqualTo("INLINE");
		assertThat(MobilityJwt.pem("JWT_PUBLIC_KEY", null, null)).isNull();
		assertThatThrownBy(() -> MobilityJwt.pem("JWT_PUBLIC_KEY", "INLINE", file.toString())).hasMessageContaining("not both");
		assertThatThrownBy(() -> MobilityJwt.pem("JWT_PUBLIC_KEY", null, dir.resolve("missing.pem").toString()))
			.hasMessageContaining("Cannot read JWT_PUBLIC_KEY_FILE");
	}
}

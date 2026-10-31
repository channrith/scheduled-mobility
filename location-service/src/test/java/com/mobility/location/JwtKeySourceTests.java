package com.mobility.location;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.mobility.platform.security.MobilityJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where location-service gets core-app's keys: a JWKS URL (staging from phase 2) or a PEM key, exactly one. */
class JwtKeySourceTests {

	static JwtDecoder decoder(String jwksUri, String publicKey) throws Exception {
        return new com.mobility.location.security.JwtDecoderConfig().jwtDecoder(jwksUri, "", publicKey,
                new org.springframework.core.io.DefaultResourceLoader(), MobilityJwt.ISSUER);
    }

	@Test
	void verifiesDriverTokensWithKeysFromCoreAppsJwksUrl() throws Exception {
		byte[] jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) TestKeys.PAIR.getPublic()).build()).toString()
			.getBytes(StandardCharsets.UTF_8);
		HttpServer core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		core.createContext(MobilityJwt.JWKS_PATH, exchange -> {
			exchange.sendResponseHeaders(200, jwks.length);
			exchange.getResponseBody().write(jwks);
			exchange.close();
		});
		core.start();
		try {
			JwtDecoder decoder = decoder("http://127.0.0.1:" + core.getAddress().getPort() + MobilityJwt.JWKS_PATH, null);
			UUID driver = UUID.randomUUID();

			assertThat(decoder.decode(TestKeys.token(driver, MobilityJwt.ISSUER, "DRIVER")).getSubject()).isEqualTo(driver.toString());
		}
		finally {
			core.stop(0);
		}
	}

	@Test
	void needsExactlyOneKeySource() {
		assertThatThrownBy(() -> decoder(null, null))
			.hasMessageContaining("Set exactly one of JWT_JWKS_URI");
		assertThatThrownBy(() -> decoder("http://core-app:8080/.well-known/jwks.json", TestKeys.publicPem()))
			.hasMessageContaining("Set exactly one of JWT_JWKS_URI");
	}
}

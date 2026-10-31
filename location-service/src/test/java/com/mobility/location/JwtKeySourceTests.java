package com.mobility.location;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.mobility.platform.security.MobilityJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code JWT_JWKS_URI} (staging from phase 2), keys come from core-app's JWKS; platform-security builds the decoder. */
@SpringBootTest
class JwtKeySourceTests {

	static final HttpServer core = jwks();

	static HttpServer jwks() {
		try {
			byte[] body = new JWKSet(new RSAKey.Builder((RSAPublicKey) TestKeys.PAIR.getPublic()).build()).toString()
				.getBytes(StandardCharsets.UTF_8);
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext(MobilityJwt.JWKS_PATH, exchange -> {
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
			return server;
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void jwksUri(DynamicPropertyRegistry registry) {
		registry.add("mobility.jwt.jwks-uri", () -> "http://127.0.0.1:" + core.getAddress().getPort() + MobilityJwt.JWKS_PATH);
	}

	@AfterAll
	static void stop() {
		core.stop(0);
	}

	@Autowired
	JwtDecoder decoder;

	@Test
	void verifiesDriverTokensWithKeysFromCoreAppsJwksUrl() {
		UUID driver = UUID.randomUUID();

		assertThat(decoder.decode(TestKeys.token(driver, MobilityJwt.ISSUER, "DRIVER")).getSubject()).isEqualTo(driver.toString());
	}
}

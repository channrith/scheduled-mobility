package com.mobility.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.mobility.platform.security.MobilityJwt;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for core-app's token side in gateway tests: a throwaway signing key, tokens signed with it, and the
 * JWKS endpoint serving its public key (started once, for the whole test run).
 */
final class CoreTokens {

	static final CoreTokens CORE = new CoreTokens("core-key", true);

	/** Same claims, a key core-app never published: a forgery. */
	static final CoreTokens FORGER = new CoreTokens("core-key", false);

	private final KeyPair pair;

	private final String kid;

	private HttpServer jwks;

	private CoreTokens(String kid, boolean serveJwks) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			this.pair = generator.generateKeyPair();
			this.kid = kid;
			if (serveJwks) {
				byte[] body = new JWKSet(new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID(kid).build()).toString()
					.getBytes(StandardCharsets.UTF_8);
				jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				jwks.createContext(MobilityJwt.JWKS_PATH, exchange -> {
					exchange.sendResponseHeaders(200, body.length);
					exchange.getResponseBody().write(body);
					exchange.close();
				});
				jwks.start();
			}
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static String jwksUri() {
		return "http://127.0.0.1:" + CORE.jwks.getAddress().getPort() + MobilityJwt.JWKS_PATH;
	}

	/** A valid passenger token for ten minutes. */
	static String valid() {
		return CORE.token("user-1", MobilityJwt.ISSUER, Instant.now().plusSeconds(600), "PASSENGER");
	}

	String token(String subject, String issuer, Instant expires, String... roles) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).claim(MobilityJwt.ROLES_CLAIM, List.of(roles))
				.issueTime(Date.from(expires.minusSeconds(900))).expirationTime(Date.from(expires)).build();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
			jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
			return jwt.serialize();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}

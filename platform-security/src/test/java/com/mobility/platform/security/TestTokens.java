package com.mobility.platform.security;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/** A throwaway signing key standing in for core-app's, tokens signed with it, and a tiny JWKS server for it. */
final class TestTokens {

	final KeyPair pair;

	final String kid;

	TestTokens(String kid) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			this.pair = generator.generateKeyPair();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
		this.kid = kid;
	}

	RSAPublicKey publicKey() {
		return (RSAPublicKey) pair.getPublic();
	}

	String publicPem() {
		return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----\n";
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

	/** Serves the public keys of the given signers as core-app would; close it after the test. */
	static HttpServer jwksServer(TestTokens... signers) throws Exception {
		List<com.nimbusds.jose.jwk.JWK> keys = new java.util.ArrayList<>();
		for (TestTokens signer : signers) {
			keys.add(new RSAKey.Builder(signer.publicKey()).keyID(signer.kid).build());
		}
		byte[] body = new JWKSet(keys).toString().getBytes(StandardCharsets.UTF_8);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(MobilityJwt.JWKS_PATH, exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		return server;
	}

	static String url(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + MobilityJwt.JWKS_PATH;
	}
}

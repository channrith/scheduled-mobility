package com.mobility.location;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** A throwaway RSA pair standing in for core-app's signing key; tests sign tokens with it. */
public final class TestKeys {

	static final KeyPair PAIR;

	static {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			PAIR = generator.generateKeyPair();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private TestKeys() {
	}

	public static String publicPem() {
		return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(PAIR.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----\n";
	}

	public static String token(UUID userId, String issuer, String... roles) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(userId.toString()).issuer(issuer).claim("roles", List.of(roles))
				.issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
			jwt.sign(new RSASSASigner((RSAPrivateKey) PAIR.getPrivate()));
			return jwt.serialize();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}

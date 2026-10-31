package com.mobility.core.identity.token;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;

import com.mobility.platform.security.MobilityJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256 signing keys and the JWT encoder/decoder built from them. Only core-app holds the private key; the public
 * key is published at {@link MobilityJwt#JWKS_PATH} for the other services. Verification rules are shared with them
 * ({@link MobilityJwt}).
 */
@Configuration(proxyBeanMethods = false)
class JwtConfiguration {

	private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

	@Bean
	RSAKey jwtSigningKey(TokenProperties props) throws com.nimbusds.jose.JOSEException {
		String privatePem = MobilityJwt.pem("JWT_PRIVATE_KEY", props.privateKey(), props.privateKeyFile());
		String publicPem = MobilityJwt.pem("JWT_PUBLIC_KEY", props.publicKey(), props.publicKeyFile());
		KeyPair keys;
		if (privatePem != null && publicPem != null) {
			keys = new KeyPair(MobilityJwt.parsePublicKey(publicPem), parsePrivateKey(privatePem));
		}
		else if (privatePem != null || publicPem != null) {
			throw new IllegalStateException("Configure both JWT private and public keys, not just one");
		}
		else if (props.allowGeneratedKeys()) {
			log.warn("No JWT keys configured; generated a temporary RSA key pair. Tokens will not survive a restart.");
			keys = generateKeyPair();
		}
		else {
			throw new IllegalStateException("JWT keys missing: set JWT_PRIVATE_KEY_FILE and JWT_PUBLIC_KEY_FILE "
					+ "(paths) or JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (PEM contents), see README");
		}
		RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
		return new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) keys.getPrivate())
			.keyIDFromThumbprint()
			.build();
	}

	@Bean
	JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
	}

	@Bean
	JwtDecoder jwtDecoder(RSAKey jwtSigningKey, TokenProperties props, Clock clock) throws Exception {
		// Its own key directly: no need to fetch its own JWKS.
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build();
		decoder.setJwtValidator(MobilityJwt.validator(props.issuer(), clock));
		return decoder;
	}

	private static RSAPrivateKey parsePrivateKey(String pem) {
		try {
			return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(MobilityJwt.pemBody(pem)));
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Invalid JWT private key (expected PKCS#8 PEM)", ex);
		}
	}

	private static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}
}

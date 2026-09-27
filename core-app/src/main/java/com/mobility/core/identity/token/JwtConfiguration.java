package com.mobility.core.identity.token;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/** RS256 signing keys and the JWT encoder/decoder built from them. */
@Configuration(proxyBeanMethods = false)
class JwtConfiguration {

	private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

	@Bean
	RSAKey jwtSigningKey(TokenProperties props) throws com.nimbusds.jose.JOSEException {
		String privatePem = pem("JWT_PRIVATE_KEY", props.privateKey(), props.privateKeyFile());
		String publicPem = pem("JWT_PUBLIC_KEY", props.publicKey(), props.publicKeyFile());
		KeyPair keys;
		if (privatePem != null && publicPem != null) {
			keys = new KeyPair(parsePublicKey(publicPem), parsePrivateKey(privatePem));
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
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
		timestamps.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(props.issuer())));
		return decoder;
	}

	/** PEM from inline contents or a file; setting both is ambiguous and rejected. */
	static String pem(String name, String inline, String file) {
		boolean hasInline = StringUtils.hasText(inline);
		boolean hasFile = StringUtils.hasText(file);
		if (hasInline && hasFile) {
			throw new IllegalStateException("Set either " + name + " or " + name + "_FILE, not both");
		}
		if (hasFile) {
			Path path = Path.of(file.strip()).toAbsolutePath().normalize();
			try {
				return Files.readString(path);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Cannot read " + name + "_FILE at " + path, ex);
			}
		}
		return hasInline ? inline : null;
	}

	private static RSAPublicKey parsePublicKey(String pem) {
		try {
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pemBody(pem)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Invalid JWT public key (expected X.509 PEM)", ex);
		}
	}

	private static RSAPrivateKey parsePrivateKey(String pem) {
		try {
			return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pemBody(pem)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Invalid JWT private key (expected PKCS#8 PEM)", ex);
		}
	}

	private static byte[] pemBody(String pem) {
		String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
		return Base64.getDecoder().decode(body);
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

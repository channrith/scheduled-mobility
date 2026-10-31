package com.mobility.platform.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.util.StringUtils;

/**
 * How every service verifies core-app's access tokens: RS256, issuer {@code mobility-core}, 30 s clock skew, and
 * the {@code roles} claim as {@code ROLE_*} authorities. Only core-app signs tokens; everyone else gets its public
 * keys from its JWKS endpoint ({@link #JWKS_PATH}) or, as a fallback, from a PEM key.
 */
public final class MobilityJwt {

	public static final String ISSUER = "mobility-core";

	public static final String ROLES_CLAIM = "roles";

	public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

	/** Where core-app publishes its public key(s); internal network only, the gateway does not route it. */
	public static final String JWKS_PATH = "/.well-known/jwks.json";

	private MobilityJwt() {
	}

	/** Expiry and not-before with {@link #CLOCK_SKEW}, plus the issuer. */
	public static OAuth2TokenValidator<Jwt> validator(String issuer, Clock clock) {
		JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
		timestamps.setClock(clock);
		return new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer));
	}

	/** A decoder for one of the {@link KeySource}s, with {@link #validator}. */
	public static JwtDecoder decoder(KeySource keys, String issuer, Clock clock) {
		NimbusJwtDecoder decoder = keys.jwksUri() != null ? NimbusJwtDecoder.withJwkSetUri(keys.jwksUri()).build()
				: NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
		decoder.setJwtValidator(validator(issuer, clock));
		return decoder;
	}

	/** Maps the {@code roles} claim to {@code ROLE_*} authorities; the principal name is the user id (subject). */
	public static JwtAuthenticationConverter authenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(ROLES_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	/**
	 * Where a service gets core-app's public keys: a JWKS URL (preferred: key rotation needs no change here), or one
	 * PEM key (tests, and services not yet switched).
	 */
	public record KeySource(String jwksUri, RSAPublicKey publicKey) {

		/**
		 * From a service's settings; exactly one of the three must be set.
		 *
		 * @param setting how the settings are named in this service's error messages, e.g. "JWT_JWKS_URI"
		 */
		public static KeySource of(String jwksUri, String publicKeyPem, String publicKeyFile, String setting) {
			boolean uri = StringUtils.hasText(jwksUri);
			boolean inline = StringUtils.hasText(publicKeyPem);
			boolean file = StringUtils.hasText(publicKeyFile);
			if ((uri ? 1 : 0) + (inline ? 1 : 0) + (file ? 1 : 0) != 1) {
				throw new IllegalStateException("Set exactly one of " + setting + " (core-app's JWKS URL, e.g. http://core-app:8080"
						+ JWKS_PATH + "), JWT_PUBLIC_KEY (PEM) or JWT_PUBLIC_KEY_FILE (path)");
			}
			if (uri) {
				return new KeySource(jwksUri.strip(), null);
			}
			return new KeySource(null, parsePublicKey(inline ? publicKeyPem : readFile("JWT_PUBLIC_KEY_FILE", publicKeyFile)));
		}
	}

	/** PEM from inline contents or a file; setting both is ambiguous and rejected. Null when neither is set. */
	public static String pem(String name, String inline, String file) {
		boolean hasInline = StringUtils.hasText(inline);
		boolean hasFile = StringUtils.hasText(file);
		if (hasInline && hasFile) {
			throw new IllegalStateException("Set either " + name + " or " + name + "_FILE, not both");
		}
		if (hasFile) {
			return readFile(name + "_FILE", file);
		}
		return hasInline ? inline : null;
	}

	public static RSAPublicKey parsePublicKey(String pem) {
		try {
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pemBody(pem)));
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Invalid JWT public key (expected X.509 PEM)", ex);
		}
	}

	/** The base64 body of a PEM block. */
	public static byte[] pemBody(String pem) {
		return Base64.getDecoder().decode(pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", ""));
	}

	private static String readFile(String setting, String file) {
		Path path = Path.of(file.strip()).toAbsolutePath().normalize();
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read " + setting + " at " + path, ex);
		}
	}
}

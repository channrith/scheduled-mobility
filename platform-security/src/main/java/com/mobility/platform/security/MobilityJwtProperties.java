package com.mobility.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where a service gets core-app's public keys: exactly one of {@code jwksUri} (preferred; key rotation needs no
 * change here), {@code publicKey} (PEM contents) or {@code publicKeyFile}. Services map these to environment
 * variables in their {@code application.yml} (usually {@code JWT_JWKS_URI}, {@code JWT_PUBLIC_KEY},
 * {@code JWT_PUBLIC_KEY_FILE}).
 */
@ConfigurationProperties("mobility.jwt")
public record MobilityJwtProperties(String jwksUri, String publicKey, String publicKeyFile,
		@DefaultValue(MobilityJwt.ISSUER) String issuer) {

	MobilityJwt.KeySource keySource() {
		return MobilityJwt.KeySource.of(jwksUri, publicKey, publicKeyFile, "JWT_JWKS_URI");
	}
}

package com.mobility.core.identity.token;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param privateKey RSA private key (PKCS#8 PEM) used to sign access tokens
 * @param publicKey matching RSA public key (X.509 PEM); location-service only needs this one
 * @param privateKeyFile path to a PEM file, as an alternative to {@code privateKey} (e.g. a mounted
 * secret); relative paths resolve against the working directory
 * @param publicKeyFile path to a PEM file, as an alternative to {@code publicKey}
 * @param allowGeneratedKeys generate a throwaway key pair when no keys are configured (local/test
 * only: tokens become invalid on restart)
 */
@ConfigurationProperties("identity.token")
public record TokenProperties(
		@DefaultValue("mobility-core") String issuer,
		@DefaultValue("15m") Duration accessTokenTtl,
		@DefaultValue("30d") Duration refreshTokenTtl,
		String privateKey,
		String publicKey,
		String privateKeyFile,
		String publicKeyFile,
		@DefaultValue("false") boolean allowGeneratedKeys) {
}

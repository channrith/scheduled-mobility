package com.mobility.location;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("location")
public record LocationProperties(@DefaultValue Jwt jwt, @DefaultValue("24h") Duration positionTtl) {

	/**
	 * Where core-app's public keys come from: exactly one of {@code jwksUri} (preferred), {@code publicKey} (PEM) or
	 * {@code publicKeyFile}.
	 */
	public record Jwt(String jwksUri, String publicKey, String publicKeyFile, @DefaultValue("mobility-core") String issuer) {
	}
}

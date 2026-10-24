package com.mobility.location;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("location")
public record LocationProperties(@DefaultValue Jwt jwt, @DefaultValue("24h") Duration positionTtl) {

	public record Jwt(String publicKey, String publicKeyFile, @DefaultValue("mobility-core") String issuer) {
	}
}

package com.mobility.location.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import com.mobility.platform.security.MobilityJwt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;

@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${location.jwt.jwks-uri:}") String jwksUri,
            @Value("${location.jwt.public-key-location:${JWT_PUBLIC_KEY_FILE:}}") String keyLocation,
            @Value("${location.jwt.public-key:}") String keyPem,
            ResourceLoader resourceLoader,
            @Value("${location.jwt.issuer:mobility-core}") String issuer
    ) throws IOException {
        int sources = (StringUtils.hasText(jwksUri) ? 1 : 0)
                + (StringUtils.hasText(keyLocation) ? 1 : 0)
                + (StringUtils.hasText(keyPem) ? 1 : 0);
        if (sources != 1) {
            throw new IllegalStateException("Set exactly one of JWT_JWKS_URI, JWT_PUBLIC_KEY or "
                    + "JWT_PUBLIC_KEY_LOCATION / JWT_PUBLIC_KEY_FILE (missing or ambiguous key source)");
        }
        if (StringUtils.hasText(keyLocation)) {
            String location = keyLocation.strip();
            if (!location.contains(":")) location = "file:" + location;
            try (var input = resourceLoader.getResource(location).getInputStream()) {
                keyPem = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return MobilityJwt.decoder(MobilityJwt.KeySource.of(jwksUri, keyPem, null, "JWT_JWKS_URI"),
                issuer, Clock.systemUTC());
    }
}

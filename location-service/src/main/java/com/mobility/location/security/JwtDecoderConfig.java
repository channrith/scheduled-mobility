package com.mobility.location.security;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${location.jwt.public-key-location}")
            Resource keyResource,
            @Value("${location.jwt.issuer}")
            String issuer
    ) throws IOException {

        RSAPublicKey publicKey;

        try (var input = keyResource.getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(input);
        }

        if (publicKey == null) {
            throw new IllegalStateException(
                    "JWT public key could not be loaded"
            );
        }

        var decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        var timestamps =
                new JwtTimestampValidator(Duration.ofSeconds(30));

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamps,
                        new JwtIssuerValidator(issuer)
                )
        );

        return decoder;
    }
}
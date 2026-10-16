package com.mobility.location.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${location.jwt.public-key-location}")
            String keyLocation,
            @Value("${location.jwt.public-key}")
            String keyPem,
            ResourceLoader resourceLoader,
            @Value("${location.jwt.issuer}")
            String issuer
    ) throws IOException, GeneralSecurityException {

        RSAPublicKey publicKey;
        if (StringUtils.hasText(keyPem) && StringUtils.hasText(keyLocation)) {
            throw new IllegalStateException("Set either JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_LOCATION / JWT_PUBLIC_KEY_FILE, not both");
        }
        if (StringUtils.hasText(keyPem)) {
            String body = keyPem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
            publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } else if (StringUtils.hasText(keyLocation)) {
            String location = keyLocation.strip();
            if (!location.contains(":")) {
                location = "file:" + location;
            }
            try (var input = resourceLoader.getResource(location).getInputStream()) {
                publicKey = RsaKeyConverters.x509().convert(input);
            }
        } else {
            throw new IllegalStateException("JWT public key missing: set JWT_PUBLIC_KEY, JWT_PUBLIC_KEY_LOCATION or JWT_PUBLIC_KEY_FILE (local)");
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

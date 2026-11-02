package com.mobility.location;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

final class JwtTestSupport {

    static void register(DynamicPropertyRegistry registry) {
        registry.add("mobility.jwt.jwks-uri", () -> "");
        registry.add("mobility.jwt.public-key", JwtTestSupport::publicKey);
        registry.add("JWT_PUBLIC_KEY_LOCATION", () -> "");
        registerEnvironment(registry);
    }

    static void registerEnvironment(DynamicPropertyRegistry registry) {
        registry.add("CORE_BASE_URL", () -> "http://localhost:8080");
        registry.add("REDIS_HOST", () -> "localhost");
        registry.add("REDIS_PASSWORD", () -> "test");
    }

    private static String publicKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

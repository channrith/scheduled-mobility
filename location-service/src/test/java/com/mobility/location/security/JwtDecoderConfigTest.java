package com.mobility.location.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderConfigTest {

    private static KeyPair keys;
    private final JwtDecoderConfig config = new JwtDecoderConfig();
    private final DefaultResourceLoader resources = new DefaultResourceLoader();

    @BeforeAll
    static void generateKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    void stagingInlinePemVerifiesCoreTokensAndRejectsWrongIssuer() throws Exception {
        var decoder = config.jwtDecoder("", pem(" "), resources, "mobility-core");
        assertThat(decoder.decode(token("mobility-core")).getSubject()).isEqualTo("driver");
        assertThatThrownBy(() -> decoder.decode(token("another-issuer")))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void localFileAndResourcePathsVerifyTheSameTokens(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("public.pem");
        Files.writeString(file, pem("\n"));
        for (String location : new String[] {file.toString(), file.toUri().toString()}) {
            var decoder = config.jwtDecoder(location, "", resources, "mobility-core");
            assertThat(decoder.decode(token("mobility-core")).getSubject()).isEqualTo("driver");
        }
    }

    @Test
    void missingOrAmbiguousKeysFailClearly() {
        assertThatThrownBy(() -> config.jwtDecoder("", "", resources, "mobility-core"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> config.jwtDecoder("file:public.pem", pem("\n"), resources, "mobility-core"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not both");
    }

    private static String pem(String separator) {
        return "-----BEGIN PUBLIC KEY-----" + separator
                + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + separator
                + "-----END PUBLIC KEY-----";
    }

    private static String token(String issuer) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(issuer).subject("driver").expirationTime(Date.from(Instant.now().plusSeconds(60))).build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }
}

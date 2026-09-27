package com.mobility.core.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeyLoadingTest {

	static KeyPair keys;

	static String privatePem;

	static String publicPem;

	@TempDir
	Path dir;

	@BeforeAll
	static void generate() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		privatePem = pem("PRIVATE KEY", keys.getPrivate().getEncoded());
		publicPem = pem("PUBLIC KEY", keys.getPublic().getEncoded());
	}

	@Test
	void loadsKeysFromFiles() throws Exception {
		Path privateFile = Files.writeString(dir.resolve("jwt-private.pem"), privatePem);
		Path publicFile = Files.writeString(dir.resolve("jwt-public.pem"), publicPem);

		RSAKey key = load(null, null, privateFile.toString(), publicFile.toString(), false);

		assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) keys.getPublic()).getModulus());
		assertThat(key.isPrivate()).isTrue();
	}

	@Test
	void loadsInlinePemContents() throws Exception {
		RSAKey key = load(privatePem, publicPem, null, null, false);

		assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) keys.getPublic()).getModulus());
	}

	@Test
	void loadsInlineBodyWithoutHeadersOnOneLine() throws Exception {
		String privateBody = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
		String publicBody = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());

		assertThat(load(privateBody, publicBody, null, null, false).isPrivate()).isTrue();
	}

	@Test
	void rejectsInlineAndFileForTheSameKey() throws Exception {
		Path privateFile = Files.writeString(dir.resolve("jwt-private.pem"), privatePem);

		assertThatThrownBy(() -> load(privatePem, publicPem, privateFile.toString(), null, false))
			.hasMessageContaining("JWT_PRIVATE_KEY or JWT_PRIVATE_KEY_FILE");
	}

	@Test
	void missingFileNamesTheResolvedPath() {
		assertThatThrownBy(() -> load(null, null, dir.resolve("nope.pem").toString(), "x", false))
			.hasMessageContaining("JWT_PRIVATE_KEY_FILE")
			.hasMessageContaining(dir.resolve("nope.pem").toString());
	}

	@Test
	void onlyOneKeyConfiguredIsAnError() throws Exception {
		Path privateFile = Files.writeString(dir.resolve("jwt-private.pem"), privatePem);

		assertThatThrownBy(() -> load(null, null, privateFile.toString(), null, true))
			.hasMessageContaining("both");
	}

	@Test
	void noKeysFailsUnlessGenerationIsAllowed() throws Exception {
		assertThatThrownBy(() -> load(null, null, null, null, false)).hasMessageContaining("JWT keys missing");
		assertThat(load(null, null, null, null, true).isPrivate()).isTrue();
	}

	private static RSAKey load(String privateKey, String publicKey, String privateFile, String publicFile,
			boolean allowGenerated) throws Exception {
		TokenProperties props = new TokenProperties("test", Duration.ofMinutes(15), Duration.ofDays(30), privateKey,
				publicKey, privateFile, publicFile, allowGenerated);
		return new JwtConfiguration().jwtSigningKey(props);
	}

	private static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
	}
}

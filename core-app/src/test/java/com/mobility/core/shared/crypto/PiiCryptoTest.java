package com.mobility.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PiiCryptoTest {

	static final String KEY = "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=";

	final PiiCrypto crypto = new PiiCrypto(new PiiProperties(KEY, "test-pii-hash-secret-0123456789abcdef"));

	@Test
	void roundTripsAndNeverStoresPlaintext() {
		byte[] sealed = crypto.encrypt("123456789");

		assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("123456789");
		assertThat(sealed[0]).isEqualTo((byte) 1);
		assertThat(crypto.decrypt(sealed)).isEqualTo("123456789");
	}

	@Test
	void sameValueEncryptsDifferentlyEachTime() {
		assertThat(crypto.encrypt("123456789")).isNotEqualTo(crypto.encrypt("123456789"));
	}

	@Test
	void nullStaysNull() {
		assertThat(crypto.encrypt(null)).isNull();
		assertThat(crypto.decrypt(null)).isNull();
	}

	@Test
	void tamperedCiphertextIsRejected() {
		byte[] sealed = crypto.encrypt("123456789");
		sealed[sealed.length - 1] ^= 1;

		assertThatThrownBy(() -> crypto.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void bytesAreBoundToTheirContext() {
		byte[] sealed = crypto.encryptBytes(new byte[] { 1, 2, 3 }, "a".getBytes());

		assertThat(crypto.decryptBytes(sealed, "a".getBytes())).containsExactly(1, 2, 3);
		assertThatThrownBy(() -> crypto.decryptBytes(sealed, "b".getBytes())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void blindIndexIsDeterministicAndDependsOnTheSecret() {
		PiiCrypto other = new PiiCrypto(new PiiProperties(KEY, "another-pii-hash-secret-0123456789abcd"));

		assertThat(crypto.blindIndex("123456789")).isEqualTo(crypto.blindIndex("123456789"))
			.isNotEqualTo(crypto.blindIndex("123456780"))
			.isNotEqualTo(other.blindIndex("123456789"));
	}
}

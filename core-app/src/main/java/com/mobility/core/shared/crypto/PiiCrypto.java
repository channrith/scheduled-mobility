package com.mobility.core.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Application-layer encryption of personal data. Ciphertexts are prefixed with a key-version byte so
 * the key can be rotated later (decrypt with the old version, re-encrypt with the new one).
 */
@Component
public class PiiCrypto {

	private static final byte KEY_VERSION = 1;

	private static final byte[] FIELD_CONTEXT = "pii-field".getBytes(StandardCharsets.UTF_8);

	private final AesGcm aes;

	private final byte[] hashSecret;

	public PiiCrypto(PiiProperties props) {
		this.aes = AesGcm.fromBase64Key(props.encryptionKey());
		this.hashSecret = props.hashSecret().getBytes(StandardCharsets.UTF_8);
	}

	public byte[] encrypt(String plaintext) {
		return plaintext == null ? null : encryptBytes(plaintext.getBytes(StandardCharsets.UTF_8), FIELD_CONTEXT);
	}

	public String decrypt(byte[] ciphertext) {
		return ciphertext == null ? null
				: new String(decryptBytes(ciphertext, FIELD_CONTEXT), StandardCharsets.UTF_8);
	}

	/** @param context bound into the ciphertext (e.g. a storage key); decryption needs the same value */
	public byte[] encryptBytes(byte[] plaintext, byte[] context) {
		byte[] sealed = aes.encrypt(plaintext, context);
		return ByteBuffer.allocate(1 + sealed.length).put(KEY_VERSION).put(sealed).array();
	}

	public byte[] decryptBytes(byte[] ciphertext, byte[] context) {
		if (ciphertext.length < 1 || ciphertext[0] != KEY_VERSION) {
			throw new IllegalStateException("Unknown PII key version");
		}
		return aes.decrypt(Arrays.copyOfRange(ciphertext, 1, ciphertext.length), context);
	}

	/**
	 * Deterministic keyed hash of an already-normalized value, for unique indexes and lookups on
	 * encrypted columns without decrypting them.
	 */
	public byte[] blindIndex(String normalizedValue) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(hashSecret, "HmacSHA256"));
			return mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}
}

package com.mobility.core.shared.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM authenticated encryption. Output layout: 12-byte IV followed by ciphertext and tag.
 * {@code aad} binds a ciphertext to its context (e.g. its storage key) so it cannot be moved.
 */
public final class AesGcm {

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	private AesGcm(byte[] key) {
		if (key.length != 32) {
			throw new IllegalArgumentException("AES-256 key must be 32 bytes, got " + key.length);
		}
		this.key = new SecretKeySpec(key, "AES");
	}

	/** @param base64Key 32 random bytes, base64-encoded ({@code openssl rand -base64 32}) */
	public static AesGcm fromBase64Key(String base64Key) {
		if (base64Key == null || base64Key.isBlank()) {
			throw new IllegalArgumentException("Encryption key is not configured");
		}
		return new AesGcm(Base64.getDecoder().decode(base64Key.strip()));
	}

	public byte[] encrypt(byte[] plaintext, byte[] aad) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(aad);
			byte[] ciphertext = cipher.doFinal(plaintext);
			return ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Encryption failed", ex);
		}
	}

	/** @throws IllegalStateException if the data was tampered with or the key/aad do not match */
	public byte[] decrypt(byte[] data, byte[] aad) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
			cipher.updateAAD(aad);
			return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Decryption failed", ex);
		}
	}
}

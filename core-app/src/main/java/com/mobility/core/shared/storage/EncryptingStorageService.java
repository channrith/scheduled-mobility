package com.mobility.core.shared.storage;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.mobility.core.shared.crypto.PiiCrypto;

/**
 * Encrypts every object before it reaches the underlying store. The storage key is bound into the
 * ciphertext, so a blob copied to another key will not decrypt.
 */
class EncryptingStorageService implements StorageService {

	private final StorageService delegate;

	private final PiiCrypto crypto;

	EncryptingStorageService(StorageService delegate, PiiCrypto crypto) {
		this.delegate = delegate;
		this.crypto = crypto;
	}

	@Override
	public void put(String key, byte[] content) {
		delegate.put(key, crypto.encryptBytes(content, context(key)));
	}

	@Override
	public Optional<byte[]> get(String key) {
		return delegate.get(key).map(data -> crypto.decryptBytes(data, context(key)));
	}

	@Override
	public void delete(String key) {
		delegate.delete(key);
	}

	@Override
	public boolean exists(String key) {
		return delegate.exists(key);
	}

	private static byte[] context(String key) {
		return ("storage:" + key).getBytes(StandardCharsets.UTF_8);
	}
}

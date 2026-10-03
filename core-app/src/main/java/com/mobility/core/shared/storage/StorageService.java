package com.mobility.core.shared.storage;

import java.util.Optional;

/**
 * Binary object storage. Keys are slash-separated paths such as {@code drivers/<id>/<documentId>}.
 * The configured implementation encrypts content at rest.
 */
public interface StorageService {

	void put(String key, byte[] content);

	Optional<byte[]> get(String key);

	void delete(String key);

	boolean exists(String key);
}

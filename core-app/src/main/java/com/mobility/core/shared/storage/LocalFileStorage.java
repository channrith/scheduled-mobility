package com.mobility.core.shared.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** Development storage on the local filesystem. An S3-compatible implementation replaces it later. */
class LocalFileStorage implements StorageService {

	private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]+(/[A-Za-z0-9_.-]+)*$");

	private final Path root;

	LocalFileStorage(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	@Override
	public void put(String key, byte[] content) {
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
			Files.write(temp, content);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot store " + key, ex);
		}
	}

	@Override
	public Optional<byte[]> get(String key) {
		Path target = resolve(key);
		try {
			return Files.exists(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + key, ex);
		}
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot delete " + key, ex);
		}
	}

	@Override
	public boolean exists(String key) {
		return Files.exists(resolve(key));
	}

	private Path resolve(String key) {
		if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
			throw new IllegalArgumentException("Invalid storage key: " + key);
		}
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("Invalid storage key: " + key);
		}
		return path;
	}
}

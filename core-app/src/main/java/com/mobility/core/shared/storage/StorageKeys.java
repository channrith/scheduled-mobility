package com.mobility.core.shared.storage;

import java.util.regex.Pattern;

/** Storage keys are slash-separated paths of safe characters, identical for every backend. */
final class StorageKeys {

	private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]+(/[A-Za-z0-9_.-]+)*$");

	private StorageKeys() {
	}

	static String requireValid(String key) {
		if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
			throw new IllegalArgumentException("Invalid storage key: " + key);
		}
		return key;
	}
}

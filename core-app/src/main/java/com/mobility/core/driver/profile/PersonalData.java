package com.mobility.core.driver.profile;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Normalization and masking of national ID and bank account numbers. */
final class PersonalData {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-./]");

	private static final Pattern NATIONAL_ID = Pattern.compile("^[A-Z0-9]{5,20}$");

	private static final Pattern BANK_ACCOUNT = Pattern.compile("^[A-Z0-9]{4,34}$");

	private PersonalData() {
	}

	/** {@code "0123 456-78"} → {@code "012345678"}. Blank means "not provided". */
	static Optional<String> normalizeNationalId(String raw) {
		return normalize(raw, NATIONAL_ID);
	}

	static Optional<String> normalizeBankAccount(String raw) {
		return normalize(raw, BANK_ACCOUNT);
	}

	static boolean isBlank(String raw) {
		return raw == null || raw.isBlank();
	}

	/** Keeps the last 4 characters: {@code "012345678"} → {@code "•••••5678"}. */
	static String mask(String value) {
		if (value == null) {
			return null;
		}
		int visible = Math.min(4, value.length() / 2);
		return "•".repeat(value.length() - visible) + value.substring(value.length() - visible);
	}

	private static Optional<String> normalize(String raw, Pattern pattern) {
		String s = SEPARATORS.matcher(raw.strip()).replaceAll("").toUpperCase(Locale.ROOT);
		return pattern.matcher(s).matches() ? Optional.of(s) : Optional.empty();
	}
}

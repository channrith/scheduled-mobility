package com.mobility.core.identity.otp;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizes user-entered phone numbers to E.164. Cambodian local formats ({@code 012 345 678},
 * {@code 855 12 345 678}) are converted to {@code +855...}; other countries must be entered with
 * {@code +} or {@code 00}.
 */
public final class PhoneNumbers {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

	private static final Pattern CAMBODIA = Pattern.compile("^\\+855[1-9]\\d{7,8}$");

	private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

	private PhoneNumbers() {
	}

	public static Optional<String> normalize(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String s = SEPARATORS.matcher(raw.strip()).replaceAll("");
		if (s.startsWith("00")) {
			s = "+" + s.substring(2);
		}
		else if (s.startsWith("855")) {
			s = "+" + s;
		}
		else if (s.startsWith("0")) {
			s = "+855" + s.substring(1);
		}
		if (s.startsWith("+855")) {
			return CAMBODIA.matcher(s).matches() ? Optional.of(s) : Optional.empty();
		}
		return E164.matcher(s).matches() ? Optional.of(s) : Optional.empty();
	}

	/** For logs: keeps country code and last 3 digits. */
	public static String mask(String e164) {
		if (e164.length() <= 7) {
			return "***";
		}
		return e164.substring(0, 4) + "*".repeat(e164.length() - 7) + e164.substring(e164.length() - 3);
	}
}

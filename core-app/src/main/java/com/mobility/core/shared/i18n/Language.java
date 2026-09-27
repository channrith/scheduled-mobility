package com.mobility.core.shared.i18n;

import java.util.Arrays;
import java.util.Locale;

/** Supported user-facing languages. Khmer is the default. */
public enum Language {

	KM("km"), EN("en");

	public static final Language DEFAULT = KM;

	private final String code;

	Language(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public Locale locale() {
		return Locale.of(code);
	}

	public static Language fromCode(String code) {
		return Arrays.stream(values())
			.filter(l -> l.code.equalsIgnoreCase(code))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unsupported language: " + code));
	}

	/** Best match for a locale, falling back to {@link #DEFAULT}. */
	public static Language fromLocale(Locale locale) {
		if (locale == null) {
			return DEFAULT;
		}
		return Arrays.stream(values())
			.filter(l -> l.code.equals(locale.getLanguage()))
			.findFirst()
			.orElse(DEFAULT);
	}
}

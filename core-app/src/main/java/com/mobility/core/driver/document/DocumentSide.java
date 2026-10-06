package com.mobility.core.driver.document;

import java.util.Arrays;
import java.util.Optional;

public enum DocumentSide {

	FRONT, BACK;

	/** Case-insensitive, for URL path segments ({@code front}, {@code BACK}). */
	static Optional<DocumentSide> parse(String value) {
		return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(value)).findFirst();
	}
}

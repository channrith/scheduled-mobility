package com.mobility.core.shared.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PostgreSQL {@code TIMESTAMPTZ} keeps microseconds, while Java on Linux reads the clock in nanoseconds. Rounding
 * down before saving makes the value an API returns on create the same as every later read.
 */
public final class StoredTime {

	private StoredTime() {
	}

	/** {@code null} stays {@code null}. */
	public static Instant of(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
	}
}

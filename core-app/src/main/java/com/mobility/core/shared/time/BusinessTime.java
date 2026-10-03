package com.mobility.core.shared.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Timestamps are stored in UTC; business dates (expiry, "today") are in Phnom Penh time. */
public final class BusinessTime {

	public static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");

	private BusinessTime() {
	}

	public static LocalDate today(Clock clock) {
		return LocalDate.ofInstant(clock.instant(), ZONE);
	}
}

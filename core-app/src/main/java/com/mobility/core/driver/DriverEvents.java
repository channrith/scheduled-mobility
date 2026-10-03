package com.mobility.core.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain events published by the driver module (Spring application events). */
public final class DriverEvents {

	private DriverEvents() {
	}

	public record DriverApproved(UUID driverId, UUID userId) {
	}

	/** The reason is deliberately not included: it may contain sensitive incident details. */
	public record DriverSuspended(UUID driverId, UUID userId, Instant noticeAt) {
	}

	public record DriverReinstated(UUID driverId, UUID userId) {
	}

	/** {@code expired} is true when the document is already past its expiry date. */
	public record DriverDocumentExpiringSoon(UUID driverId, UUID documentId, String documentType, LocalDate expiresOn,
			boolean expired) {
	}
}

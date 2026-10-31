package com.mobility.core.dispatch;

import java.time.Instant;
import java.util.UUID;

/** Domain events of the dispatch module (outbox → RabbitMQ {@code dispatch.*}, and in-process). No personal data. */
public final class DispatchEvents {

	private DispatchEvents() {
	}

	/** @param role PRIMARY or BACKUP */
	public record DriverAssignmentConfirmed(UUID tripId, UUID driverId, String role, Instant confirmedAt) {
	}

	public record DriverAssignmentDeclined(UUID tripId, UUID driverId, String role, Instant declinedAt) {
	}

	public record BackupSwitched(UUID tripId, UUID previousDriverId, UUID newDriverId, Instant switchedAt) {
	}

	public record DispatchExceptionOpened(UUID exceptionId, String type, String severity, UUID tripId, Instant pickupAt) {
	}
}

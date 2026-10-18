package com.mobility.core.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain events of the booking module. Written to the outbox in the transaction of the change and delivered to
 * RabbitMQ (exchange {@code mobility.events}, routing keys {@code booking.*}); also published in-process. No
 * personal data: ids, statuses, amounts and times only.
 */
public final class BookingEvents {

	private BookingEvents() {
	}

	public record RideBooked(UUID rideId, BookingType bookingType, UUID corporateId, List<UUID> tripIds,
			Instant firstPickupAt, String currency, long fareMinor, PaymentMethod paymentMethod) {
	}

	public record TripAssigned(UUID tripId, UUID rideId, UUID driverId, UUID vehicleId, Instant pickupAt) {
	}

	/** Every status change, including the ones with their own event below. */
	public record TripStatusChanged(UUID tripId, UUID rideId, TripStatus from, TripStatus to, String actorRole,
			Instant occurredAt) {
	}

	public record TripCompleted(UUID tripId, UUID rideId, UUID driverId, String currency, long fareMinor,
			PaymentMethod paymentMethod, UUID corporateId, Instant completedAt) {
	}

	public record TripCancelled(UUID tripId, UUID rideId, TripStatus status, UUID driverId, Instant cancelledAt) {
	}

	/** The driver's report of cash collected; {@code mismatch} when it differs from the fare and its equivalent. */
	public record TripCashReported(UUID tripId, UUID rideId, UUID driverId, String currency, long amountMinor,
			String fareCurrency, long fareMinor, boolean mismatch, Instant reportedAt) {
	}
}

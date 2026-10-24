package com.mobility.core.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;

/**
 * Trips for the dispatch module: reading what needs a driver, and assigning one. Who may do it, the reason code and
 * the audit entry are dispatch's job; booking keeps the state machine, the version check and the trip_events row.
 */
public interface Trips {

	/**
	 * @param duration the quote's driving time (pickup to drop-off); a default when unknown
	 * @param version optimistic-locking version: pass it back to change the trip
	 */
	record TripInfo(UUID tripId, UUID rideId, BookingType type, TripStatus status, Instant pickupAt, Duration duration,
			VehicleClass vehicleClass, double pickupLat, double pickupLng, double dropoffLat, double dropoffLng,
			UUID corporateId, String flightNo, UUID driverId, long version) {

		public Instant dropoffAt() {
			return pickupAt.plus(duration);
		}

		/** Airport and corporate rides carry a service promise: they need a backup driver. */
		public boolean slaTrip() {
			return type == BookingType.AIRPORT || corporateId != null;
		}
	}

	Optional<TripInfo> find(UUID tripId);

	List<TripInfo> findAll(Collection<UUID> tripIds);

	/** Trips with a pickup in [from, to) in any of the statuses, by pickup time. */
	List<TripInfo> pickingUpBetween(Instant from, Instant to, Collection<TripStatus> statuses);

	/**
	 * Assigns or reassigns (from ASSIGNED or DRIVER_EN_ROUTE) a driver. 409 {@code concurrent-update} when
	 * {@code expectedVersion} is stale, 422 {@code booking.driver-unavailable} for a driver who cannot take it.
	 */
	TripInfo assign(UUID tripId, UUID driverId, long expectedVersion, UUID actorUserId, String reason);

	/** Back to SCHEDULED without a driver; {@code byDriver}: the driver declined it (recorded as a DRIVER change). */
	TripInfo unassign(UUID tripId, long expectedVersion, UUID actorUserId, String reason, boolean byDriver);
}

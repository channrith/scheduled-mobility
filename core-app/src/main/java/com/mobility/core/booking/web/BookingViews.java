package com.mobility.core.booking.web;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.PaymentMethod;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.Ride;
import com.mobility.core.booking.trip.Trip;
import com.mobility.core.booking.trip.TripActor;
import com.mobility.core.booking.trip.TripEvent;
import com.mobility.core.driver.VehicleClass;

/** API shapes of rides and trips. Money in minor units of {@code currency}. */
final class BookingViews {

	private BookingViews() {
	}

	record RidePointView(UUID placeId, double latitude, double longitude) {
	}

	record RecurrenceView(Set<DayOfWeek> days, LocalDate until) {
	}

	record CashReportView(long amountMinor, String currency, Instant reportedAt, boolean mismatch) {
	}

	record TripEventView(TripStatus from, TripStatus to, TripActor actorRole, String reason, UUID driverId, Instant occurredAt) {
	}

	/** @param history status changes, oldest first; only on detail reads */
	record TripView(UUID id, UUID rideId, int occurrence, Instant pickupAt, TripStatus status, UUID driverId, UUID vehicleId,
			Instant assignedAt, Instant enRouteAt, Instant arrivedAt, Instant startedAt, Instant completedAt, Instant endedAt,
			CashReportView cash, Long version, List<TripEventView> history) {
	}

	/** @param equivalentMinor the fare in {@code equivalentCurrency}, for information */
	record RideView(UUID id, BookingType type, UUID bookedBy, UUID riderUserId, UUID corporateId, UUID corporateMemberId,
			UUID costCenterId, UUID quoteId, String city, VehicleClass vehicleClass, RidePointView pickup, String pickupNotes,
			String pickupVoiceNoteKey, RidePointView dropoff, String flightNo, RecurrenceView recurrence,
			PaymentMethod paymentMethod, String currency, long fareMinor, String equivalentCurrency, long equivalentMinor,
			Long serviceFeeMinor, Instant createdAt, List<TripView> trips) {
	}

	/** A trip with what its ride says about it (list views). */
	record TripWithRideView(TripView trip, BookingType type, VehicleClass vehicleClass, RidePointView pickup,
			String pickupNotes, String pickupVoiceNoteKey, RidePointView dropoff, String flightNo, PaymentMethod paymentMethod,
			String currency, long fareMinor, String equivalentCurrency, long equivalentMinor, UUID corporateId) {
	}

	static TripView trip(Trip t, List<TripEvent> history) {
		CashReportView cash = t.getCashReportedAt() == null ? null
				: new CashReportView(t.getCashCollectedMinor(), t.getCashCurrency(), t.getCashReportedAt(),
						Boolean.TRUE.equals(t.getCashMismatch()));
		return new TripView(t.getId(), t.getRideId(), t.getOccurrence(), t.getPickupAt(), t.getStatus(), t.getDriverId(),
				t.getVehicleId(), t.getAssignedAt(), t.getEnRouteAt(), t.getArrivedAt(), t.getStartedAt(), t.getCompletedAt(),
				t.getEndedAt(), cash, t.getVersion(), history == null ? null : history.stream().map(BookingViews::event).toList());
	}

	static TripEventView event(TripEvent e) {
		return new TripEventView(e.getFromStatus(), e.getToStatus(), e.getActorRole(), e.getReason(), e.getDriverId(),
				e.getOccurredAt());
	}

	static RideView ride(Ride r, List<Trip> trips, Map<UUID, List<TripEvent>> history) {
		return new RideView(r.getId(), r.getType(), r.getBookedBy(), r.getRiderUserId(), r.getCorporateId(),
				r.getCorporateMemberId(), r.getCostCenterId(), r.getQuoteId(), r.getCity(), r.getVehicleClass(),
				new RidePointView(r.getPickupPlaceId(), r.getPickupLat(), r.getPickupLng()), r.getPickupNotes(),
				r.getPickupVoiceNoteKey(), new RidePointView(r.getDropoffPlaceId(), r.getDropoffLat(), r.getDropoffLng()),
				r.getFlightNo(),
				r.getRecurrenceUntil() == null ? null : new RecurrenceView(r.getRecurrenceDays(), r.getRecurrenceUntil()),
				r.getPaymentMethod(), r.getCurrency(), r.getFareMinor(), r.getEquivalentCurrency(), r.getEquivalentMinor(),
				r.getServiceFeeMinor(), r.getCreatedAt(),
				trips.stream().map(t -> trip(t, history.getOrDefault(t.getId(), List.of()))).toList());
	}

	static TripWithRideView withRide(Trip t, Ride r) {
		return new TripWithRideView(trip(t, null), r.getType(), r.getVehicleClass(),
				new RidePointView(r.getPickupPlaceId(), r.getPickupLat(), r.getPickupLng()), r.getPickupNotes(),
				r.getPickupVoiceNoteKey(), new RidePointView(r.getDropoffPlaceId(), r.getDropoffLat(), r.getDropoffLng()),
				r.getFlightNo(), r.getPaymentMethod(), r.getCurrency(), r.getFareMinor(), r.getEquivalentCurrency(),
				r.getEquivalentMinor(), r.getCorporateId());
	}
}

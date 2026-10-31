package com.mobility.core.booking.web;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.PaymentMethod;
import com.mobility.core.booking.ride.BookingService;
import com.mobility.core.booking.ride.BookingService.Booked;
import com.mobility.core.booking.ride.BookingService.BookingRequest;
import com.mobility.core.booking.ride.BookingService.RecurrenceInput;
import com.mobility.core.booking.ride.Ride;
import com.mobility.core.booking.trip.Trip;
import com.mobility.core.booking.trip.TripService;
import com.mobility.core.booking.web.BookingViews.RideView;
import com.mobility.core.booking.web.BookingViews.TripWithRideView;
import com.mobility.core.shared.openapi.ProblemResponse;
import com.mobility.core.shared.web.ApiException;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rides")
@Tag(name = "Rides", description = "Booking rides from fare quotes, for riders and bookers")
class RideController {

	private final BookingService bookings;

	private final TripService trips;

	RideController(BookingService bookings, TripService trips) {
		this.bookings = bookings;
		this.trips = trips;
	}

	record RecurrenceRequest(@NotNull Set<DayOfWeek> days, @NotNull LocalDate until) {
	}

	/**
	 * @param riderMemberId corporate rides: the member who rides (default: yourself)
	 * @param costCenterId corporate rides: default the rider's own cost centre
	 */
	record CreateRideRequest(@NotNull UUID quoteId, BookingType type, @Size(max = 10) String flightNo,
			@Valid RecurrenceRequest recurrence, @Size(max = 500) String pickupNotes, @Size(max = 200) String pickupVoiceNoteKey,
			@NotNull PaymentMethod paymentMethod, UUID costCenterId, UUID riderMemberId) {
	}

	/** @param tripId one trip; without it, every trip of the ride that can still be cancelled */
	record CancelRideRequest(UUID tripId, @Size(max = 500) String reason) {
	}

	@Operation(summary = "Book a ride from a quote",
			description = "Requires an `Idempotency-Key` header: a retry with the same key returns the first response. The "
					+ "quote must be yours and unexpired, and can be booked once. Fare, route and pickup time come from the "
					+ "quote. `type`: SCHEDULED (default), AIRPORT (`flightNo`; the quote must touch the airport zone) or "
					+ "RECURRING (`recurrence`: weekdays incl. the quote's own, `until` at most 90 days on; every trip at the "
					+ "quote's fare). Corporate quotes: `CORPORATE_INVOICE`, the member's booking policy is checked for every "
					+ "trip. `KHQR` is not available yet.")
	@ProblemResponse(status = 422, description = "`booking.quote-expired`, `booking.policy-refused` (`refused`: pickup "
			+ "times + violations), `booking.payment-method-unavailable`, `booking.payment-method-invalid`, "
			+ "`booking.rider-invalid`, `booking.cost-center-invalid`, `booking.not-an-airport-trip`, `booking.type-not-available`")
	@PostMapping
	ResponseEntity<RideView> book(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateRideRequest request) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "booking.idempotency-key-required");
		}
		RecurrenceInput recurrence = request.recurrence() == null ? null
				: new RecurrenceInput(request.recurrence().days(), request.recurrence().until());
		Booked booked = bookings.book(new BookingRequest(request.quoteId(), request.type(), request.flightNo(), recurrence,
				request.pickupNotes(), request.pickupVoiceNoteKey(), request.paymentMethod(), request.costCenterId(),
				request.riderMemberId()));
		return ResponseEntity.created(URI.create("/api/v1/rides/" + booked.ride().getId())).body(view(booked));
	}

	@Operation(summary = "Your upcoming trips",
			description = "Open trips (not finished or cancelled) of rides you booked or ride in, from an hour ago on, soonest first.")
	@GetMapping
	PageResponse<TripWithRideView> upcoming(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		var result = bookings.upcoming(page, size);
		var rides = bookings.rides(result.getContent().stream().map(Trip::getRideId).distinct().toList());
		return PageResponse.of(result, t -> BookingViews.withRide(t, rides.get(t.getRideId())));
	}

	@Operation(summary = "A ride", description = "With all its trips and their status history. Yours, your company's (admins, "
			+ "bookers) or any for staff; otherwise 404.")
	@GetMapping("/{rideId}")
	RideView get(@PathVariable UUID rideId) {
		return view(bookings.get(rideId));
	}

	@Operation(summary = "Cancel", description = "One trip (`tripId`) or every trip of the ride that has not started. Allowed "
			+ "until the driver has arrived.")
	@PostMapping("/{rideId}/cancel")
	RideView cancel(@PathVariable UUID rideId, @Valid @RequestBody CancelRideRequest request) {
		return view(bookings.cancel(rideId, request.tripId(), request.reason()));
	}

	private RideView view(Booked booked) {
		Ride ride = booked.ride();
		List<UUID> ids = booked.trips().stream().map(Trip::getId).toList();
		return BookingViews.ride(ride, booked.trips(), trips.history(ids));
	}
}

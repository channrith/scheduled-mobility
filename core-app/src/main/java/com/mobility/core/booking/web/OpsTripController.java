package com.mobility.core.booking.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.BookingService;
import com.mobility.core.booking.trip.Trip;
import com.mobility.core.booking.trip.TripActor;
import com.mobility.core.booking.trip.TripRepository;
import com.mobility.core.booking.trip.TripService;
import com.mobility.core.booking.trip.TripService.Actor;
import com.mobility.core.booking.web.BookingViews.TripView;
import com.mobility.core.booking.web.BookingViews.TripWithRideView;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops/trips")
@PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
@Tag(name = "Ops · Trips", description = "Dispatchers: trips, cancellations and disputes (all audit-logged); assigning drivers is in Dispatch")
class OpsTripController {

	private final TripService trips;

	private final TripRepository tripRepository;

	private final BookingService bookings;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	OpsTripController(TripService trips, TripRepository tripRepository, BookingService bookings,
			CurrentUserProvider currentUser, Clock clock) {
		this.trips = trips;
		this.tripRepository = tripRepository;
		this.bookings = bookings;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/** @param byDriver the driver could not do it and nobody can replace them (CANCELLED_BY_DRIVER) */
	record OpsCancelRequest(String reason, Boolean byDriver) {
	}

	record TripReasonRequest(String reason) {
	}

	@Operation(summary = "Trips", description = "By status (default: all open) and pickup time (default: from now for 7 days).")
	@GetMapping
	PageResponse<TripWithRideView> list(@RequestParam(required = false) List<TripStatus> status,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		Instant start = from == null ? clock.instant().minus(Duration.ofHours(1)) : from;
		Instant end = to == null ? start.plus(Duration.ofDays(7)) : to;
		var result = tripRepository.forOps(status == null || status.isEmpty() ? TripStatus.OPEN : status, start, end,
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("pickupAt")));
		var rides = bookings.rides(result.getContent().stream().map(Trip::getRideId).distinct().toList());
		return PageResponse.of(result, t -> BookingViews.withRide(t, rides.get(t.getRideId())));
	}

	@Operation(summary = "A trip", description = "With its status history.")
	@GetMapping("/{tripId}")
	TripView get(@PathVariable UUID tripId) {
		Trip trip = trips.require(tripId);
		return BookingViews.trip(trip, trips.history(List.of(tripId)).getOrDefault(tripId, List.of()));
	}

	@Operation(summary = "Cancel (→ CANCELLED_BY_OPS or CANCELLED_BY_DRIVER)", description = "`reason` (≥ 10 characters).")
	@PostMapping("/{tripId}/cancel")
	TripView cancel(@PathVariable UUID tripId, @Valid @RequestBody OpsCancelRequest request) {
		return BookingViews.trip(trips.cancelByOps(tripId, request.reason(), Boolean.TRUE.equals(request.byDriver()), ops()), null);
	}

	@Operation(summary = "Open a dispute (COMPLETED → DISPUTED)", description = "`reason` (≥ 10 characters).")
	@PostMapping("/{tripId}/dispute")
	TripView dispute(@PathVariable UUID tripId, @Valid @RequestBody TripReasonRequest request) {
		return BookingViews.trip(trips.dispute(tripId, request.reason(), ops()), null);
	}

	@Operation(summary = "Resolve a dispute (DISPUTED → COMPLETED)", description = "`reason` (≥ 10 characters).")
	@PostMapping("/{tripId}/resolve")
	TripView resolve(@PathVariable UUID tripId, @Valid @RequestBody TripReasonRequest request) {
		return BookingViews.trip(trips.resolveDispute(tripId, request.reason(), ops()), null);
	}

	private Actor ops() {
		return new Actor(currentUser.require().userId(), TripActor.OPS);
	}
}

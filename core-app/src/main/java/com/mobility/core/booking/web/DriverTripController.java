package com.mobility.core.booking.web;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.BookingService;
import com.mobility.core.booking.trip.Trip;
import com.mobility.core.booking.trip.TripRepository;
import com.mobility.core.booking.trip.TripService;
import com.mobility.core.booking.web.BookingViews.TripWithRideView;
import com.mobility.core.identity.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/driver/trips")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Driver · Trips", description = "A driver's own trips and the steps of a ride")
class DriverTripController {

	private final TripService trips;

	private final TripRepository tripRepository;

	private final BookingService bookings;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	DriverTripController(TripService trips, TripRepository tripRepository, BookingService bookings,
			CurrentUserProvider currentUser, Clock clock) {
		this.trips = trips;
		this.tripRepository = tripRepository;
		this.bookings = bookings;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/** @param amountMinor in minor units of {@code currency} (USD or KHR) */
	record CashRequest(@NotNull Long amountMinor, @NotNull String currency) {
	}

	@Operation(summary = "My upcoming trips", description = "Trips assigned to me that are not finished, from an hour ago on.")
	@GetMapping
	List<TripWithRideView> upcoming() {
		var driver = trips.driverOf(currentUser.require().userId());
		List<Trip> mine = tripRepository.forDriver(driver.driverId(),
				List.of(TripStatus.ASSIGNED, TripStatus.DRIVER_EN_ROUTE, TripStatus.DRIVER_ARRIVED, TripStatus.IN_PROGRESS),
				clock.instant().minus(Duration.ofHours(1)));
		var rides = bookings.rides(mine.stream().map(Trip::getRideId).distinct().toList());
		return mine.stream().map(t -> BookingViews.withRide(t, rides.get(t.getRideId()))).toList();
	}

	@Operation(summary = "On my way (ASSIGNED → DRIVER_EN_ROUTE)")
	@PostMapping("/{tripId}/en-route")
	BookingViews.TripView enRoute(@PathVariable UUID tripId) {
		return step(tripId, TripStatus.DRIVER_EN_ROUTE);
	}

	@Operation(summary = "Arrived at the pickup (→ DRIVER_ARRIVED)")
	@PostMapping("/{tripId}/arrived")
	BookingViews.TripView arrived(@PathVariable UUID tripId) {
		return step(tripId, TripStatus.DRIVER_ARRIVED);
	}

	@Operation(summary = "Rider on board (→ IN_PROGRESS)")
	@PostMapping("/{tripId}/start")
	BookingViews.TripView start(@PathVariable UUID tripId) {
		return step(tripId, TripStatus.IN_PROGRESS);
	}

	@Operation(summary = "Dropped off (→ COMPLETED)")
	@PostMapping("/{tripId}/complete")
	BookingViews.TripView complete(@PathVariable UUID tripId) {
		return step(tripId, TripStatus.COMPLETED);
	}

	@Operation(summary = "Rider did not come (→ NO_SHOW)",
			description = "Only 10 minutes after both the pickup time and the arrival (`booking.no-show-too-early`).")
	@PostMapping("/{tripId}/no-show")
	BookingViews.TripView noShow(@PathVariable UUID tripId) {
		return step(tripId, TripStatus.NO_SHOW);
	}

	@Operation(summary = "Report cash collected",
			description = "Once per completed CASH trip. An amount that differs from the fare (and its equivalent in the "
					+ "other currency) is accepted and flagged as a mismatch.")
	@PostMapping("/{tripId}/cash")
	BookingViews.TripView cash(@PathVariable UUID tripId, @Valid @RequestBody CashRequest request) {
		Trip trip = trips.reportCash(tripId, currentUser.require().userId(), request.amountMinor(),
				request.currency().strip().toUpperCase(java.util.Locale.ROOT));
		return BookingViews.trip(trip, null);
	}

	private BookingViews.TripView step(UUID tripId, TripStatus to) {
		return BookingViews.trip(trips.driverStep(tripId, to, currentUser.require().userId()), null);
	}
}

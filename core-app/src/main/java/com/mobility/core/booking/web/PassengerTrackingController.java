package com.mobility.core.booking.web;

import java.util.Set;
import java.util.UUID;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.RideRepository;
import com.mobility.core.booking.trip.TripRepository;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.identity.Role;
import com.mobility.core.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Passenger tracking")
@RequestMapping("/api/v1/passenger/trips")
public class PassengerTrackingController {
    private final TripRepository trips;
    private final RideRepository rides;
    private final CurrentUserProvider users;
    public PassengerTrackingController(TripRepository trips, RideRepository rides, CurrentUserProvider users) {
        this.trips = trips; this.rides = rides; this.users = users;
    }
    public record Access(UUID tripId, UUID driverId) {}

    @GetMapping("/{tripId}/tracking-access")
    @PreAuthorize("hasRole('PASSENGER')")
    @Transactional(readOnly = true)
    @Operation(summary = "Authorize passenger live tracking", description = "Rider or original booker only. Available while the driver is en route, at pickup or in progress. Rechecked by location-service before every socket delivery. No location is returned here.")
    public ResponseEntity<Access> access(@PathVariable UUID tripId) {
        var user = users.require();
        if (!user.hasRole(Role.PASSENGER)) throw new ApiException(HttpStatus.FORBIDDEN, "auth.forbidden");
        var trip = trips.findById(tripId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "booking.trip-not-found"));
        var ride = rides.findById(trip.getRideId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "booking.trip-not-found"));
        if (!user.userId().equals(ride.getRiderUserId()) && !user.userId().equals(ride.getBookedBy()))
            throw new ApiException(HttpStatus.NOT_FOUND, "booking.trip-not-found");
        if (trip.getDriverId() == null || !Set.of(TripStatus.DRIVER_EN_ROUTE, TripStatus.DRIVER_ARRIVED, TripStatus.IN_PROGRESS).contains(trip.getStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "booking.tracking-unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Access(tripId, trip.getDriverId()));
    }
}

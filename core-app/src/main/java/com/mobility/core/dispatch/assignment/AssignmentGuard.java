package com.mobility.core.dispatch.assignment;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.booking.Trips;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.shared.DriverScheduleLock;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.risk.TravelEstimator;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Shared by automatic and manual assignment. Locks live until the surrounding transaction commits. */
@Component
public class AssignmentGuard {
    private final JdbcTemplate jdbc;
    private final DriverScheduleLock driverLocks;
    private final TripAssignmentRepository assignments;
    private final Trips trips;
    private final TravelEstimator travel;
    private final DispatchProperties properties;

    AssignmentGuard(JdbcTemplate jdbc, TripAssignmentRepository assignments, Trips trips,
            TravelEstimator travel, DispatchProperties properties, DriverScheduleLock driverLocks) {
        this.jdbc = jdbc;
        this.driverLocks = driverLocks;
        this.assignments = assignments;
        this.trips = trips;
        this.travel = travel;
        this.properties = properties;
    }

    public void lock(UUID... drivers) {
        // Same namespace as shift cancellation; stable ordering prevents primary/backup lock inversion.
        Arrays.stream(drivers).filter(Objects::nonNull).distinct().sorted().forEach(driverLocks::acquire);
    }

    public void lockTrip(UUID tripId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class, "dispatch.trip:" + tripId);
    }

    public void lockForTrip(UUID tripId) {
        // Scalar JDBC lookup avoids caching an entity before waiting for a driver's lock.
        var ids = jdbc.query("SELECT primary_driver_id, backup_driver_id FROM dispatch.trip_assignments WHERE trip_id = ?",
                (rs, row) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, tripId);
        if (!ids.isEmpty()) {
            lock(ids.getFirst());
        }
    }

    public boolean available(TripInfo trip, UUID driver) {
        return trips.findAll(assignments.forDriver(driver).stream()
                .map(TripAssignment::getTripId).filter(id -> !id.equals(trip.tripId())).toList())
                .stream().filter(t -> t.status().isOpen()).noneMatch(other -> conflicts(trip, other));
    }

    public void requireAvailable(TripInfo trip, UUID driver) {
        if (driver != null && !available(trip, driver)) {
            throw new ApiException(HttpStatus.CONFLICT, "dispatch.driver-overlap")
                    .withProperty("driverId", driver);
        }
    }

    boolean conflicts(TripInfo a, TripInfo b) {
        if (a.status() == TripStatus.IN_PROGRESS
                || b.status() == TripStatus.IN_PROGRESS) {
            return true;
        }
        // A pickup-only trip has no known release time or endpoint: never invent a free slot.
        if (a.dropoffLat() == null || b.dropoffLat() == null) {
            return true;
        }
        TripInfo earlier = a.pickupAt().isBefore(b.pickupAt()) ? a : b;
        TripInfo later = earlier == a ? b : a;
        return earlier.dropoffAt().plus(travel.between(earlier.dropoffLat(), earlier.dropoffLng(),
                later.pickupLat(), later.pickupLng())).plus(properties.planning().buffer())
                .isAfter(later.pickupAt());
    }
}

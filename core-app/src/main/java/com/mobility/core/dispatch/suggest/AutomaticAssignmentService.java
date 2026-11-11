package com.mobility.core.dispatch.suggest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.assignment.AssignmentGuard;
import com.mobility.core.dispatch.assignment.AssignmentService;
import com.mobility.core.dispatch.assignment.TripAssignment;
import com.mobility.core.dispatch.assignment.TripAssignmentRepository;
import com.mobility.core.dispatch.automatic.AutomaticAssignmentProperties;
import com.mobility.core.dispatch.issue.Severity;
import com.mobility.core.dispatch.shift.ShiftBlockRepository;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Commitment;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.DayProblem;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Driver;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Point;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Rules;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.TripToPlan;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Window;
import com.mobility.core.driver.DriverDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans from detached snapshots. AssignmentService revalidates in a fresh, locked transaction;
 * planning must not leave cached entities that could hide a concurrent assignment or trip start.
 */
@Service
public class AutomaticAssignmentService {
    public record AssignmentOutcome(String status, String reason, Instant nextAttempt) {}

    private final Trips trips;
    private final DriverDirectory drivers;
    private final ShiftBlockRepository shifts;
    private final TripAssignmentRepository reservations;
    private final AssignmentGuard guard;
    private final AssignmentStrategy strategy;
    private final AssignmentService assignments;
    private final DispatchProperties dispatch;
    private final AutomaticAssignmentProperties properties;
    private final Clock clock;

    AutomaticAssignmentService(Trips trips, DriverDirectory drivers, ShiftBlockRepository shifts,
            TripAssignmentRepository reservations, AssignmentGuard guard, AssignmentStrategy strategy,
            AssignmentService assignments, DispatchProperties dispatch,
            AutomaticAssignmentProperties properties, Clock clock) {
        this.trips = trips;
        this.drivers = drivers;
        this.shifts = shifts;
        this.reservations = reservations;
        this.guard = guard;
        this.strategy = strategy;
        this.assignments = assignments;
        this.dispatch = dispatch;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AssignmentOutcome attempt(UUID id, int attempt) {
        var trip = trips.find(id).orElse(null);
        if (trip == null || trip.status() != TripStatus.SCHEDULED || trip.driverId() != null) {
            return new AssignmentOutcome("DONE", "NO_LONGER_UNASSIGNED", null);
        }
        Instant now = clock.instant();
        // Live, pickup-only dispatch needs a different supply/ETA policy; keep its rollout separate.
        if (trip.type() == BookingType.ON_DEMAND || trip.dropoffLat() == null) {
            return manual(trip, "LIVE_DISPATCH_REQUIRED");
        }
        if (!trip.pickupAt().isAfter(now)) {
            return manual(trip, "PICKUP_PASSED");
        }
        if (trip.pickupAt().isAfter(now.plus(properties.horizon()))) {
            return new AssignmentOutcome("PENDING", "OUTSIDE_HORIZON", trip.pickupAt().minus(properties.horizon()));
        }
        List<Driver> pool = new ArrayList<>();
        List<Commitment> held = new ArrayList<>();
        for (var driver : drivers.approved()) {
            if (driver.vehicle() == null || driver.vehicle().vehicleClass() != trip.vehicleClass()
                    || !guard.available(trip, driver.driverId())) {
                continue;
            }
            var windows = shifts.overlapping(List.of(driver.driverId()), trip.pickupAt(), trip.dropoffAt())
                    .stream().map(s -> new Window(s.getStartsAt(), s.getEndsAt())).toList();
            pool.add(new Driver(driver.driverId(), driver.fullName(), driver.vehicle().vehicleClass(),
                    HomeZoneCentres.of(driver.homeZone()), windows));
            for (var other : trips.findAll(reservations.forDriver(driver.driverId()).stream()
                    .map(TripAssignment::getTripId).filter(t -> !t.equals(id)).toList())) {
                if (other.status().isOpen() && other.dropoffLat() != null) {
                    held.add(new Commitment(driver.driverId(), other.tripId(), other.pickupAt(), other.dropoffAt(),
                            new Point(other.pickupLat(), other.pickupLng()), new Point(other.dropoffLat(), other.dropoffLng())));
                }
            }
        }
        var plan = strategy.plan(new DayProblem(List.of(new TripToPlan(id, trip.vehicleClass(),
                trip.pickupAt(), trip.dropoffAt(), new Point(trip.pickupLat(), trip.pickupLng()),
                new Point(trip.dropoffLat(), trip.dropoffLng()))), pool, held,
                new Rules(dispatch.planning().buffer(), dispatch.planning().fairnessMinutes()))).suggestions().getFirst();
        if (plan.primary() == null || (trip.slaTrip() && plan.backup() == null)) {
            String reason = plan.primary() == null ? plan.unplannedReason() : "NO_BACKUP";
            if (attempt >= properties.maxAttempts()) {
                return manual(trip, reason);
            }
            if (!assignments.reportUnassigned(id, reason, Severity.MEDIUM)) {
                return new AssignmentOutcome("DONE", "NO_LONGER_UNASSIGNED", null);
            }
            return new AssignmentOutcome("PENDING", reason, now.plus(properties.retryDelay()));
        }
        assignments.assignAutomatically(id, plan.primary().driverId(),
                trip.slaTrip() ? plan.backup().driverId() : null, trip.version());
        return new AssignmentOutcome("DONE", "ASSIGNED", null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void failedPermanently(UUID tripId) {
        assignments.reportUnassigned(tripId, "ASSIGNMENT_FAILED", Severity.HIGH);
    }

    private AssignmentOutcome manual(Trips.TripInfo trip, String reason) {
        if (!assignments.reportUnassigned(trip.tripId(), reason, Severity.HIGH)) {
            return new AssignmentOutcome("DONE", "NO_LONGER_UNASSIGNED", null);
        }
        return new AssignmentOutcome("MANUAL", reason, null);
    }
}

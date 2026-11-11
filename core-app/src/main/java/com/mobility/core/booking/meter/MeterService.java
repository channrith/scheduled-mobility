package com.mobility.core.booking.meter;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.Ride;
import com.mobility.core.booking.ride.RideRepository;
import com.mobility.core.booking.trip.Trip;
import com.mobility.core.booking.trip.TripRepository;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.pricing.MeteredPricing;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All distance and fare arithmetic lives on the server. Missing coverage never becomes an invented
 * fare.
 */
@Service
public class MeterService {
    static final int MAX_GAP_SECONDS = 30;
    static final int FINAL_SAMPLE_AGE_SECONDS = 15;
    private final MeterSessionRepository meters;
    private final TripRepository trips;
    private final RideRepository rides;
    private final DriverDirectory drivers;
    private final MeteredPricing pricing;
    private final Clock clock;

    public MeterService(
            MeterSessionRepository meters,
            TripRepository trips,
            RideRepository rides,
            DriverDirectory drivers,
            MeteredPricing pricing,
            Clock clock) {
        this.meters = meters;
        this.trips = trips;
        this.rides = rides;
        this.drivers = drivers;
        this.pricing = pricing;
        this.clock = clock;
    }

    public record Sample(
            double latitude, double longitude, double accuracyMeters, Instant recordedAt) {}

    public void start(Trip trip, Ride ride) {
        if (ride.isMetered()) {
            meters.saveAndFlush(new MeterSession(trip.getId()));
        }
    }

    /**
     * A driver may have at most one in-progress trip. No active meter is a no-op for shared GPS
     * upload.
     */
    @Transactional
    public void accept(UUID driverUser, Sample sample) {
        var driver =
                drivers.byUserId(driverUser)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.FORBIDDEN, "booking.not-a-driver"));
        var active =
                trips.forDriver(driver.driverId(), List.of(TripStatus.IN_PROGRESS), Instant.EPOCH);
        if (active.isEmpty()) {
            return;
        }
        if (active.size() != 1) {
            throw failure("ambiguous-trip");
        }
        Trip trip = active.getFirst();
        Ride ride = rides.findById(trip.getRideId()).orElseThrow();
        if (!ride.isMetered()) {
            return;
        }
        var meter = meters.findById(trip.getId()).orElseThrow(() -> failure("not-started"));
        if (meter.finalized) {
            throw failure("finalized");
        }
        Instant now = clock.instant();
        if (sample == null
                || sample.recordedAt() == null
                || !Double.isFinite(sample.latitude())
                || !Double.isFinite(sample.longitude())
                || Math.abs(sample.latitude()) > 90
                || Math.abs(sample.longitude()) > 180
                || !Double.isFinite(sample.accuracyMeters())
                || sample.accuracyMeters() <= 0
                || sample.accuracyMeters() > 50
                || sample.recordedAt().isAfter(now.plusSeconds(2))
                || sample.recordedAt().isBefore(now.minusSeconds(30))
                || sample.recordedAt().isBefore(trip.getStartedAt())) {
            throw failure("sample-invalid");
        }
        Instant at = StoredTime.of(sample.recordedAt());
        if (meter.lastAt != null && !at.isAfter(meter.lastAt)) {
            return; // replay/out-of-order cannot add distance or time
        }
        if (meter.lastAt == null) {
            if (at.isAfter(trip.getStartedAt().plusSeconds(MAX_GAP_SECONDS))) {
                throw failure("coverage-missing");
            }
            meter.firstAt = at;
            meter.lastLat = sample.latitude();
            meter.lastLng = sample.longitude();
            meter.lastAccuracy = sample.accuracyMeters();
            meter.positionAt = at;
        } else {
            double seconds = Duration.between(meter.lastAt, at).toMillis() / 1000.0;
            if (seconds < 1 || seconds > MAX_GAP_SECONDS) {
                throw failure("coverage-missing");
            }
            double distance =
                    distance(meter.lastLat, meter.lastLng, sample.latitude(), sample.longitude());
            if (distance / (Duration.between(meter.positionAt, at).toMillis() / 1000.0) > 45) {
                throw failure("sample-invalid"); // 162 km/h, never silently clamp a jump
            }
            if (distance > Math.max(3, Math.max(meter.lastAccuracy, sample.accuracyMeters()))) {
                meter.distanceMeters += distance;
                meter.lastLat = sample.latitude();
                meter.lastLng = sample.longitude();
                meter.lastAccuracy = sample.accuracyMeters();
                meter.positionAt = at;
            }
        }
        meter.lastAt = at;
        meter.samples++;
        meters.saveAndFlush(meter);
    }

    /** Called in the same transaction as COMPLETED and payment/outbox events. */
    public void complete(Trip trip, Ride ride) {
        if (!ride.isMetered()) {
            return;
        }
        var meter = meters.findById(trip.getId()).orElseThrow(() -> failure("not-started"));
        Instant now = clock.instant();
        if (meter.finalized
                || meter.samples < 2
                || meter.lastAt == null
                || meter.lastAt.isBefore(now.minusSeconds(FINAL_SAMPLE_AGE_SECONDS))) {
            throw failure("coverage-missing");
        }
        long seconds = Duration.between(trip.getStartedAt(), now).getSeconds();
        if (seconds < 0 || seconds > 86400) {
            throw failure("duration-invalid");
        }
        ride.finalizeMeter(
                pricing.finish(ride.getMeterTerms(), Math.round(meter.distanceMeters), seconds));
        rides.saveAndFlush(ride);
        meter.finalized = true;
        meters.saveAndFlush(meter);
    }

    static double distance(double lat1, double lng1, double lat2, double lng2) {
        double a =
                Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    private static ApiException failure(String reason) {
        return new ApiException(HttpStatus.CONFLICT, "booking.meter-" + reason);
    }
}

package com.mobility.location.tracking;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LocationJumpValidator {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final double maxSpeedMetersPerSecond;
    private final Duration reacquisitionGap;

    public LocationJumpValidator(
            @Value("${location.tracking.max-speed-meters-per-second:60}")
            double maxSpeedMetersPerSecond,
            @Value("${location.tracking.reacquisition-gap:120s}")
            Duration reacquisitionGap
    ) {
        if (!Double.isFinite(maxSpeedMetersPerSecond)
                || maxSpeedMetersPerSecond <= 0
                || reacquisitionGap.isNegative()
                || reacquisitionGap.isZero()) {
            throw new IllegalArgumentException(
                    "Jump validation settings must be positive"
            );
        }

        this.maxSpeedMetersPerSecond = maxSpeedMetersPerSecond;
        this.reacquisitionGap = reacquisitionGap;
    }

    public void validate(
            StoredDriverLocation previous,
            UpdateDriverLocationRequest incoming
    ) {
        Duration elapsed = Duration.between(
                previous.recordedAt(),
                incoming.recordedAt()
        );

        if (elapsed.isNegative() || elapsed.isZero()) {
            throw new IllegalArgumentException(
                    "Jump validation requires a newer measurement"
            );
        }

        // After a long gap, establish a new baseline.
        // Basic coordinate, accuracy and age validation still applies.
        if (elapsed.compareTo(reacquisitionGap) > 0) {
            return;
        }

        double distance = distanceMeters(
                previous.latitude(),
                previous.longitude(),
                incoming.latitude(),
                incoming.longitude()
        );

        // Allow for uncertainty in both GPS readings.
        double adjustedDistance = Math.max(
                0,
                distance
                        - previous.accuracyMeters()
                        - incoming.accuracyMeters()
        );

        double seconds =
                elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;

        double estimatedSpeed = adjustedDistance / seconds;

        if (estimatedSpeed > maxSpeedMetersPerSecond) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Location jump exceeds the accepted movement limit"
            );
        }
    }

    private static double distanceMeters(
            double latitude1,
            double longitude1,
            double latitude2,
            double longitude2
    ) {
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);

        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2 - longitude1);

        double sinLat = Math.sin(deltaLat / 2);
        double sinLon = Math.sin(deltaLon / 2);

        double a = sinLat * sinLat
                + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;

        a = Math.max(0, Math.min(1, a));

        return EARTH_RADIUS_METERS
                * 2
                * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
package com.mobility.location.tracking;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LocationValidator {

    private final Duration maxAge;
    private final Duration futureTolerance;
    private final double maxAccuracyMeters;

    public LocationValidator(
            @Value("${location.tracking.max-age:120s}")
            Duration maxAge,
            @Value("${location.tracking.future-tolerance:30s}")
            Duration futureTolerance,
            @Value("${location.tracking.max-accuracy-meters:100}")
            double maxAccuracyMeters
    ) {
        this.maxAge = maxAge;
        this.futureTolerance = futureTolerance;
        this.maxAccuracyMeters = maxAccuracyMeters;
    }

    public void validate(
            UpdateDriverLocationRequest request,
            Instant now
    ) {
        if (request == null) {
            throw invalid("Location body is required");
        }

        Double latitude = request.latitude();

        if (latitude == null
                || !Double.isFinite(latitude)
                || latitude < -90
                || latitude > 90) {
            throw invalid("Latitude must be between -90 and 90");
        }

        Double longitude = request.longitude();

        if (longitude == null
                || !Double.isFinite(longitude)
                || longitude < -180
                || longitude > 180) {
            throw invalid("Longitude must be between -180 and 180");
        }

        Double accuracy = request.accuracyMeters();

        if (accuracy == null
                || !Double.isFinite(accuracy)
                || accuracy <= 0
                || accuracy > maxAccuracyMeters) {
            throw invalid("Location accuracy is outside the accepted range");
        }

        Instant recordedAt = request.recordedAt();

        if (recordedAt == null) {
            throw invalid("Location timestamp is required");
        }

        if (recordedAt.isBefore(now.minus(maxAge))) {
            throw invalid("Location measurement is too old");
        }

        if (recordedAt.isAfter(now.plus(futureTolerance))) {
            throw invalid("Location timestamp is too far in the future");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
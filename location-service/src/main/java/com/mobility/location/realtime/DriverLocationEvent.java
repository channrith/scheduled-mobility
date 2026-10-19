package com.mobility.location.realtime;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriverLocationEvent(
        Integer schemaVersion,
        String type,
        UUID eventId,
        UUID driverId,
        Double latitude,
        Double longitude,
        Double accuracyMeters,
        Instant recordedAt,
        Instant receivedAt
) {

    public void validate() {
        if (schemaVersion == null || schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported event version");
        }

        if (!"driver.location.updated".equals(type)) {
            throw new IllegalArgumentException("Unsupported event type");
        }

        if (eventId == null || driverId == null) {
            throw new IllegalArgumentException("Missing event identity");
        }

        if (latitude == null
                || !Double.isFinite(latitude)
                || latitude < -90
                || latitude > 90) {
            throw new IllegalArgumentException("Invalid latitude");
        }

        if (longitude == null
                || !Double.isFinite(longitude)
                || longitude < -180
                || longitude > 180) {
            throw new IllegalArgumentException("Invalid longitude");
        }

        if (accuracyMeters == null
                || !Double.isFinite(accuracyMeters)
                || accuracyMeters <= 0) {
            throw new IllegalArgumentException("Invalid accuracy");
        }

        if (recordedAt == null || receivedAt == null) {
            throw new IllegalArgumentException("Missing timestamps");
        }
    }
}
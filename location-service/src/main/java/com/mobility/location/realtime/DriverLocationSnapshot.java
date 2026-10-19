package com.mobility.location.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.mobility.location.tracking.StoredDriverLocation;

public record DriverLocationSnapshot(
        int schemaVersion,
        String type,
        UUID driverId,
        double latitude,
        double longitude,
        double accuracyMeters,
        Instant recordedAt,
        Instant receivedAt,
        long ageSeconds,
        boolean fresh
) {

    public static DriverLocationSnapshot from(
            UUID driverId,
            StoredDriverLocation location
    ) {
        long ageSeconds = Math.max(
                0,
                Duration.between(
                        location.recordedAt(),
                        Instant.now()
                ).getSeconds()
        );

        return new DriverLocationSnapshot(
                1,
                "driver.location.snapshot",
                driverId,
                location.latitude(),
                location.longitude(),
                location.accuracyMeters(),
                location.recordedAt(),
                location.receivedAt(),
                ageSeconds,
                ageSeconds <= 30
        );
    }
}
package com.mobility.location.tracking;

import java.time.Instant;

public record DriverLocationResponse(
        double latitude,
        double longitude,
        double accuracyMeters,
        Instant recordedAt,
        Instant receivedAt,
        long ageSeconds,
        boolean fresh
) {
}


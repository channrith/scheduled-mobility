package com.mobility.location.tracking;

import java.time.Instant;

public record StoredDriverLocation(
        double latitude,
        double longitude,
        double accuracyMeters,
        Instant recordedAt,
        Instant receivedAt
) {
}
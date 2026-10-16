package com.mobility.location.tracking;

import java.time.Instant;

public record UpdateDriverLocationRequest(
        Double latitude,
        Double longitude,
        Double accuracyMeters,
        Instant recordedAt
) {
}
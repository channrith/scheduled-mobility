package com.mobility.location.presence;

import java.time.Instant;

public record DriverPresenceResponse(
        boolean online,
        Instant lastSeenAt,
        long timeoutSeconds
) {
}

package com.mobility.location.driver;

import java.util.UUID;

public record DriverIdentity(
        UUID driverId,
        UUID userId,
        String status
) {
}
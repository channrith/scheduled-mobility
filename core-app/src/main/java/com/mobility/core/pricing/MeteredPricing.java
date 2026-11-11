package com.mobility.core.pricing;

import com.mobility.core.driver.VehicleClass;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Public pricing boundary: immutable terms stored with a destination-free booking. */
public interface MeteredPricing {
    record Terms(
            UUID ruleId,
            UUID pickupPlaceId,
            double latitude,
            double longitude,
            String city,
            VehicleClass vehicleClass,
            boolean airport,
            String currency,
            long baseMinor,
            long perKmMinor,
            long perMinMinor,
            long minimumMinor,
            BigDecimal fuelFactor,
            BigDecimal maxSurge,
            BigDecimal fuelIndex,
            BigDecimal surge,
            Long airportMinor,
            String airportCurrency,
            BigDecimal usdToKhr) {}

    record MeteredFare(
            String currency, long minor, String equivalentCurrency, long equivalentMinor) {}

    Terms prepare(
            UUID placeId,
            Double latitude,
            Double longitude,
            VehicleClass vehicleClass,
            Instant at,
            boolean airport);

    MeteredFare finish(Terms terms, long meters, long seconds);
}

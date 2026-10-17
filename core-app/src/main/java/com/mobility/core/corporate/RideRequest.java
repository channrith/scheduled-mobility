package com.mobility.core.corporate;

import java.time.Instant;

import com.mobility.core.driver.VehicleClass;

/**
 * A ride to check against a member's policy.
 *
 * @param fareMinor fixed fare in minor units, in {@code currency}; pricing converts to the policy's currency
 */
public record RideRequest(VehicleClass vehicleClass, Instant pickupAt, long fareMinor, String currency) {
}

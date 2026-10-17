package com.mobility.core.pricing.quote;

import java.math.BigDecimal;
import java.time.Instant;

import com.mobility.core.driver.VehicleClass;

/**
 * Demand multiplier for a ride. Scheduled rides have no surge yet (always 1); whatever an implementation
 * returns, the fare never applies more than the rule's {@code max_surge}.
 */
public interface SurgeProvider {

	BigDecimal surge(String city, VehicleClass vehicleClass, Instant pickupAt);
}

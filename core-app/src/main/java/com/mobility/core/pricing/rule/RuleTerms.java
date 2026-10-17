package com.mobility.core.pricing.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;

/** Validated content of a new rule version. */
record RuleTerms(String city, VehicleClass vehicleClass, UUID corporateId, String currency, long baseMinor,
		long perKmMinor, long perMinMinor, long minFareMinor, BigDecimal fuelIndexFactor, BigDecimal maxSurge,
		Instant validFrom, Instant validTo, boolean active) {
}

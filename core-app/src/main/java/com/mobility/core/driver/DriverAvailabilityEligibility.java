package com.mobility.core.driver;

import java.util.UUID;

/** Driver-module guard for a driver's choice to become available. Does not evaluate GPS freshness. */
public interface DriverAvailabilityEligibility {

	void requireEligible(UUID driverId);
}

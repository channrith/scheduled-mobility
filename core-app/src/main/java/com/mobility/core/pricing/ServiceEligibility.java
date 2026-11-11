package com.mobility.core.pricing;

import com.mobility.core.driver.VehicleClass;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only public pricing boundary; no route calls, quotes or writes. */
public interface ServiceEligibility {
    record EligibilityLocation(UUID placeId, Double latitude, Double longitude) {}
    record Coverage(String city, List<String> pickupZoneTypes, List<String> dropoffZoneTypes, UUID airportZoneId) {}
    Coverage check(EligibilityLocation pickup, EligibilityLocation dropoff, VehicleClass vehicleClass, Instant at, boolean airport, boolean metered);
}

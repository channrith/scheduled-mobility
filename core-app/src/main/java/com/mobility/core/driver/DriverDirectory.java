package com.mobility.core.driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only driver lookups for other modules (booking: who is driving, and may they take this trip). */
public interface DriverDirectory {

	/** The vehicle a driver has now (assignment in force). */
	record ActiveVehicle(UUID vehicleId, VehicleClass vehicleClass, String plateNumber) {
	}

	/**
	 * @param homeZone where the driver usually starts the day; may be null
	 * @param vehicle null when no vehicle is assigned now
	 */
	record DriverInfo(UUID driverId, UUID userId, String fullName, DriverStatus status, HomeZone homeZone,
			ActiveVehicle vehicle) {
	}

	Optional<DriverInfo> get(UUID driverId);

	/** The driver profile of a user (a DRIVER login), if any. */
	Optional<DriverInfo> byUserId(UUID userId);

	List<DriverInfo> getAll(java.util.Collection<UUID> driverIds);

	/** Every APPROVED driver, for planning. */
	List<DriverInfo> approved();
}

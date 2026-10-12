package com.mobility.core.driver.vehicle;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;

/**
 * @param validFrom start of the assignment this view was built for (null outside an assignment)
 * @param validTo end of that assignment; null = open-ended
 */
public record VehicleView(UUID id, String plateNumber, VehicleClass vehicleClass, String make, String model,
		String color, Short modelYear, short seats, VehicleStatus status, VehicleOwnership ownership, String ownerName,
		String ownerPhone, Long version, Instant validFrom, Instant validTo) {

	static VehicleView of(Vehicle v, VehicleAssignment assignment) {
		return new VehicleView(v.getId(), v.getPlateNumber(), v.getVehicleClass(), v.getMake(), v.getModel(),
				v.getColor(), v.getModelYear(), v.getSeats(), v.getStatus(), v.getOwnership(), v.getOwnerName(),
				v.getOwnerPhone(), v.getVersion(), assignment == null ? null : assignment.getValidFrom(),
				assignment == null ? null : assignment.getValidTo());
	}
}

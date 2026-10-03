package com.mobility.core.driver.vehicle;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;

public record VehicleView(UUID id, String plateNumber, VehicleClass vehicleClass, String make, String model,
		String color, Short modelYear, short seats, VehicleStatus status, Instant assignedAt) {

	static VehicleView of(Vehicle v, Instant assignedAt) {
		return new VehicleView(v.getId(), v.getPlateNumber(), v.getVehicleClass(), v.getMake(), v.getModel(),
				v.getColor(), v.getModelYear(), v.getSeats(), v.getStatus(), assignedAt);
	}
}

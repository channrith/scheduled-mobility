package com.mobility.core.driver.vehicle;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.driver.profile.Driver;
import com.mobility.core.driver.profile.DriverRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverDirectoryService implements DriverDirectory {

	private final DriverRepository drivers;

	private final VehicleAssignmentRepository assignments;

	private final VehicleRepository vehicles;

	private final Clock clock;

	DriverDirectoryService(DriverRepository drivers, VehicleAssignmentRepository assignments, VehicleRepository vehicles,
			Clock clock) {
		this.drivers = drivers;
		this.assignments = assignments;
		this.vehicles = vehicles;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<DriverInfo> get(UUID driverId) {
		return drivers.findById(driverId).map(this::summary);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<DriverInfo> byUserId(UUID userId) {
		return drivers.findByUserId(userId).map(this::summary);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<DriverInfo> getAll(java.util.Collection<UUID> driverIds) {
		return drivers.findAllById(driverIds).stream().map(this::summary).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<DriverInfo> approved() {
		return drivers.findByStatus(com.mobility.core.driver.DriverStatus.APPROVED).stream().map(this::summary).toList();
	}

	private DriverInfo summary(Driver d) {
		ActiveVehicle vehicle = assignments.findActiveForDrivers(java.util.List.of(d.getId()), clock.instant()).stream()
			.findFirst()
			.flatMap(a -> vehicles.findById(a.getVehicleId()))
			.filter(v -> v.getStatus() == VehicleStatus.ACTIVE)
			.map(v -> new ActiveVehicle(v.getId(), v.getVehicleClass(), v.getPlateNumber()))
			.orElse(null);
		return new DriverInfo(d.getId(), d.getUserId(), d.getFullName(), d.getStatus(), d.getHomeZone(), vehicle);
	}
}

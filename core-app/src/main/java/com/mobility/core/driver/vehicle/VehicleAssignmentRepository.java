package com.mobility.core.driver.vehicle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleAssignmentRepository extends JpaRepository<VehicleAssignment, UUID> {

	Optional<VehicleAssignment> findByDriverIdAndUnassignedAtIsNull(UUID driverId);

	Optional<VehicleAssignment> findByVehicleIdAndUnassignedAtIsNull(UUID vehicleId);
}

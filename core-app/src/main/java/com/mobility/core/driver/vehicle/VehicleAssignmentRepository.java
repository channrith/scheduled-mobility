package com.mobility.core.driver.vehicle;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface VehicleAssignmentRepository extends JpaRepository<VehicleAssignment, UUID> {

	/** Assignments that are not over at {@code now} (current and future), oldest first. */
	@Query("select a from VehicleAssignment a where a.driverId = :driverId and (a.validTo is null or a.validTo > :now) "
			+ "order by a.validFrom")
	List<VehicleAssignment> findNotEndedByDriver(UUID driverId, Instant now);

	@Query("select a from VehicleAssignment a where a.vehicleId = :vehicleId and (a.validTo is null or a.validTo > :now) "
			+ "order by a.validFrom")
	List<VehicleAssignment> findNotEndedByVehicle(UUID vehicleId, Instant now);

	@Query("select a from VehicleAssignment a where a.driverId in :driverIds and a.validFrom <= :now "
			+ "and (a.validTo is null or a.validTo > :now)")
	List<VehicleAssignment> findActiveForDrivers(Collection<UUID> driverIds, Instant now);

	@Query("select a from VehicleAssignment a where a.vehicleId in :vehicleIds and a.validFrom <= :now "
			+ "and (a.validTo is null or a.validTo > :now)")
	List<VehicleAssignment> findActiveForVehicles(Collection<UUID> vehicleIds, Instant now);

	List<VehicleAssignment> findByDriverIdOrderByValidFromDesc(UUID driverId);
}

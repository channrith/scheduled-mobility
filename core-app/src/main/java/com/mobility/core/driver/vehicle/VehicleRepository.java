package com.mobility.core.driver.vehicle;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

	boolean existsByPlateNumber(String plateNumber);

	boolean existsByPlateNumberAndIdNot(String plateNumber, UUID id);

	/** Row lock: serializes assignments of this vehicle. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select v from Vehicle v where v.id = :id")
	Optional<Vehicle> lockById(UUID id);
}

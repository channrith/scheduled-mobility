package com.mobility.core.driver.vehicle;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

	boolean existsByPlateNumber(String plateNumber);
}

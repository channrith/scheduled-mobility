package com.mobility.core.driver.profile;

import java.util.Optional;
import java.util.UUID;

import com.mobility.core.driver.DriverStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

	Optional<Driver> findByUserId(UUID userId);

	boolean existsByUserId(UUID userId);

	boolean existsByNationalIdHash(byte[] nationalIdHash);

	Page<Driver> findByStatus(DriverStatus status, Pageable pageable);
}

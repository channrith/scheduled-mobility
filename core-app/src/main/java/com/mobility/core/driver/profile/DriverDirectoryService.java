package com.mobility.core.driver.profile;

import java.util.Optional;
import java.util.UUID;

import com.mobility.core.driver.DriverDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverDirectoryService implements DriverDirectory {

	private final DriverRepository drivers;

	DriverDirectoryService(DriverRepository drivers) {
		this.drivers = drivers;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<DriverSummary> byUserId(UUID userId) {
		return drivers.findByUserId(userId).map(driver -> new DriverSummary(driver.getId()));
	}
}

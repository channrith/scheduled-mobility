package com.mobility.core.driver.profile;

import java.util.UUID;

import com.mobility.core.driver.DriverAvailabilityEligibility;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverAvailabilityEligibilityService implements DriverAvailabilityEligibility {

	private final DriverRepository drivers;

	private final ReadinessCalculator readiness;

	DriverAvailabilityEligibilityService(DriverRepository drivers, ReadinessCalculator readiness) {
		this.drivers = drivers;
		this.readiness = readiness;
	}

	@Override
	@Transactional(readOnly = true)
	public void requireEligible(UUID driverId) {
		Driver driver = drivers.findById(driverId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
		driver.requireAvailabilityApproved();
		driver.requireAvailable(readiness.forDispatch(driverId));
	}
}

package com.mobility.core.pricing.quote;

import java.math.BigDecimal;
import java.time.Instant;

import com.mobility.core.driver.VehicleClass;
import org.springframework.stereotype.Component;

@Component
class NoSurge implements SurgeProvider {

	@Override
	public BigDecimal surge(String city, VehicleClass vehicleClass, Instant pickupAt) {
		return BigDecimal.ONE;
	}
}

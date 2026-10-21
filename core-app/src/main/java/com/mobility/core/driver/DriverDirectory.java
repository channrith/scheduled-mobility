package com.mobility.core.driver;

import java.util.Optional;
import java.util.UUID;

/** Read-only driver identity lookups; live state is stored separately in Redis. */
public interface DriverDirectory {

	record DriverSummary(UUID id) {
	}

	Optional<DriverSummary> byUserId(UUID userId);
}

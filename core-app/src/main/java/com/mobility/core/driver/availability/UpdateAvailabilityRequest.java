package com.mobility.core.driver.availability;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateAvailabilityRequest(@NotNull(message = "{availability.status-required}") AvailabilityStatus status,
		@NotNull(message = "{availability.version-required}")
		@PositiveOrZero(message = "{availability.version-nonnegative}") Long expectedVersion) {
}

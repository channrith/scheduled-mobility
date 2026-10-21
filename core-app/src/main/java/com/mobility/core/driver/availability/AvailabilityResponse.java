package com.mobility.core.driver.availability;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record AvailabilityResponse(AvailabilityStatus status, long version,
		@Schema(types = { "string", "null" }, format = "date-time",
				description = "Last stored update, or null when no availability has been recorded") Instant updatedAt) {
}

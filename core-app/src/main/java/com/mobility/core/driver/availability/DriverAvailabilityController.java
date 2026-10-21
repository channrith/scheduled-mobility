package com.mobility.core.driver.availability;

import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.openapi.ProblemResponse;
import com.mobility.core.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/v1/drivers/me/availability")
@Tag(name = "Driver app")
@PreAuthorize("hasRole('DRIVER')")
class DriverAvailabilityController {

	private final CurrentUserProvider currentUser;

	private final DriverDirectory drivers;

	private final DriverAvailabilityService availability;

	DriverAvailabilityController(CurrentUserProvider currentUser, DriverDirectory drivers,
			DriverAvailabilityService availability) {
		this.currentUser = currentUser;
		this.drivers = drivers;
		this.availability = availability;
	}

	@Operation(summary = "My driver availability",
			description = "Reads live availability independently of onboarding status and GPS freshness. "
					+ "No stored state returns UNAVAILABLE, version 0 and updatedAt null. The response must not be cached.")
	@ProblemResponse(status = 404, description = "`driver.not-found`: no driver for the signed-in user")
	@ProblemResponse(status = 503, description = "`availability.unavailable`: Redis is unavailable or stored state is invalid")
	@GetMapping
	ResponseEntity<AvailabilityResponse> get() {
		var driver = drivers.byUserId(currentUser.require().userId())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(availability.get(driver.id()));
	}

	@Operation(summary = "Set my driver availability",
			description = "Requires Idempotency-Key; reuse the same key and identical body to retry one operation. "
					+ "AVAILABLE requires APPROVED onboarding and current dispatch readiness. UNAVAILABLE does not. "
					+ "expectedVersion must match even for an unchanged status. Actual changes increment version; "
					+ "unchanged status preserves version and updatedAt. GPS freshness is separate. "
					+ "Availability mutation and idempotency response caching are separate Redis operations: "
					+ "if caching fails after a save, retry may return idempotency.in-progress or concurrent-update; "
					+ "GET availability to reconcile. Responses must not be cached.")
	@ProblemResponse(status = 400, description = "Invalid status/expectedVersion or missing/invalid Idempotency-Key")
	@ProblemResponse(status = 404, description = "`driver.not-found`: no driver for the signed-in user")
	@ProblemResponse(status = 409, description = "`concurrent-update`: GET the latest version; `idempotency.in-progress`: retry the same operation shortly. "
			+ "`availability.approval-required`: complete onboarding or resolve suspension. "
			+ "`driver.vehicle-required`: obtain an active vehicle assignment. "
			+ "`driver.documents-missing`, `driver.documents-not-approved`, `driver.documents-expired`: upload or renew the listed documents, or await approval.")
	@ProblemResponse(status = 503, description = "`availability.unavailable` or `idempotency.unavailable`: Redis failure or invalid state; GET to reconcile before a new operation")
	@PutMapping
	ResponseEntity<AvailabilityResponse> update(
			@Parameter(required = true, description = "Required, 1–255 characters. Reuse the same key and identical body for retries of one operation.")
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody UpdateAvailabilityRequest request) {
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "idempotency.key-required");
		}
		var driver = drivers.byUserId(currentUser.require().userId())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(availability.update(driver.id(), request));
	}
}

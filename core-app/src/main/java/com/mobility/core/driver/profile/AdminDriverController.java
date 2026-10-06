package com.mobility.core.driver.profile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentService.StoredFile;
import com.mobility.core.driver.document.DocumentView;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.driver.profile.DriverOnboardingService.RegisterDriver;
import com.mobility.core.driver.profile.DriverViews.DriverDetail;
import com.mobility.core.driver.profile.DriverViews.DriverSummary;
import com.mobility.core.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/drivers")
@Tag(name = "Admin · Drivers", description = "Driver onboarding, review and suspension. Personal data is always masked.")
class AdminDriverController {

	static final String VIEW = "hasAnyRole('ADMIN', 'DISPATCHER', 'SUPPORT', 'SAFETY_OFFICER')";

	static final String ADMIN = "hasRole('ADMIN')";

	static final String SUSPEND = "hasAnyRole('ADMIN', 'SAFETY_OFFICER')";

	static final String VIEW_DOCUMENTS = "hasAnyRole('ADMIN', 'SAFETY_OFFICER')";

	private final DriverOnboardingService onboarding;

	private final DriverViews views;

	private final DocumentService documents;

	private final VehicleService vehicles;

	AdminDriverController(DriverOnboardingService onboarding, DriverViews views, DocumentService documents,
			VehicleService vehicles) {
		this.onboarding = onboarding;
		this.views = views;
		this.documents = documents;
		this.vehicles = vehicles;
	}

	@Operation(summary = "Register a driver", description = "Creates the driver (PENDING) and their login (DRIVER role) for the phone number; an existing passenger account is reused. National ID and bank account are encrypted at rest.")
	@PostMapping
	@PreAuthorize(ADMIN)
	ResponseEntity<DriverDetail> register(@Valid @RequestBody RegisterDriverRequest request) {
		Driver driver = onboarding.register(new RegisterDriver(request.phone(), request.fullName(), request.nationalId(),
				request.bankName(), request.bankAccountName(), request.bankAccountNumber()));
		return ResponseEntity.created(URI.create("/api/v1/admin/drivers/" + driver.getId()))
			.body(views.detail(driver, true));
	}

	@Operation(summary = "List drivers", description = "Newest first. Filter by `status`; `size` is 1–100.")
	@GetMapping
	@PreAuthorize(VIEW)
	PageResponse<DriverSummary> list(@RequestParam(required = false) DriverStatus status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return PageResponse.of(onboarding.list(status, page, size), DriverViews::summary);
	}

	@Operation(summary = "Driver detail", description = "Includes current documents, vehicle, `readiness` (what blocks approval) and status history.")
	@GetMapping("/{driverId}")
	@PreAuthorize(VIEW)
	DriverDetail get(@PathVariable UUID driverId) {
		return views.detail(onboarding.get(driverId), true);
	}

	@Operation(summary = "DOCS_SUBMITTED → TRAINING", description = "Requires NATIONAL_ID, DRIVING_LICENSE and PROFILE_PHOTO approved and not expired.")
	@PostMapping("/{driverId}/training")
	@PreAuthorize(ADMIN)
	DriverDetail startTraining(@PathVariable UUID driverId) {
		return views.detail(onboarding.startTraining(driverId), true);
	}

	@Operation(summary = "TRAINING → APPROVED", description = "Also requires an assigned vehicle with an approved VEHICLE_REGISTRATION. The driver can then be dispatched.")
	@PostMapping("/{driverId}/approve")
	@PreAuthorize(ADMIN)
	DriverDetail approve(@PathVariable UUID driverId) {
		return views.detail(onboarding.approve(driverId), true);
	}

	@Operation(summary = "Reject an applicant (final)", description = "From PENDING, DOCS_SUBMITTED or TRAINING. `reason` of at least 10 characters.")
	@PostMapping("/{driverId}/reject")
	@PreAuthorize(ADMIN)
	DriverDetail reject(@PathVariable UUID driverId, @RequestBody ReasonRequest request) {
		return views.detail(onboarding.reject(driverId, request.reason()), true);
	}

	@Operation(summary = "APPROVED → SUSPENDED", description = "Due process: `reason` (≥ 10 characters) and `noticeAt`, when the driver was notified (defaults to now; not in the future).")
	@PostMapping("/{driverId}/suspend")
	@PreAuthorize(SUSPEND)
	DriverDetail suspend(@PathVariable UUID driverId, @RequestBody SuspendRequest request) {
		return views.detail(onboarding.suspend(driverId, request.reason(), request.noticeAt()), true);
	}

	@Operation(summary = "SUSPENDED → APPROVED", description = "`reason` required; the driver must still meet every approval requirement.")
	@PostMapping("/{driverId}/reinstate")
	@PreAuthorize(ADMIN)
	DriverDetail reinstate(@PathVariable UUID driverId, @RequestBody ReasonRequest request) {
		return views.detail(onboarding.reinstate(driverId, request.reason()), true);
	}

	@Operation(summary = "Assign a vehicle", description = "Ends the driver's previous assignment. A vehicle belongs to one driver at a time (`vehicle.already-assigned`).")
	@PostMapping("/{driverId}/vehicle-assignment")
	@PreAuthorize(ADMIN)
	DriverDetail assignVehicle(@PathVariable UUID driverId, @Valid @RequestBody AssignVehicleRequest request) {
		Driver driver = onboarding.get(driverId);
		vehicles.assign(driverId, request.vehicleId());
		return views.detail(driver, true);
	}

	@Operation(summary = "Unassign the vehicle", description = "An approved driver without a vehicle stays APPROVED but is not dispatch-ready.")
	@DeleteMapping("/{driverId}/vehicle-assignment")
	@PreAuthorize(ADMIN)
	DriverDetail unassignVehicle(@PathVariable UUID driverId) {
		Driver driver = onboarding.get(driverId);
		vehicles.unassign(driverId);
		return views.detail(driver, true);
	}

	@Operation(summary = "Approve a document", description = "Records reviewer and time. Expired documents cannot be approved.")
	@PostMapping("/{driverId}/documents/{documentId}/approve")
	@PreAuthorize(ADMIN)
	DocumentView approveDocument(@PathVariable UUID driverId, @PathVariable UUID documentId) {
		return DocumentView.of(documents.approve(driverId, documentId), true);
	}

	@Operation(summary = "Reject a document", description = "`reason` (≥ 10 characters) is shown to the driver, who must upload a new one.")
	@PostMapping("/{driverId}/documents/{documentId}/reject")
	@PreAuthorize(ADMIN)
	DocumentView rejectDocument(@PathVariable UUID driverId, @PathVariable UUID documentId,
			@RequestBody ReasonRequest request) {
		return DocumentView.of(documents.reject(driverId, documentId, request.reason()), true);
	}

	/** Streams the decrypted file for review. Every access is audited. */
	@Operation(summary = "Download one side of a document",
			description = "`side` is `front` or `back` (see the document's `files`). Decrypted file; every access is "
					+ "written to the audit log.")
	@ApiResponse(responseCode = "200", description = "The file", content = {
			@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
			@Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
			@Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")) })
	@GetMapping("/{driverId}/documents/{documentId}/files/{side}/content")
	@PreAuthorize(VIEW_DOCUMENTS)
	ResponseEntity<byte[]> documentContent(@PathVariable UUID driverId, @PathVariable UUID documentId,
			@PathVariable @io.swagger.v3.oas.annotations.Parameter(schema = @Schema(allowableValues = { "front",
					"back" })) String side) {
		StoredFile file = documents.content(driverId, documentId, side);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(file.filename()).build().toString())
			.cacheControl(CacheControl.noStore())
			.body(file.content());
	}

	record RegisterDriverRequest(@NotBlank String phone, @NotBlank @Size(max = 200) String fullName, String nationalId,
			@Size(max = 100) String bankName, @Size(max = 200) String bankAccountName, String bankAccountNumber) {
	}

	record AssignVehicleRequest(@NotNull UUID vehicleId) {
	}

	/** Reason rules (length) are enforced by the state machine so the error code is consistent. */
	record ReasonRequest(String reason) {
	}

	/** @param noticeAt when the driver was notified of the suspension; defaults to now */
	record SuspendRequest(String reason, Instant noticeAt) {
	}
}

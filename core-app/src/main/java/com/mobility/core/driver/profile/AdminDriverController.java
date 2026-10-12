package com.mobility.core.driver.profile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.HomeZone;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.driver.document.DocumentPreviews;
import com.mobility.core.driver.document.DocumentPreviews.PreviewUrl;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentView;
import com.mobility.core.driver.profile.DriverOnboardingService.DriverFilter;
import com.mobility.core.driver.profile.DriverOnboardingService.RegisterDriver;
import com.mobility.core.driver.profile.DriverOnboardingService.UpdateDriver;
import com.mobility.core.driver.profile.DriverViews.AuditView;
import com.mobility.core.driver.profile.DriverViews.DriverDetail;
import com.mobility.core.driver.profile.DriverViews.DriverSummary;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
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

	private final DocumentPreviews previews;

	private final VehicleService vehicles;

	private final CurrentUserProvider currentUser;

	AdminDriverController(DriverOnboardingService onboarding, DriverViews views, DocumentService documents,
			DocumentPreviews previews, VehicleService vehicles, CurrentUserProvider currentUser) {
		this.onboarding = onboarding;
		this.views = views;
		this.documents = documents;
		this.previews = previews;
		this.vehicles = vehicles;
		this.currentUser = currentUser;
	}

	// --- Driver record -------------------------------------------------------------------------

	@Operation(summary = "Register a driver",
			description = "Creates the driver (PENDING) and their login (DRIVER role) for the phone number; an existing "
					+ "user with that phone gets the DRIVER role instead of a duplicate account. National ID, licence "
					+ "number and bank account are encrypted at rest and must be unique.")
	@PostMapping
	@PreAuthorize(ADMIN)
	ResponseEntity<DriverDetail> register(@Valid @RequestBody RegisterDriverRequest request) {
		Driver driver = onboarding.register(new RegisterDriver(request.phone(), request.fullName(), request.nationalId(),
				request.licenseNumber(), request.homeZone(), request.preferredLanguage(), request.bankName(),
				request.bankAccountName(), request.bankAccountNumber()));
		return ResponseEntity.created(URI.create("/api/v1/admin/drivers/" + driver.getId()))
			.body(views.detail(driver, true));
	}

	@Operation(summary = "Edit driver details",
			description = "Omitted (null) fields stay unchanged. `version` must match the driver's current version "
					+ "(`concurrent-update` otherwise). The phone number is the login and cannot be changed here.")
	@PatchMapping("/{driverId}")
	@PreAuthorize(ADMIN)
	DriverDetail update(@PathVariable UUID driverId, @Valid @RequestBody UpdateDriverRequest request) {
		Driver driver = onboarding.update(driverId, new UpdateDriver(request.version(), request.fullName(),
				request.nationalId(), request.licenseNumber(), request.homeZone(), request.preferredLanguage(),
				request.bankName(), request.bankAccountName(), request.bankAccountNumber()));
		return views.detail(driver, true);
	}

	@Operation(summary = "List and search drivers",
			description = "Newest first; `size` 1–100. `q` matches name, phone (any format) or plate of the current "
					+ "vehicle, or exactly a national ID / licence number. `expiringSoon` = an approved document "
					+ "expired or expiring within 30 days.")
	@GetMapping
	@PreAuthorize(VIEW)
	PageResponse<DriverSummary> list(@RequestParam(required = false) DriverStatus status,
			@RequestParam(required = false) VehicleClass vehicleClass,
			@RequestParam(defaultValue = "false") boolean expiringSoon, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		Page<Driver> result = onboarding.list(new DriverFilter(status, vehicleClass, expiringSoon, q), page, size);
		List<DriverSummary> rows = views.summaries(result.getContent());
		return new PageResponse<>(rows, result.getNumber(), result.getSize(), result.getTotalElements(),
				result.getTotalPages());
	}

	@Operation(summary = "Driver detail",
			description = "Current documents, current vehicle and all assignments, `readiness` (what blocks approval) "
					+ "and status history.")
	@GetMapping("/{driverId}")
	@PreAuthorize(VIEW)
	DriverDetail get(@PathVariable UUID driverId) {
		return views.detail(onboarding.get(driverId), true);
	}

	@Operation(summary = "Audit trail", description = "Staff actions on the driver, their documents and their account, newest first.")
	@GetMapping("/{driverId}/audit")
	@PreAuthorize(VIEW_DOCUMENTS)
	List<AuditView> audit(@PathVariable UUID driverId, @RequestParam(defaultValue = "100") int limit) {
		return views.auditTrail(onboarding.get(driverId), limit);
	}

	// --- Onboarding transitions ----------------------------------------------------------------

	@Operation(summary = "PENDING → DOCS_SUBMITTED on the driver's behalf",
			description = "For in-person onboarding. Requires NATIONAL_ID, DRIVING_LICENSE and PROFILE_PHOTO uploaded.")
	@PostMapping("/{driverId}/submit-documents")
	@PreAuthorize(ADMIN)
	DriverDetail submitDocuments(@PathVariable UUID driverId) {
		return views.detail(onboarding.submitDocumentsForDriver(driverId), true);
	}

	@Operation(summary = "DOCS_SUBMITTED → TRAINING", description = "Requires NATIONAL_ID, DRIVING_LICENSE and PROFILE_PHOTO approved and not expired.")
	@PostMapping("/{driverId}/training")
	@PreAuthorize(ADMIN)
	DriverDetail startTraining(@PathVariable UUID driverId) {
		return views.detail(onboarding.startTraining(driverId), true);
	}

	@Operation(summary = "TRAINING → APPROVED", description = "Also requires a current vehicle assignment with an approved VEHICLE_REGISTRATION. The driver can then be dispatched.")
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

	// --- Vehicle assignments -------------------------------------------------------------------

	@Operation(summary = "Assign a vehicle for a period",
			description = "`validFrom` defaults to now and may be in the future; `validTo` is optional (open-ended). "
					+ "An open-ended assignment of the driver that started earlier ends at `validFrom`. A vehicle "
					+ "belongs to one driver at a time (`vehicle.already-assigned`); other overlaps for the driver "
					+ "are refused (`driver.assignment-overlap`).")
	@PostMapping("/{driverId}/vehicle-assignments")
	@PreAuthorize(ADMIN)
	DriverDetail assignVehicle(@PathVariable UUID driverId, @Valid @RequestBody AssignVehicleRequest request) {
		vehicles.assign(driverId, request.vehicleId(), request.validFrom(), request.validTo());
		return views.detail(onboarding.get(driverId), true);
	}

	@Operation(summary = "End or cancel an assignment", description = "Ends a current assignment now; cancels a future one.")
	@DeleteMapping("/{driverId}/vehicle-assignments/{assignmentId}")
	@PreAuthorize(ADMIN)
	DriverDetail endAssignment(@PathVariable UUID driverId, @PathVariable UUID assignmentId) {
		vehicles.endAssignment(driverId, assignmentId);
		return views.detail(onboarding.get(driverId), true);
	}

	// --- Documents -----------------------------------------------------------------------------

	@Operation(summary = "Upload a document on the driver's behalf", description = DocumentUpload.DESCRIPTION
			+ " Recorded with the staff member as uploader and audited.")
	@com.mobility.core.shared.openapi.ProblemResponse(status = 413, description = "`document.too-large`: over 10 MB")
	@PostMapping(path = "/{driverId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(ADMIN)
	ResponseEntity<DocumentView> uploadDocument(@PathVariable UUID driverId, @Valid @ModelAttribute DocumentUpload form)
			throws IOException {
		Driver driver = onboarding.get(driverId);
		if (!driver.canUploadDocuments()) {
			throw new ApiException(HttpStatus.CONFLICT, "document.upload-not-allowed");
		}
		var document = documents.upload(driverId, form.type(), form.vehicleId(), form.expiresOn(), form.frontBytes(),
				form.backBytes(), currentUser.require().userId());
		return ResponseEntity.status(HttpStatus.CREATED).body(DocumentView.of(document, true));
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

	@Operation(summary = "Get a short-lived preview link for one side of a document",
			description = "`side` is `front` or `back`. The returned `url` (relative to the API base) works without a "
					+ "token for a few minutes and contains no storage path. Issuing it is audited as a view.")
	@PostMapping("/{driverId}/documents/{documentId}/files/{side}/preview-url")
	@PreAuthorize(VIEW_DOCUMENTS)
	PreviewUrl previewUrl(@PathVariable UUID driverId, @PathVariable UUID documentId,
			@PathVariable @Parameter(schema = @Schema(allowableValues = { "front", "back" })) String side) {
		return previews.issue(driverId, documentId, side);
	}

	// --- Requests ------------------------------------------------------------------------------

	record RegisterDriverRequest(@NotBlank String phone, @NotBlank @Size(max = 200) String fullName, String nationalId,
			String licenseNumber, HomeZone homeZone, Language preferredLanguage, @Size(max = 100) String bankName,
			@Size(max = 200) String bankAccountName, String bankAccountNumber) {
	}

	record UpdateDriverRequest(@NotNull Long version, @Size(max = 200) String fullName, String nationalId,
			String licenseNumber, HomeZone homeZone, Language preferredLanguage, @Size(max = 100) String bankName,
			@Size(max = 200) String bankAccountName, String bankAccountNumber) {
	}

	record AssignVehicleRequest(@NotNull UUID vehicleId, Instant validFrom, Instant validTo) {
	}

	/** Reason rules (length) are enforced by the state machine so the error code is consistent. */
	record ReasonRequest(String reason) {
	}

	/** @param noticeAt when the driver was notified of the suspension; defaults to now */
	record SuspendRequest(String reason, Instant noticeAt) {
	}
}

package com.mobility.core.driver.profile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.mobility.core.shared.openapi.ProblemResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.ModelAttribute;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentType;
import com.mobility.core.driver.document.DocumentView;
import com.mobility.core.driver.document.DriverDocument;
import com.mobility.core.driver.profile.DriverViews.DriverDetail;
import com.mobility.core.shared.web.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.mobility.core.identity.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/drivers/me")
@Tag(name = "Driver app", description = "The signed-in driver's profile and documents")
@PreAuthorize("hasRole('DRIVER')")
class DriverSelfController {

	private final DriverOnboardingService onboarding;

	private final DriverViews views;

	private final CurrentUserProvider currentUser;

	private final DocumentService documents;

	DriverSelfController(DriverOnboardingService onboarding, DriverViews views, CurrentUserProvider currentUser,
			DocumentService documents) {
		this.onboarding = onboarding;
		this.views = views;
		this.currentUser = currentUser;
		this.documents = documents;
	}

	@Operation(summary = "My driver profile", description = "Status, documents, vehicle, `readiness` and history. Personal data is masked.")
	@GetMapping
	DriverDetail me() {
		return views.detail(onboarding.getByUserId(currentUser.require().userId()), false);
	}

	@Operation(summary = "My current documents")
	@GetMapping("/documents")
	List<DocumentView> documents() {
		Driver driver = onboarding.getByUserId(currentUser.require().userId());
		return documents.currentDocuments(driver.getId()).stream().map(d -> DocumentView.of(d, false)).toList();
	}

	/**
	 * Multipart upload: {@code type}, {@code file}, {@code expiresOn} (ISO date, for documents that expire)
	 * and {@code vehicleId} (for vehicle documents; must be the vehicle assigned to the driver).
	 */
	@Operation(summary = "Upload a document",
			description = "JPEG, PNG or PDF (checked by content), max 10 MB, encrypted at rest. Replaces the current "
					+ "document of the same type. PROFILE_PHOTO must be an image.")
	@ProblemResponse(status = 413, description = "`document.too-large`: over 10 MB")
	@PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<DocumentView> upload(@Valid @ModelAttribute DocumentUpload form) throws IOException {
		Driver driver = onboarding.getByUserId(currentUser.require().userId());
		if (!driver.canUploadDocuments()) {
			throw new ApiException(HttpStatus.CONFLICT, "document.upload-not-allowed");
		}
		DriverDocument document = documents.upload(driver.getId(), form.type(), form.vehicleId(), form.expiresOn(),
				form.file() == null ? null : form.file().getBytes());
		return ResponseEntity.status(HttpStatus.CREATED).body(DocumentView.of(document, false));
	}

	/** PENDING → DOCS_SUBMITTED once every required personal document is uploaded. */
	@Operation(summary = "Submit documents for review", description = "PENDING → DOCS_SUBMITTED. Requires NATIONAL_ID, DRIVING_LICENSE and PROFILE_PHOTO (`driver.documents-missing` lists what is missing).")
	@PostMapping("/submit")
	DriverDetail submit() {
		return views.detail(onboarding.submitDocuments(currentUser.require().userId()), false);
	}

	/** Multipart form fields of a document upload. */
	record DocumentUpload(@NotNull DocumentType type,
			@Schema(description = "Required for VEHICLE_* documents: the vehicle assigned to you") UUID vehicleId,
			@Schema(description = "Required for documents that expire (all except PROFILE_PHOTO)")
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
			@NotNull @Schema(type = "string", format = "binary") MultipartFile file) {
	}
}

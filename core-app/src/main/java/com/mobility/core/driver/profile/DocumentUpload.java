package com.mobility.core.driver.profile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

import com.mobility.core.driver.document.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

/** Multipart form fields of a document upload (by the driver or by staff on their behalf). */
record DocumentUpload(@NotNull DocumentType type,
		@Schema(description = "Required for VEHICLE_REGISTRATION: the vehicle assigned to the driver") UUID vehicleId,
		@Schema(description = "Required for documents that expire (all except PROFILE_PHOTO)")
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
		@NotNull @Schema(type = "string", format = "binary", description = "Front side (or a PDF of both sides)")
		MultipartFile front,
		@Schema(type = "string", format = "binary",
				description = "Back side; required for two-sided documents unless `front` is a PDF") MultipartFile back) {

	static final String DESCRIPTION = "JPEG, PNG or PDF (checked by content), max 10 MB per side, encrypted at rest. "
			+ "Replaces the current document of the same type. NATIONAL_ID, DRIVING_LICENSE and VEHICLE_REGISTRATION are "
			+ "two-sided: send `front` and `back` photos, or a single PDF scan of both sides as `front`. "
			+ "PROFILE_PHOTO must be an image and has no back.";

	byte[] frontBytes() throws IOException {
		return front == null ? null : front.getBytes();
	}

	byte[] backBytes() throws IOException {
		return back == null ? null : back.getBytes();
	}
}

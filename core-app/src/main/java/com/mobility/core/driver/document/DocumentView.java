package com.mobility.core.driver.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** @param reviewedBy staff user id; omitted in the driver's own view */
public record DocumentView(UUID id, DocumentType type, UUID vehicleId, DocumentStatus status, String contentType,
		int sizeBytes, LocalDate expiresOn, Instant uploadedAt, Instant reviewedAt, UUID reviewedBy,
		String rejectionReason, Instant expiryFlaggedAt) {

	public static DocumentView of(DriverDocument d, boolean forStaff) {
		return new DocumentView(d.getId(), d.getType(), d.getVehicleId(), d.getStatus(), d.getContentType(),
				d.getSizeBytes(), d.getExpiresOn(), d.getUploadedAt(), d.getReviewedAt(),
				forStaff ? d.getReviewedBy() : null, d.getRejectionReason(), d.getExpiryFlaggedAt());
	}
}

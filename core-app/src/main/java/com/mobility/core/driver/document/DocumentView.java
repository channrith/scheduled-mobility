package com.mobility.core.driver.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param files one per uploaded side, front first
 * @param reviewedBy staff user id; omitted in the driver's own view
 */
public record DocumentView(UUID id, DocumentType type, UUID vehicleId, DocumentStatus status, List<FileView> files,
		LocalDate expiresOn, Instant uploadedAt, Instant reviewedAt, UUID reviewedBy, String rejectionReason,
		Instant expiryFlaggedAt) {

	public record FileView(DocumentSide side, String contentType, int sizeBytes) {
	}

	public static DocumentView of(DriverDocument d, boolean forStaff) {
		List<FileView> files = d.getFiles()
			.stream()
			.map(f -> new FileView(f.getSide(), f.getContentType(), f.getSizeBytes()))
			.toList();
		return new DocumentView(d.getId(), d.getType(), d.getVehicleId(), d.getStatus(), files, d.getExpiresOn(),
				d.getUploadedAt(), d.getReviewedAt(), forStaff ? d.getReviewedBy() : null, d.getRejectionReason(),
				d.getExpiryFlaggedAt());
	}
}

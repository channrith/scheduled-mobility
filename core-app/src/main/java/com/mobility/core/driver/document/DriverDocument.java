package com.mobility.core.driver.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.mobility.core.shared.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.http.HttpStatus;

/** Metadata of an uploaded document. The file itself is in StorageService under {@link #getStorageKey()}. */
@Entity
@Table(schema = "driver", name = "driver_documents")
public class DriverDocument {

	@Id
	private UUID id;

	@Column(name = "driver_id", nullable = false)
	private UUID driverId;

	@Column(name = "vehicle_id")
	private UUID vehicleId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DocumentType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DocumentStatus status;

	@Column(name = "storage_key", nullable = false, unique = true)
	private String storageKey;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private int sizeBytes;

	@Column(nullable = false)
	private byte[] sha256;

	@Column(name = "expires_on")
	private LocalDate expiresOn;

	@Column(name = "uploaded_at", nullable = false)
	private Instant uploadedAt;

	@Column(name = "reviewed_by")
	private UUID reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "rejection_reason")
	private String rejectionReason;

	@Column(name = "expiry_flagged_at")
	private Instant expiryFlaggedAt;

	@Version
	private Long version;

	protected DriverDocument() {
	}

	DriverDocument(UUID driverId, UUID vehicleId, DocumentType type, String contentType, int sizeBytes, byte[] sha256,
			LocalDate expiresOn, Instant uploadedAt) {
		this.id = UUID.randomUUID();
		this.driverId = driverId;
		this.vehicleId = vehicleId;
		this.type = type;
		this.status = DocumentStatus.PENDING_REVIEW;
		this.storageKey = "drivers/" + driverId + "/" + id;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.expiresOn = expiresOn;
		this.uploadedAt = uploadedAt;
	}

	void approve(UUID reviewer, Instant now, LocalDate today) {
		requirePendingReview();
		if (isExpiredOn(today)) {
			throw new ApiException(HttpStatus.CONFLICT, "document.expired");
		}
		this.status = DocumentStatus.APPROVED;
		this.reviewedBy = reviewer;
		this.reviewedAt = now;
	}

	void reject(UUID reviewer, String reason, Instant now) {
		requirePendingReview();
		this.status = DocumentStatus.REJECTED;
		this.reviewedBy = reviewer;
		this.reviewedAt = now;
		this.rejectionReason = reason;
	}

	void supersede() {
		this.status = DocumentStatus.SUPERSEDED;
	}

	public boolean isExpiredOn(LocalDate today) {
		return expiresOn != null && expiresOn.isBefore(today);
	}

	private void requirePendingReview() {
		if (status != DocumentStatus.PENDING_REVIEW) {
			throw new ApiException(HttpStatus.CONFLICT, "document.not-pending-review");
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getDriverId() {
		return driverId;
	}

	public UUID getVehicleId() {
		return vehicleId;
	}

	public DocumentType getType() {
		return type;
	}

	public DocumentStatus getStatus() {
		return status;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public String getContentType() {
		return contentType;
	}

	public int getSizeBytes() {
		return sizeBytes;
	}

	public LocalDate getExpiresOn() {
		return expiresOn;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public UUID getReviewedBy() {
		return reviewedBy;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	public Instant getExpiryFlaggedAt() {
		return expiryFlaggedAt;
	}
}

package com.mobility.core.driver.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.shared.web.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.http.HttpStatus;

/**
 * An uploaded document (ID card, licence, ...): reviewed once and expiring once, with one file per side
 * ({@link DocumentFile}).
 */
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

	@OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	private List<DocumentFile> files = new ArrayList<>();

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

	DriverDocument(UUID driverId, UUID vehicleId, DocumentType type, LocalDate expiresOn, Instant uploadedAt) {
		this.id = UUID.randomUUID();
		this.driverId = driverId;
		this.vehicleId = vehicleId;
		this.type = type;
		this.status = DocumentStatus.PENDING_REVIEW;
		this.expiresOn = expiresOn;
		this.uploadedAt = uploadedAt;
	}

	DocumentFile addFile(DocumentSide side, String contentType, int sizeBytes, byte[] sha256) {
		DocumentFile file = new DocumentFile(this, side, contentType, sizeBytes, sha256);
		files.add(file);
		return file;
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

	/** Front first. */
	public List<DocumentFile> getFiles() {
		return files.stream().sorted(Comparator.comparing(DocumentFile::getSide)).toList();
	}

	public Optional<DocumentFile> file(DocumentSide side) {
		return files.stream().filter(f -> f.getSide() == side).findFirst();
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

package com.mobility.core.driver.profile;

import static com.mobility.core.driver.DriverStatus.APPROVED;
import static com.mobility.core.driver.DriverStatus.DOCS_SUBMITTED;
import static com.mobility.core.driver.DriverStatus.PENDING;
import static com.mobility.core.driver.DriverStatus.REJECTED;
import static com.mobility.core.driver.DriverStatus.SUSPENDED;
import static com.mobility.core.driver.DriverStatus.TRAINING;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.shared.crypto.EncryptedString;
import com.mobility.core.shared.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.http.HttpStatus;

/**
 * A driver and their onboarding state machine:
 *
 * <pre>
 * PENDING → DOCS_SUBMITTED → TRAINING → APPROVED ⇄ SUSPENDED
 *    └──────────┴──────────────┴──→ REJECTED (final)
 * </pre>
 *
 * Every transition method checks the current status and its guard, and returns a {@link StatusChange}
 * that the caller records in the status history and the audit log.
 */
@Entity
@Table(schema = "driver", name = "drivers")
public class Driver {

	/** Allowed transitions. Operations below additionally apply their own guards. */
	static final Map<DriverStatus, Set<DriverStatus>> TRANSITIONS = new EnumMap<>(Map.of(
			PENDING, EnumSet.of(DOCS_SUBMITTED, REJECTED),
			DOCS_SUBMITTED, EnumSet.of(TRAINING, REJECTED),
			TRAINING, EnumSet.of(APPROVED, REJECTED),
			APPROVED, EnumSet.of(SUSPENDED),
			SUSPENDED, EnumSet.of(APPROVED),
			REJECTED, EnumSet.noneOf(DriverStatus.class)));

	static final int MIN_REASON_LENGTH = 10;

	/** Tolerated clock skew between the admin's device and the server for the notice time. */
	private static final Duration NOTICE_SKEW = Duration.ofMinutes(1);

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false, unique = true)
	private UUID userId;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Column(name = "phone_e164", nullable = false)
	private String phoneE164;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DriverStatus status;

	@Convert(converter = EncryptedString.class)
	@Column(name = "national_id_enc")
	private String nationalId;

	@Column(name = "national_id_hash")
	private byte[] nationalIdHash;

	@Column(name = "bank_name")
	private String bankName;

	@Column(name = "bank_account_name")
	private String bankAccountName;

	@Convert(converter = EncryptedString.class)
	@Column(name = "bank_account_enc")
	private String bankAccountNumber;

	@Column(name = "suspension_reason")
	private String suspensionReason;

	@Column(name = "suspension_notice_at")
	private Instant suspensionNoticeAt;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspended_by")
	private UUID suspendedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Driver() {
	}

	Driver(UUID userId, String fullName, String phoneE164) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.fullName = fullName;
		this.phoneE164 = phoneE164;
		this.status = PENDING;
	}

	/** @param nationalIdHash blind index of the normalized ID, for uniqueness */
	void setNationalId(String normalizedNationalId, byte[] nationalIdHash) {
		this.nationalId = normalizedNationalId;
		this.nationalIdHash = nationalIdHash;
	}

	void setBankAccount(String bankName, String accountName, String normalizedAccountNumber) {
		this.bankName = bankName;
		this.bankAccountName = accountName;
		this.bankAccountNumber = normalizedAccountNumber;
	}

	// --- State machine -------------------------------------------------------------------------

	/** Driver: all required documents are uploaded and ready for review. */
	public StatusChange submitDocuments(Readiness readiness) {
		requireStatus(DOCS_SUBMITTED, PENDING);
		if (!readiness.allUploaded()) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.documents-missing")
				.withProperty("missing", Readiness.names(readiness.missing()));
		}
		return moveTo(DOCS_SUBMITTED, null, null);
	}

	/** Admin: documents verified, driver starts training. */
	public StatusChange startTraining(Readiness readiness) {
		requireStatus(TRAINING, DOCS_SUBMITTED);
		requireDocumentsValid(readiness);
		return moveTo(TRAINING, null, null);
	}

	/** Admin: training complete; the driver can be dispatched. */
	public StatusChange approve(Readiness readiness) {
		requireStatus(APPROVED, TRAINING);
		requireDispatchReady(readiness);
		return moveTo(APPROVED, null, null);
	}

	/** Admin: end onboarding. Final. */
	public StatusChange reject(String reason) {
		requireStatus(REJECTED, PENDING, DOCS_SUBMITTED, TRAINING);
		return moveTo(REJECTED, requireReason(reason), null);
	}

	/**
	 * Admin/safety: stop an approved driver from operating. Regulatory due process requires a reason and
	 * the time the driver was notified.
	 *
	 * @param noticeAt when the driver was notified; {@code null} means now; never in the future
	 */
	public StatusChange suspend(String reason, Instant noticeAt, UUID actor, Instant now) {
		requireStatus(SUSPENDED, APPROVED);
		String validReason = requireReason(reason);
		Instant notice = noticeAt == null ? now : noticeAt;
		if (notice.isAfter(now.plus(NOTICE_SKEW))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "driver.notice-in-future");
		}
		this.suspensionReason = validReason;
		this.suspensionNoticeAt = notice;
		this.suspendedAt = now;
		this.suspendedBy = actor;
		return moveTo(SUSPENDED, validReason, notice);
	}

	/** Admin: lift a suspension. The driver must still meet every approval requirement. */
	public StatusChange reinstate(String reason, Readiness readiness) {
		requireStatus(APPROVED, SUSPENDED);
		String validReason = requireReason(reason);
		requireDispatchReady(readiness);
		this.suspensionReason = null;
		this.suspensionNoticeAt = null;
		this.suspendedAt = null;
		this.suspendedBy = null;
		return moveTo(APPROVED, validReason, null);
	}

	public boolean canUploadDocuments() {
		return status != REJECTED;
	}

	private void requireStatus(DriverStatus target, DriverStatus... allowedFrom) {
		boolean allowed = EnumSet.of(allowedFrom[0], allowedFrom).contains(status)
				&& TRANSITIONS.get(status).contains(target);
		if (!allowed) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.invalid-transition", status, target)
				.withProperty("from", status)
				.withProperty("to", target);
		}
	}

	private static void requireDocumentsValid(Readiness readiness) {
		if (!readiness.missing().isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.documents-missing")
				.withProperty("missing", Readiness.names(readiness.missing()));
		}
		if (!readiness.pendingReview().isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.documents-not-approved")
				.withProperty("pendingReview", Readiness.names(readiness.pendingReview()));
		}
		if (!readiness.expired().isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.documents-expired")
				.withProperty("expired", Readiness.names(readiness.expired()));
		}
	}

	private static void requireDispatchReady(Readiness readiness) {
		if (!readiness.hasActiveVehicle()) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.vehicle-required");
		}
		requireDocumentsValid(readiness);
	}

	private static String requireReason(String reason) {
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.length() < MIN_REASON_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "driver.reason-required", MIN_REASON_LENGTH);
		}
		return trimmed;
	}

	private StatusChange moveTo(DriverStatus target, String reason, Instant noticeAt) {
		StatusChange change = new StatusChange(status, target, reason, noticeAt);
		this.status = target;
		return change;
	}

	// --- Accessors -----------------------------------------------------------------------------

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getFullName() {
		return fullName;
	}

	public String getPhoneE164() {
		return phoneE164;
	}

	public DriverStatus getStatus() {
		return status;
	}

	public String getNationalId() {
		return nationalId;
	}

	public String getBankName() {
		return bankName;
	}

	public String getBankAccountName() {
		return bankAccountName;
	}

	public String getBankAccountNumber() {
		return bankAccountNumber;
	}

	public String getSuspensionReason() {
		return suspensionReason;
	}

	public Instant getSuspensionNoticeAt() {
		return suspensionNoticeAt;
	}

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public UUID getSuspendedBy() {
		return suspendedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}

package com.mobility.core.dispatch.assignment;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.dispatch.RiskTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Dispatch's view of a trip: primary and backup driver with their confirmation deadlines, the last manual change and
 * the current risk tier. The version makes two dispatchers' changes from the same view conflict (409).
 */
@Entity
@Table(schema = "dispatch", name = "trip_assignments")
public class TripAssignment {

	@Id
	@Column(name = "trip_id")
	private UUID tripId;

	@Column(name = "primary_driver_id")
	private UUID primaryDriverId;

	@Column(name = "primary_deadline")
	private Instant primaryDeadline;

	@Column(name = "primary_confirmed_at")
	private Instant primaryConfirmedAt;

	@Column(name = "backup_driver_id")
	private UUID backupDriverId;

	@Column(name = "backup_deadline")
	private Instant backupDeadline;

	@Column(name = "backup_confirmed_at")
	private Instant backupConfirmedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code")
	private ReasonCode reasonCode;

	@Column
	private String note;

	@Column(name = "assigned_by")
	private UUID assignedBy;

	@Column(name = "assigned_at")
	private Instant assignedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_tier")
	private RiskTier riskTier;

	@Column(name = "slack_seconds")
	private Long slackSeconds;

	@Column(name = "risk_reason")
	private String riskReason;

	@Column(name = "risk_evaluated_at")
	private Instant riskEvaluatedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected TripAssignment() {
	}

	/** A new row for a trip dispatch has not handled yet (e.g. assigned before dispatch existed). */
	public static TripAssignment forTrip(UUID tripId, Instant now) {
		return new TripAssignment(tripId, now);
	}

	TripAssignment(UUID tripId, Instant now) {
		this.tripId = tripId;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void setPrimary(UUID driverId, Instant deadline, Instant confirmedAt, ReasonCode reason, String note, UUID by, Instant now) {
		this.primaryDriverId = driverId;
		this.primaryDeadline = driverId == null ? null : deadline;
		this.primaryConfirmedAt = driverId == null ? null : confirmedAt;
		this.reasonCode = reason;
		this.note = note;
		this.assignedBy = by;
		this.assignedAt = now;
		this.riskTier = null;
		this.slackSeconds = null;
		this.riskReason = null;
		this.updatedAt = now;
	}

	void setBackup(UUID driverId, Instant deadline, Instant confirmedAt, Instant now) {
		this.backupDriverId = driverId;
		this.backupDeadline = driverId == null ? null : deadline;
		this.backupConfirmedAt = driverId == null ? null : confirmedAt;
		this.updatedAt = now;
	}

	void confirm(boolean primary, Instant now) {
		if (primary) {
			primaryConfirmedAt = now;
		}
		else {
			backupConfirmedAt = now;
		}
		updatedAt = now;
	}

	public void risk(RiskTier tier, Duration slack, String reason, Instant now) {
		this.riskTier = tier;
		this.slackSeconds = slack == null ? null : slack.toSeconds();
		this.riskReason = reason;
		this.riskEvaluatedAt = now;
	}

	public UUID getTripId() {
		return tripId;
	}

	public UUID getPrimaryDriverId() {
		return primaryDriverId;
	}

	public Instant getPrimaryDeadline() {
		return primaryDeadline;
	}

	public Instant getPrimaryConfirmedAt() {
		return primaryConfirmedAt;
	}

	public UUID getBackupDriverId() {
		return backupDriverId;
	}

	public Instant getBackupDeadline() {
		return backupDeadline;
	}

	public Instant getBackupConfirmedAt() {
		return backupConfirmedAt;
	}

	public ReasonCode getReasonCode() {
		return reasonCode;
	}

	public String getNote() {
		return note;
	}

	public UUID getAssignedBy() {
		return assignedBy;
	}

	public Instant getAssignedAt() {
		return assignedAt;
	}

	public RiskTier getRiskTier() {
		return riskTier;
	}

	public Long getSlackSeconds() {
		return slackSeconds;
	}

	public String getRiskReason() {
		return riskReason;
	}

	public Instant getRiskEvaluatedAt() {
		return riskEvaluatedAt;
	}

	public Long getVersion() {
		return version;
	}
}

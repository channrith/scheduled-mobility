package com.mobility.core.dispatch.issue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnTransformer;

/** One entry of the dispatcher's exception queue (table {@code dispatch.exceptions}). */
@Entity
@Table(schema = "dispatch", name = "exceptions")
public class ExceptionCase {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "exception_type", nullable = false)
	private ExceptionType type;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@Column(name = "driver_id")
	private UUID driverId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Severity severity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ExceptionStatus status;

	@Column(name = "pickup_at", nullable = false)
	private Instant pickupAt;

	@Column(name = "assigned_dispatcher_id")
	private UUID assignedDispatcherId;

	@Convert(converter = DetailsConverter.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> details;

	@Column(name = "opened_at", nullable = false)
	private Instant openedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(name = "resolved_by")
	private UUID resolvedBy;

	@Column(name = "resolution_note")
	private String resolutionNote;

	@Version
	private Long version;

	protected ExceptionCase() {
	}

	ExceptionCase(ExceptionType type, UUID tripId, UUID driverId, Severity severity, Instant pickupAt, Map<String, Object> details,
			Instant now) {
		this.id = UUID.randomUUID();
		this.type = type;
		this.tripId = tripId;
		this.driverId = driverId;
		this.severity = severity;
		this.status = ExceptionStatus.OPEN;
		this.pickupAt = pickupAt;
		this.details = details;
		this.openedAt = now;
		this.updatedAt = now;
	}

	/** @return true if anything changed */
	boolean refresh(UUID driverId, Severity severity, Map<String, Object> details, Instant now) {
		boolean changed = this.severity != severity || !java.util.Objects.equals(this.driverId, driverId)
				|| !java.util.Objects.equals(this.details, details);
		if (changed) {
			this.driverId = driverId;
			this.severity = severity;
			this.details = details;
			this.updatedAt = now;
		}
		return changed;
	}

	void take(UUID dispatcherId, Instant now) {
		this.assignedDispatcherId = dispatcherId;
		this.status = ExceptionStatus.ACKNOWLEDGED;
		this.updatedAt = now;
	}

	void close(ExceptionStatus to, UUID by, String note, Instant now) {
		this.status = to;
		this.resolvedAt = now;
		this.resolvedBy = by;
		this.resolutionNote = note;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public ExceptionType getType() {
		return type;
	}

	public UUID getTripId() {
		return tripId;
	}

	public UUID getDriverId() {
		return driverId;
	}

	public Severity getSeverity() {
		return severity;
	}

	public ExceptionStatus getStatus() {
		return status;
	}

	public Instant getPickupAt() {
		return pickupAt;
	}

	public UUID getAssignedDispatcherId() {
		return assignedDispatcherId;
	}

	public Map<String, Object> getDetails() {
		return details;
	}

	public Instant getOpenedAt() {
		return openedAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public UUID getResolvedBy() {
		return resolvedBy;
	}

	public String getResolutionNote() {
		return resolutionNote;
	}

	public Long getVersion() {
		return version;
	}
}

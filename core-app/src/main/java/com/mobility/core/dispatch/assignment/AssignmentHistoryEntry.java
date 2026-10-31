package com.mobility.core.dispatch.assignment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** One change of a trip's drivers; insert-only (a database trigger refuses updates and deletes). */
@Entity
@Immutable
@Table(schema = "dispatch", name = "assignment_history")
public class AssignmentHistoryEntry implements Persistable<UUID> {

	public enum Action {
		ASSIGNED, REASSIGNED, UNASSIGNED, BACKUP_SET, BACKUP_CLEARED, SWITCHED_TO_BACKUP, CONFIRMED, DECLINED
	}

	public enum Role {
		PRIMARY, BACKUP
	}

	@Id
	private UUID id;

	@Column(insertable = false, updatable = false)
	private Long seq;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Action action;

	@Enumerated(EnumType.STRING)
	@Column
	private Role role;

	@Column(name = "driver_id")
	private UUID driverId;

	@Column(name = "previous_driver_id")
	private UUID previousDriverId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code")
	private ReasonCode reasonCode;

	@Column
	private String note;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Transient
	private boolean isNew;

	protected AssignmentHistoryEntry() {
	}

	AssignmentHistoryEntry(UUID tripId, Action action, Role role, UUID driverId, UUID previousDriverId, ReasonCode reasonCode,
			String note, UUID actorUserId, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.tripId = tripId;
		this.action = action;
		this.role = role;
		this.driverId = driverId;
		this.previousDriverId = previousDriverId;
		this.reasonCode = reasonCode;
		this.note = note;
		this.actorUserId = actorUserId;
		this.occurredAt = occurredAt;
		this.isNew = true;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	public UUID getTripId() {
		return tripId;
	}

	public Action getAction() {
		return action;
	}

	public Role getRole() {
		return role;
	}

	public UUID getDriverId() {
		return driverId;
	}

	public UUID getPreviousDriverId() {
		return previousDriverId;
	}

	public ReasonCode getReasonCode() {
		return reasonCode;
	}

	public String getNote() {
		return note;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}

package com.mobility.core.booking.trip;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** One status change of a trip; insert-only (a database trigger refuses updates and deletes). */
@Entity
@Immutable
@Table(schema = "booking", name = "trip_events")
public class TripEvent implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(insertable = false, updatable = false)
	private Long seq;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status")
	private TripStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false)
	private TripStatus toStatus;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_role", nullable = false)
	private TripActor actorRole;

	@Column
	private String reason;

	@Column(name = "driver_id")
	private UUID driverId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Transient
	private boolean isNew;

	protected TripEvent() {
	}

	TripEvent(UUID tripId, TripStatus from, TripStatus to, UUID actorUserId, TripActor actorRole, String reason,
			UUID driverId, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.tripId = tripId;
		this.fromStatus = from;
		this.toStatus = to;
		this.actorUserId = actorUserId;
		this.actorRole = actorRole;
		this.reason = reason;
		this.driverId = driverId;
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

	public Long getSeq() {
		return seq;
	}

	public UUID getTripId() {
		return tripId;
	}

	public TripStatus getFromStatus() {
		return fromStatus;
	}

	public TripStatus getToStatus() {
		return toStatus;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public TripActor getActorRole() {
		return actorRole;
	}

	public String getReason() {
		return reason;
	}

	public UUID getDriverId() {
		return driverId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}

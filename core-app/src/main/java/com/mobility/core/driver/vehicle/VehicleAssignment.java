package com.mobility.core.driver.vehicle;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "driver", name = "driver_vehicle_assignments")
public class VehicleAssignment implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "driver_id", nullable = false)
	private UUID driverId;

	@Column(name = "vehicle_id", nullable = false)
	private UUID vehicleId;

	@Column(name = "assigned_at", nullable = false)
	private Instant assignedAt;

	@Column(name = "assigned_by")
	private UUID assignedBy;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	/** Exclusive; null = open-ended. Ending an assignment early sets it. */
	@Column(name = "valid_to")
	private Instant validTo;

	@Column(name = "ended_by")
	private UUID endedBy;

	@Transient
	private boolean isNew = true;

	protected VehicleAssignment() {
	}

	VehicleAssignment(UUID driverId, UUID vehicleId, UUID assignedBy, Instant assignedAt, Instant validFrom,
			Instant validTo) {
		this.id = UUID.randomUUID();
		this.driverId = driverId;
		this.vehicleId = vehicleId;
		this.assignedBy = assignedBy;
		this.assignedAt = assignedAt;
		this.validFrom = validFrom;
		this.validTo = validTo;
	}

	/** Ends the assignment at {@code at}; an assignment that has not started yet becomes empty (cancelled). */
	void endAt(UUID by, Instant at) {
		Instant end = at.isBefore(validFrom) ? validFrom : at;
		if (validTo == null || end.isBefore(validTo)) {
			this.validTo = end;
			this.endedBy = by;
		}
	}

	public boolean isActiveAt(Instant t) {
		return !t.isBefore(validFrom) && (validTo == null || t.isBefore(validTo));
	}

	/** Whether [validFrom, validTo) overlaps [from, to); a null end is open. */
	boolean overlaps(Instant from, Instant to) {
		boolean startsBeforeOtherEnds = to == null || validFrom.isBefore(to);
		boolean endsAfterOtherStarts = validTo == null || validTo.isAfter(from);
		return startsBeforeOtherEnds && endsAfterOtherStarts && (validTo == null || validTo.isAfter(validFrom));
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}

	public UUID getDriverId() {
		return driverId;
	}

	public UUID getVehicleId() {
		return vehicleId;
	}

	public Instant getAssignedAt() {
		return assignedAt;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getValidTo() {
		return validTo;
	}
}

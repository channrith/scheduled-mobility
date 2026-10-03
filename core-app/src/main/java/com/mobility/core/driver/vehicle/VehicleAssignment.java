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

	@Column(name = "unassigned_at")
	private Instant unassignedAt;

	@Column(name = "unassigned_by")
	private UUID unassignedBy;

	@Transient
	private boolean isNew = true;

	protected VehicleAssignment() {
	}

	VehicleAssignment(UUID driverId, UUID vehicleId, UUID assignedBy, Instant assignedAt) {
		this.id = UUID.randomUUID();
		this.driverId = driverId;
		this.vehicleId = vehicleId;
		this.assignedBy = assignedBy;
		this.assignedAt = assignedAt;
	}

	void end(UUID by, Instant at) {
		this.unassignedBy = by;
		this.unassignedAt = at;
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
}

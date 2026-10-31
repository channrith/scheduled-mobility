package com.mobility.core.dispatch.shift;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A window in which a driver is available to drive. */
@Entity
@Table(schema = "dispatch", name = "shift_blocks")
public class ShiftBlock {

	@Id
	private UUID id;

	@Column(name = "driver_id", nullable = false)
	private UUID driverId;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(nullable = false)
	private String status;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Version
	private Long version;

	protected ShiftBlock() {
	}

	ShiftBlock(UUID driverId, Instant startsAt, Instant endsAt, UUID createdBy, Instant now) {
		this.id = UUID.randomUUID();
		this.driverId = driverId;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = "ACTIVE";
		this.createdBy = createdBy;
		this.createdAt = now;
	}

	void cancel(Instant now) {
		this.status = "CANCELLED";
		this.cancelledAt = now;
	}

	public boolean covers(Instant from, Instant to) {
		return isActive() && !startsAt.isAfter(from) && !endsAt.isBefore(to);
	}

	public boolean isActive() {
		return "ACTIVE".equals(status);
	}

	public UUID getId() {
		return id;
	}

	public UUID getDriverId() {
		return driverId;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}
}

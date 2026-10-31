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
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One ride of a booking. Status changes go through {@link #moveTo}, guarded by {@link TripStateMachine}; the
 * version column makes two changes from the same state conflict (409) instead of both succeeding.
 */
@Entity
@Table(schema = "booking", name = "trips")
public class Trip {

	@Id
	private UUID id;

	@Column(name = "ride_request_id", nullable = false)
	private UUID rideId;

	@Column(nullable = false)
	private int occurrence;

	@Column(name = "pickup_at", nullable = false)
	private Instant pickupAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TripStatus status;

	@Column(name = "driver_id")
	private UUID driverId;

	@Column(name = "vehicle_id")
	private UUID vehicleId;

	@Column(name = "assigned_at")
	private Instant assignedAt;

	@Column(name = "en_route_at")
	private Instant enRouteAt;

	@Column(name = "arrived_at")
	private Instant arrivedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "cash_collected_minor")
	private Long cashCollectedMinor;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "cash_currency", length = 3)
	private String cashCurrency;

	@Column(name = "cash_reported_at")
	private Instant cashReportedAt;

	@Column(name = "cash_mismatch")
	private Boolean cashMismatch;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Trip() {
	}

	Trip(UUID rideId, int occurrence, Instant pickupAt, Instant now) {
		this.id = UUID.randomUUID();
		this.rideId = rideId;
		this.occurrence = occurrence;
		this.pickupAt = pickupAt;
		this.status = TripStatus.SCHEDULED;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Moves to {@code to} and stamps when; back to SCHEDULED clears the driver. */
	void moveTo(TripStatus to, Instant now) {
		TripStateMachine.check(status, to);
		switch (to) {
			case SCHEDULED -> {
				driverId = null;
				vehicleId = null;
				assignedAt = null;
				enRouteAt = null;
			}
			case ASSIGNED -> {
				assignedAt = now;
				enRouteAt = null;
			}
			case DRIVER_EN_ROUTE -> enRouteAt = now;
			case DRIVER_ARRIVED -> arrivedAt = now;
			case IN_PROGRESS -> startedAt = now;
			case COMPLETED -> {
				if (completedAt == null) {
					completedAt = now;
					endedAt = now;
				}
			}
			case CANCELLED_BY_PASSENGER, CANCELLED_BY_DRIVER, CANCELLED_BY_OPS, NO_SHOW -> endedAt = now;
			case DISPUTED -> {
			}
		}
		status = to;
		updatedAt = now;
	}

	void assign(UUID driverId, UUID vehicleId, Instant now) {
		moveTo(TripStatus.ASSIGNED, now);
		this.driverId = driverId;
		this.vehicleId = vehicleId;
	}

	void reportCash(long amountMinor, String currency, boolean mismatch, Instant now) {
		this.cashCollectedMinor = amountMinor;
		this.cashCurrency = currency;
		this.cashMismatch = mismatch;
		this.cashReportedAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getRideId() {
		return rideId;
	}

	public int getOccurrence() {
		return occurrence;
	}

	public Instant getPickupAt() {
		return pickupAt;
	}

	public TripStatus getStatus() {
		return status;
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

	public Instant getEnRouteAt() {
		return enRouteAt;
	}

	public Instant getArrivedAt() {
		return arrivedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public Long getCashCollectedMinor() {
		return cashCollectedMinor;
	}

	public String getCashCurrency() {
		return cashCurrency;
	}

	public Instant getCashReportedAt() {
		return cashReportedAt;
	}

	public Boolean getCashMismatch() {
		return cashMismatch;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** The time of the last change, or {@code fallback} if none was stamped. */
	Instant getUpdatedAtOr(Instant fallback) {
		return updatedAt == null ? fallback : updatedAt;
	}
}

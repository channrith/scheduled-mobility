package com.mobility.core.driver.profile;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.driver.DriverStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "driver", name = "driver_status_history")
public class StatusHistoryEntry implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "driver_id", nullable = false)
	private UUID driverId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status")
	private DriverStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false)
	private DriverStatus toStatus;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	private String reason;

	@Column(name = "notice_at")
	private Instant noticeAt;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected StatusHistoryEntry() {
	}

	StatusHistoryEntry(UUID driverId, DriverStatus from, DriverStatus to, UUID actorUserId, String reason,
			Instant noticeAt, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.driverId = driverId;
		this.fromStatus = from;
		this.toStatus = to;
		this.actorUserId = actorUserId;
		this.reason = reason;
		this.noticeAt = noticeAt;
		this.occurredAt = occurredAt;
	}

	@Override
	public UUID getId() {
		return id;
	}

	/** Always inserted, never updated. */
	@Override
	public boolean isNew() {
		return true;
	}

	public DriverStatus getFromStatus() {
		return fromStatus;
	}

	public DriverStatus getToStatus() {
		return toStatus;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public String getReason() {
		return reason;
	}

	public Instant getNoticeAt() {
		return noticeAt;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}

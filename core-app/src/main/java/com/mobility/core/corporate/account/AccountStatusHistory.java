package com.mobility.core.corporate.account;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.corporate.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

@Entity
@Table(schema = "corporate", name = "account_status_history")
public class AccountStatusHistory implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status")
	private AccountStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false)
	private AccountStatus toStatus;

	private String reason;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected AccountStatusHistory() {
	}

	AccountStatusHistory(UUID accountId, AccountStatus from, AccountStatus to, String reason, UUID actor, Instant at) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.fromStatus = from;
		this.toStatus = to;
		this.reason = reason;
		this.actorUserId = actor;
		this.occurredAt = at;
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

	public AccountStatus getFromStatus() {
		return fromStatus;
	}

	public AccountStatus getToStatus() {
		return toStatus;
	}

	public String getReason() {
		return reason;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}

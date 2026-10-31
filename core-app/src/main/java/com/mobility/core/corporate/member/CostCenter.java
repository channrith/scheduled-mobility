package com.mobility.core.corporate.member;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(schema = "corporate", name = "cost_centers")
public class CostCenter {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Column(nullable = false)
	private String code;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private boolean active;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Version
	private Long version;

	protected CostCenter() {
	}

	CostCenter(UUID accountId, String code, String name) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.code = code;
		this.name = name;
		this.active = true;
	}

	void update(String name, boolean active) {
		this.name = name;
		this.active = active;
	}

	public UUID getId() {
		return id;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public boolean isActive() {
		return active;
	}

	public Long getVersion() {
		return version;
	}
}

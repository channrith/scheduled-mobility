package com.mobility.core.pricing.settings;

import java.math.BigDecimal;
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

/** One change of a setting; rows are insert-only (enforced by a database trigger). */
@Entity
@Immutable
@Table(schema = "pricing", name = "settings_history")
public class SettingChange implements Persistable<UUID> {

	@Id
	private UUID id;

	/** Assigned by the database (identity column). */
	@Column(insertable = false, updatable = false)
	private Long seq;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Setting setting;

	@Column(nullable = false, precision = 12, scale = 4)
	private BigDecimal value;

	@Column(name = "changed_by")
	private UUID changedBy;

	@Column(name = "changed_at", nullable = false)
	private Instant changedAt;

	@Transient
	private boolean isNew;

	protected SettingChange() {
	}

	SettingChange(Setting setting, BigDecimal value, UUID changedBy, Instant changedAt) {
		this.id = UUID.randomUUID();
		this.setting = setting;
		this.value = value;
		this.changedBy = changedBy;
		this.changedAt = changedAt;
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

	public Setting getSetting() {
		return setting;
	}

	public BigDecimal getValue() {
		return value;
	}

	public UUID getChangedBy() {
		return changedBy;
	}

	public Instant getChangedAt() {
		return changedAt;
	}
}

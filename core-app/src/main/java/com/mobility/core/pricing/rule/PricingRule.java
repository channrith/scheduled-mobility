package com.mobility.core.pricing.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One version of a price line. Never changed after insert (quotes reference it; a database trigger refuses
 * updates and deletes): a price change is a new version. Money in minor units of {@link #getCurrency()}.
 */
@Entity
@Immutable
@Table(schema = "pricing", name = "pricing_rules")
public class PricingRule implements Persistable<UUID> {

	@Id
	private UUID id;

	/** Creation order (identity column); breaks ties between versions with the same valid_from. */
	@Column(insertable = false, updatable = false)
	private Long seq;

	@Column(nullable = false)
	private String city;

	@Enumerated(EnumType.STRING)
	@Column(name = "vehicle_class", nullable = false)
	private VehicleClass vehicleClass;

	@Column(name = "corporate_id")
	private UUID corporateId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "base_minor", nullable = false)
	private long baseMinor;

	@Column(name = "per_km_minor", nullable = false)
	private long perKmMinor;

	@Column(name = "per_min_minor", nullable = false)
	private long perMinMinor;

	@Column(name = "min_fare_minor", nullable = false)
	private long minFareMinor;

	@Column(name = "fuel_index_factor", nullable = false, precision = 4, scale = 3)
	private BigDecimal fuelIndexFactor;

	@Column(name = "max_surge", nullable = false, precision = 4, scale = 2)
	private BigDecimal maxSurge;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	@Column(name = "valid_to")
	private Instant validTo;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew;

	protected PricingRule() {
	}

	PricingRule(RuleTerms terms, UUID createdBy, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.city = terms.city();
		this.vehicleClass = terms.vehicleClass();
		this.corporateId = terms.corporateId();
		this.currency = terms.currency();
		this.baseMinor = terms.baseMinor();
		this.perKmMinor = terms.perKmMinor();
		this.perMinMinor = terms.perMinMinor();
		this.minFareMinor = terms.minFareMinor();
		this.fuelIndexFactor = terms.fuelIndexFactor();
		this.maxSurge = terms.maxSurge();
		this.validFrom = terms.validFrom();
		this.validTo = terms.validTo();
		this.active = terms.active();
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.isNew = true;
	}

	/** In force at {@code at}, assuming no later version of the line has started. */
	public boolean appliesAt(Instant at) {
		return active && !validFrom.isAfter(at) && (validTo == null || at.isBefore(validTo));
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

	public String getCity() {
		return city;
	}

	public VehicleClass getVehicleClass() {
		return vehicleClass;
	}

	public UUID getCorporateId() {
		return corporateId;
	}

	public String getCurrency() {
		return currency;
	}

	public long getBaseMinor() {
		return baseMinor;
	}

	public long getPerKmMinor() {
		return perKmMinor;
	}

	public long getPerMinMinor() {
		return perMinMinor;
	}

	public long getMinFareMinor() {
		return minFareMinor;
	}

	public BigDecimal getFuelIndexFactor() {
		return fuelIndexFactor;
	}

	public BigDecimal getMaxSurge() {
		return maxSurge;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getValidTo() {
		return validTo;
	}

	public boolean isActive() {
		return active;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}

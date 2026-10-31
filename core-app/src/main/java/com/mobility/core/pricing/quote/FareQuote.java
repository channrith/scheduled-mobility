package com.mobility.core.pricing.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.pricing.quote.FareCalculator.Line;
import com.mobility.core.pricing.routing.Route.RoutingSource;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** A fare quote: the final fare of the ride if booked before {@link #getExpiresAt()}. Never changed. */
@Entity
@Immutable
@Table(schema = "pricing", name = "fare_quotes")
public class FareQuote implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "requested_by", nullable = false)
	private UUID requestedBy;

	@Column(name = "corporate_id")
	private UUID corporateId;

	@Column(nullable = false)
	private String city;

	@Enumerated(EnumType.STRING)
	@Column(name = "vehicle_class", nullable = false)
	private VehicleClass vehicleClass;

	@Column(name = "pickup_place_id")
	private UUID pickupPlaceId;

	@Column(name = "pickup_lat", nullable = false)
	private double pickupLat;

	@Column(name = "pickup_lng", nullable = false)
	private double pickupLng;

	@Column(name = "dropoff_place_id")
	private UUID dropoffPlaceId;

	@Column(name = "dropoff_lat", nullable = false)
	private double dropoffLat;

	@Column(name = "dropoff_lng", nullable = false)
	private double dropoffLng;

	@Column(name = "pickup_at", nullable = false)
	private Instant pickupAt;

	@Column(name = "rule_id", nullable = false)
	private UUID ruleId;

	@Column(name = "distance_meters", nullable = false)
	private long distanceMeters;

	@Column(name = "duration_seconds", nullable = false)
	private long durationSeconds;

	@Enumerated(EnumType.STRING)
	@Column(name = "routing_source", nullable = false)
	private RoutingSource routingSource;

	@Column(name = "fuel_index", nullable = false, precision = 12, scale = 4)
	private BigDecimal fuelIndex;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal surge;

	@Column(name = "usd_khr_rate", nullable = false, precision = 12, scale = 4)
	private BigDecimal usdKhrRate;

	@Column(name = "airport_zone_id")
	private UUID airportZoneId;

	@Convert(converter = BreakdownConverter.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Line> breakdown;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "total_minor", nullable = false)
	private long totalMinor;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "equivalent_currency", nullable = false, length = 3)
	private String equivalentCurrency;

	@Column(name = "equivalent_minor", nullable = false)
	private long equivalentMinor;

	@Column(name = "service_fee_pct", precision = 5, scale = 2)
	private BigDecimal serviceFeePct;

	@Column(name = "service_fee_minor")
	private Long serviceFeeMinor;

	@Column(nullable = false)
	private Instant eta;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew;

	protected FareQuote() {
	}

	/** Everything a quote records; built by {@link QuoteService}. */
	record Content(UUID requestedBy, UUID corporateId, String city, VehicleClass vehicleClass, UUID pickupPlaceId,
			double pickupLat, double pickupLng, UUID dropoffPlaceId, double dropoffLat, double dropoffLng, Instant pickupAt,
			UUID ruleId, long distanceMeters, long durationSeconds, RoutingSource routingSource, BigDecimal fuelIndex,
			BigDecimal usdKhrRate, UUID airportZoneId, FareCalculator.Fare fare, BigDecimal serviceFeePct, Instant eta,
			Instant expiresAt, Instant createdAt) {
	}

	FareQuote(Content c) {
		this.id = UUID.randomUUID();
		this.requestedBy = c.requestedBy();
		this.corporateId = c.corporateId();
		this.city = c.city();
		this.vehicleClass = c.vehicleClass();
		this.pickupPlaceId = c.pickupPlaceId();
		this.pickupLat = c.pickupLat();
		this.pickupLng = c.pickupLng();
		this.dropoffPlaceId = c.dropoffPlaceId();
		this.dropoffLat = c.dropoffLat();
		this.dropoffLng = c.dropoffLng();
		this.pickupAt = c.pickupAt();
		this.ruleId = c.ruleId();
		this.distanceMeters = c.distanceMeters();
		this.durationSeconds = c.durationSeconds();
		this.routingSource = c.routingSource();
		this.fuelIndex = c.fuelIndex();
		this.surge = c.fare().surge();
		this.usdKhrRate = c.usdKhrRate();
		this.airportZoneId = c.airportZoneId();
		this.breakdown = c.fare().lines();
		this.currency = c.fare().currency();
		this.totalMinor = c.fare().totalMinor();
		this.equivalentCurrency = c.fare().equivalentCurrency();
		this.equivalentMinor = c.fare().equivalentMinor();
		this.serviceFeePct = c.serviceFeePct();
		this.serviceFeeMinor = c.fare().serviceFeeMinor();
		this.eta = c.eta();
		this.expiresAt = c.expiresAt();
		this.createdAt = c.createdAt();
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

	public UUID getRequestedBy() {
		return requestedBy;
	}

	public UUID getCorporateId() {
		return corporateId;
	}

	public String getCity() {
		return city;
	}

	public VehicleClass getVehicleClass() {
		return vehicleClass;
	}

	public UUID getPickupPlaceId() {
		return pickupPlaceId;
	}

	public double getPickupLat() {
		return pickupLat;
	}

	public double getPickupLng() {
		return pickupLng;
	}

	public UUID getDropoffPlaceId() {
		return dropoffPlaceId;
	}

	public double getDropoffLat() {
		return dropoffLat;
	}

	public double getDropoffLng() {
		return dropoffLng;
	}

	public Instant getPickupAt() {
		return pickupAt;
	}

	public UUID getRuleId() {
		return ruleId;
	}

	public long getDistanceMeters() {
		return distanceMeters;
	}

	public long getDurationSeconds() {
		return durationSeconds;
	}

	public RoutingSource getRoutingSource() {
		return routingSource;
	}

	public BigDecimal getFuelIndex() {
		return fuelIndex;
	}

	public BigDecimal getSurge() {
		return surge;
	}

	public BigDecimal getUsdKhrRate() {
		return usdKhrRate;
	}

	public UUID getAirportZoneId() {
		return airportZoneId;
	}

	public List<Line> getBreakdown() {
		return breakdown;
	}

	public String getCurrency() {
		return currency;
	}

	public long getTotalMinor() {
		return totalMinor;
	}

	public String getEquivalentCurrency() {
		return equivalentCurrency;
	}

	public long getEquivalentMinor() {
		return equivalentMinor;
	}

	public BigDecimal getServiceFeePct() {
		return serviceFeePct;
	}

	public Long getServiceFeeMinor() {
		return serviceFeeMinor;
	}

	public Instant getEta() {
		return eta;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}

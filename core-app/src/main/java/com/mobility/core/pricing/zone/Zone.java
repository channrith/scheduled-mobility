package com.mobility.core.pricing.zone;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** A priced area. The polygon is held as GeoJSON text; the column is a PostGIS geography. */
@Entity
@Table(schema = "pricing", name = "zones")
public class Zone {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String city;

	@Enumerated(EnumType.STRING)
	@Column(name = "zone_type", nullable = false)
	private ZoneType type;

	@Column(name = "name_km")
	private String nameKm;

	@Column(name = "name_en")
	private String nameEn;

	@Column(nullable = false, columnDefinition = "geography")
	@ColumnTransformer(read = "ST_AsGeoJSON(area)", write = "ST_GeomFromGeoJSON(?)::geography")
	private String area;

	@Column(name = "surcharge_minor")
	private Long surchargeMinor;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "surcharge_currency", length = 3)
	private String surchargeCurrency;

	@Column(nullable = false)
	private boolean active;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Zone() {
	}

	Zone(ZoneDetails details) {
		this.id = UUID.randomUUID();
		apply(details);
	}

	void apply(ZoneDetails d) {
		this.city = d.city();
		this.type = d.type();
		this.nameKm = d.nameKm();
		this.nameEn = d.nameEn();
		this.area = d.geoJson();
		this.surchargeMinor = d.surchargeMinor();
		this.surchargeCurrency = d.surchargeCurrency();
		this.active = d.active();
	}

	public UUID getId() {
		return id;
	}

	public String getCity() {
		return city;
	}

	public ZoneType getType() {
		return type;
	}

	public String getNameKm() {
		return nameKm;
	}

	public String getNameEn() {
		return nameEn;
	}

	/** GeoJSON Polygon text. */
	public String getArea() {
		return area;
	}

	public Long getSurchargeMinor() {
		return surchargeMinor;
	}

	public String getSurchargeCurrency() {
		return surchargeCurrency;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}
}

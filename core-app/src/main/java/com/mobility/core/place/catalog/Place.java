package com.mobility.core.place.catalog;

import java.time.Instant;
import java.util.UUID;

import com.mobility.core.place.PlaceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A landmark. The PostGIS {@code location} column is generated from latitude/longitude by the database. */
@Entity
@Table(schema = "place", name = "places")
public class Place {

	@Id
	private UUID id;

	@Column(name = "name_km")
	private String nameKm;

	@Column(name = "name_en")
	private String nameEn;

	@Column(nullable = false)
	private double latitude;

	@Column(nullable = false)
	private double longitude;

	@Enumerated(EnumType.STRING)
	@Column(name = "place_type", nullable = false)
	private PlaceType type;

	@Column(name = "pickup_notes_km")
	private String pickupNotesKm;

	@Column(name = "pickup_notes_en")
	private String pickupNotesEn;

	@Column(name = "usage_count", nullable = false)
	private long usageCount;

	@Column(nullable = false)
	private boolean verified;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PlaceStatus status;

	@Column(name = "merged_into")
	private UUID mergedInto;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Place() {
	}

	Place(PlaceDetails details) {
		this.id = UUID.randomUUID();
		this.status = PlaceStatus.ACTIVE;
		apply(details);
	}

	/** Editable fields, already validated. */
	record PlaceDetails(String nameKm, String nameEn, double latitude, double longitude, PlaceType type,
			String pickupNotesKm, String pickupNotesEn, boolean verified) {
	}

	void apply(PlaceDetails d) {
		this.nameKm = d.nameKm();
		this.nameEn = d.nameEn();
		this.latitude = d.latitude();
		this.longitude = d.longitude();
		this.type = d.type();
		this.pickupNotesKm = d.pickupNotesKm();
		this.pickupNotesEn = d.pickupNotesEn();
		this.verified = d.verified();
	}

	void mergeInto(Place target) {
		target.usageCount += this.usageCount;
		this.status = PlaceStatus.MERGED;
		this.mergedInto = target.id;
	}

	public boolean isActive() {
		return status == PlaceStatus.ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public String getNameKm() {
		return nameKm;
	}

	public String getNameEn() {
		return nameEn;
	}

	public double getLatitude() {
		return latitude;
	}

	public double getLongitude() {
		return longitude;
	}

	public PlaceType getType() {
		return type;
	}

	public String getPickupNotesKm() {
		return pickupNotesKm;
	}

	public String getPickupNotesEn() {
		return pickupNotesEn;
	}

	public long getUsageCount() {
		return usageCount;
	}

	public boolean isVerified() {
		return verified;
	}

	public PlaceStatus getStatus() {
		return status;
	}

	public UUID getMergedInto() {
		return mergedInto;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}
}

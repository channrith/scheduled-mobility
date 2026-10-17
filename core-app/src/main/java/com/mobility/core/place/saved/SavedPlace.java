package com.mobility.core.place.saved;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A user's shortcut: a landmark ({@code placeId}) or their own pin with a note. */
@Entity
@Table(schema = "place", name = "saved_places")
public class SavedPlace {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SavedLabel label;

	@Column(name = "custom_label")
	private String customLabel;

	@Column(name = "place_id")
	private UUID placeId;

	private Double latitude;

	private Double longitude;

	private String note;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected SavedPlace() {
	}

	SavedPlace(UUID userId) {
		this.id = UUID.randomUUID();
		this.userId = userId;
	}

	void apply(SavedLabel label, String customLabel, UUID placeId, Double latitude, Double longitude, String note) {
		this.label = label;
		this.customLabel = customLabel;
		this.placeId = placeId;
		this.latitude = latitude;
		this.longitude = longitude;
		this.note = note;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public SavedLabel getLabel() {
		return label;
	}

	public String getCustomLabel() {
		return customLabel;
	}

	public UUID getPlaceId() {
		return placeId;
	}

	public Double getLatitude() {
		return latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public String getNote() {
		return note;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}
}

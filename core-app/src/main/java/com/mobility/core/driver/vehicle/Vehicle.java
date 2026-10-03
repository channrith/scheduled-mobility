package com.mobility.core.driver.vehicle;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "driver", name = "vehicles")
public class Vehicle {

	@Id
	private UUID id;

	@Column(name = "plate_number", nullable = false, unique = true)
	private String plateNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "vehicle_class", nullable = false)
	private VehicleClass vehicleClass;

	private String make;

	private String model;

	private String color;

	@Column(name = "model_year")
	private Short modelYear;

	@Column(nullable = false)
	private short seats;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private VehicleStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Vehicle() {
	}

	Vehicle(String plateNumber, VehicleClass vehicleClass, String make, String model, String color, Short modelYear,
			short seats) {
		this.id = UUID.randomUUID();
		this.plateNumber = normalizePlate(plateNumber);
		this.vehicleClass = vehicleClass;
		this.make = make;
		this.model = model;
		this.color = color;
		this.modelYear = modelYear;
		this.seats = seats;
		this.status = VehicleStatus.ACTIVE;
	}

	/** Uppercase with single spaces, e.g. {@code "pp  2ab-1234"} → {@code "PP 2AB-1234"}. */
	public static String normalizePlate(String plate) {
		return plate.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}

	public UUID getId() {
		return id;
	}

	public String getPlateNumber() {
		return plateNumber;
	}

	public VehicleClass getVehicleClass() {
		return vehicleClass;
	}

	public String getMake() {
		return make;
	}

	public String getModel() {
		return model;
	}

	public String getColor() {
		return color;
	}

	public Short getModelYear() {
		return modelYear;
	}

	public short getSeats() {
		return seats;
	}

	public VehicleStatus getStatus() {
		return status;
	}
}

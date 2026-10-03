package com.mobility.core.driver.document;

import java.util.EnumSet;
import java.util.Set;

public enum DocumentType {

	NATIONAL_ID(false, true), DRIVING_LICENSE(false, true), PROFILE_PHOTO(false, false),
	VEHICLE_REGISTRATION(true, true), VEHICLE_INSURANCE(true, true);

	/** Required from every driver before training. */
	public static final Set<DocumentType> DRIVER_REQUIRED = EnumSet.of(NATIONAL_ID, DRIVING_LICENSE, PROFILE_PHOTO);

	/** Required for the assigned vehicle before approval. */
	public static final Set<DocumentType> VEHICLE_REQUIRED = EnumSet.of(VEHICLE_REGISTRATION, VEHICLE_INSURANCE);

	private final boolean vehicleDocument;

	private final boolean requiresExpiry;

	DocumentType(boolean vehicleDocument, boolean requiresExpiry) {
		this.vehicleDocument = vehicleDocument;
		this.requiresExpiry = requiresExpiry;
	}

	public boolean isVehicleDocument() {
		return vehicleDocument;
	}

	public boolean requiresExpiry() {
		return requiresExpiry;
	}
}

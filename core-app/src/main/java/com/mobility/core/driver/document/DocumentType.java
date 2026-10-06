package com.mobility.core.driver.document;

import java.util.EnumSet;
import java.util.Set;

public enum DocumentType {

	NATIONAL_ID(false, true, true), DRIVING_LICENSE(false, true, true), PROFILE_PHOTO(false, false, false),
	VEHICLE_REGISTRATION(true, true, true);

	/** Required from every driver before training. */
	public static final Set<DocumentType> DRIVER_REQUIRED = EnumSet.of(NATIONAL_ID, DRIVING_LICENSE, PROFILE_PHOTO);

	/** Required for the assigned vehicle before approval. */
	public static final Set<DocumentType> VEHICLE_REQUIRED = EnumSet.of(VEHICLE_REGISTRATION);

	private final boolean vehicleDocument;

	private final boolean requiresExpiry;

	private final boolean twoSided;

	DocumentType(boolean vehicleDocument, boolean requiresExpiry, boolean twoSided) {
		this.vehicleDocument = vehicleDocument;
		this.requiresExpiry = requiresExpiry;
		this.twoSided = twoSided;
	}

	public boolean isVehicleDocument() {
		return vehicleDocument;
	}

	public boolean requiresExpiry() {
		return requiresExpiry;
	}

	/** A card with information on both sides: needs a back photo unless the front is a PDF scan of both. */
	public boolean isTwoSided() {
		return twoSided;
	}
}

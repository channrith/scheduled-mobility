package com.mobility.core.pricing.zone;

/** A position in WGS 84 degrees. */
public record LatLng(double latitude, double longitude) {

	static final double MIN_LAT = 9.5, MAX_LAT = 15.0, MIN_LNG = 102.0, MAX_LNG = 108.0;

	/** Roughly Cambodia (the same box the place module uses). */
	public boolean inCambodia() {
		return latitude >= MIN_LAT && latitude <= MAX_LAT && longitude >= MIN_LNG && longitude <= MAX_LNG;
	}
}

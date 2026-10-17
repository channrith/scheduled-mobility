package com.mobility.core.pricing.zone;

/** Validated zone content; {@code geoJson} is a closed GeoJSON Polygon. */
record ZoneDetails(String city, ZoneType type, String nameKm, String nameEn, String geoJson, Long surchargeMinor,
		String surchargeCurrency, boolean active) {
}

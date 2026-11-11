package com.mobility.core.pricing.routing;

/** Road distance and driving time between two points. */
public record Route(long distanceMeters, long durationSeconds, RoutingSource source, Geometry geometry) {

    public Route(long distanceMeters, long durationSeconds, RoutingSource source) {
        this(distanceMeters, durationSeconds, source, null);
    }

    public record Geometry(String encoding, int precision, String encodedPolyline) {
        public Geometry {
            if (!"polyline".equals(encoding) || precision != 5 || encodedPolyline == null
                    || encodedPolyline.isBlank() || encodedPolyline.length() > 100000)
                throw new IllegalArgumentException("Invalid route geometry");
        }
    }

	public enum RoutingSource {
		OSRM, STRAIGHT_LINE
	}
}

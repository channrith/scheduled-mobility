package com.mobility.core.pricing.routing;

/** Road distance and driving time between two points. */
public record Route(long distanceMeters, long durationSeconds, RoutingSource source) {

	public enum RoutingSource {
		OSRM, STRAIGHT_LINE
	}
}

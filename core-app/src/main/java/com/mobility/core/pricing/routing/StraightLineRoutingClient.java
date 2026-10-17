package com.mobility.core.pricing.routing;

import java.math.BigDecimal;

import com.mobility.core.pricing.routing.Route.RoutingSource;
import com.mobility.core.pricing.zone.LatLng;

/**
 * Estimate without a routing server: great-circle distance × a detour factor, at an average speed. For tests
 * and local runs only (fares are binding, so production uses OSRM).
 */
public class StraightLineRoutingClient implements RoutingClient {

	static final double EARTH_RADIUS_M = 6_371_008.8;

	private final double detourFactor;

	private final double metersPerSecond;

	public StraightLineRoutingClient(BigDecimal detourFactor, BigDecimal averageSpeedKmh) {
		this.detourFactor = detourFactor.doubleValue();
		this.metersPerSecond = averageSpeedKmh.doubleValue() * 1000 / 3600;
	}

	@Override
	public Route route(LatLng from, LatLng to) {
		long meters = Math.round(haversine(from, to) * detourFactor);
		return new Route(meters, Math.round(meters / metersPerSecond), RoutingSource.STRAIGHT_LINE);
	}

	static double haversine(LatLng a, LatLng b) {
		double lat1 = Math.toRadians(a.latitude());
		double lat2 = Math.toRadians(b.latitude());
		double dLat = lat2 - lat1;
		double dLng = Math.toRadians(b.longitude() - a.longitude());
		double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
		return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
	}
}

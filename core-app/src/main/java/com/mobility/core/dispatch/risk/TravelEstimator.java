package com.mobility.core.dispatch.risk;

import java.time.Duration;

import com.mobility.core.dispatch.DispatchProperties;
import org.springframework.stereotype.Component;

/**
 * Driving time between two points without a routing call: great-circle distance × a detour factor at an average city
 * speed. Used every 30 s for every trip near pickup, and by the planner, so it must be cheap; real road ETAs come
 * with our own OSRM server.
 */
@Component
public class TravelEstimator {

	static final double EARTH_RADIUS_M = 6_371_008.8;

	private final double detourFactor;

	private final double metersPerSecond;

	@org.springframework.beans.factory.annotation.Autowired
	public TravelEstimator(DispatchProperties properties) {
		this(properties.eta().detourFactor().doubleValue(), properties.eta().speedKmh().doubleValue());
	}

	TravelEstimator(double detourFactor, double speedKmh) {
		this.detourFactor = detourFactor;
		this.metersPerSecond = speedKmh * 1000 / 3600;
	}

	public Duration between(double fromLat, double fromLng, double toLat, double toLng) {
		double meters = haversine(fromLat, fromLng, toLat, toLng) * detourFactor;
		return Duration.ofSeconds(Math.round(meters / metersPerSecond));
	}

	static double haversine(double lat1, double lng1, double lat2, double lng2) {
		double p1 = Math.toRadians(lat1);
		double p2 = Math.toRadians(lat2);
		double dLat = p2 - p1;
		double dLng = Math.toRadians(lng2 - lng1);
		double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
		return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
	}
}

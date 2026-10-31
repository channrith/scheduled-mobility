package com.mobility.core.pricing.routing;

import com.mobility.core.pricing.zone.LatLng;

/** Distance and duration of the drive between two points. */
public interface RoutingClient {

	/**
	 * @throws RoutingException {@code NO_ROUTE} when there is no road between the points, {@code UNAVAILABLE}
	 * when the routing service cannot answer (timeout, error)
	 */
	Route route(LatLng from, LatLng to);
}

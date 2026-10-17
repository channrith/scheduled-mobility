package com.mobility.core.pricing.quote;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.mobility.core.pricing.zone.ZoneService.ZoneHit;
import com.mobility.core.pricing.zone.ZoneType;

/**
 * What the zones at both ends of a ride mean for its price. Pure, so it is unit tested without a database.
 *
 * @param restrictedEnd "pickup" or "dropoff" if that end is in a RESTRICTED zone
 * @param city the city whose prices apply: of the pickup's CITY/AIRPORT zone, else of the drop-off's
 * @param airport the AIRPORT zone (charged once even if both ends are in one): the pickup's, else the drop-off's
 */
record ServiceArea(String restrictedEnd, Optional<String> city, Optional<ZoneHit> airport) {

	static ServiceArea of(List<ZoneHit> pickup, List<ZoneHit> dropoff) {
		String restricted = has(pickup, ZoneType.RESTRICTED) ? "pickup" : has(dropoff, ZoneType.RESTRICTED) ? "dropoff" : null;
		Optional<String> city = Stream.concat(served(pickup), served(dropoff)).map(ZoneHit::city).findFirst();
		Optional<ZoneHit> airport = Stream.concat(pickup.stream(), dropoff.stream())
			.filter(z -> z.type() == ZoneType.AIRPORT)
			.filter(z -> city.isEmpty() || z.city().equals(city.get()))
			.findFirst();
		return new ServiceArea(restricted, city, airport);
	}

	private static boolean has(List<ZoneHit> zones, ZoneType type) {
		return zones.stream().anyMatch(z -> z.type() == type);
	}

	/** CITY before AIRPORT, so a city zone decides the city where both overlap. */
	private static Stream<ZoneHit> served(List<ZoneHit> zones) {
		return Stream.concat(zones.stream().filter(z -> z.type() == ZoneType.CITY),
				zones.stream().filter(z -> z.type() == ZoneType.AIRPORT));
	}
}

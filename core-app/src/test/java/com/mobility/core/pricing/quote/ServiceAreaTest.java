package com.mobility.core.pricing.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.mobility.core.pricing.zone.ZoneService.ZoneHit;
import com.mobility.core.pricing.zone.ZoneType;
import org.junit.jupiter.api.Test;

/** Airport zone and service area at the two ends of a ride. */
class ServiceAreaTest {

	static ZoneHit zone(String city, ZoneType type) {
		return new ZoneHit(UUID.randomUUID(), city, type, null, type.name(), type == ZoneType.AIRPORT ? 1000L : null,
				type == ZoneType.AIRPORT ? "USD" : null);
	}

	static final ZoneHit CITY = zone("PHNOM_PENH", ZoneType.CITY);

	static final ZoneHit AIRPORT = zone("PHNOM_PENH", ZoneType.AIRPORT);

	@Test
	void cityToAirportPaysTheAirportOnce() {
		ServiceArea area = ServiceArea.of(List.of(CITY), List.of(AIRPORT));

		assertThat(area.city()).contains("PHNOM_PENH");
		assertThat(area.airport()).contains(AIRPORT);
		assertThat(area.restrictedEnd()).isNull();
	}

	@Test
	void airportToAirportIsStillOneSurcharge() {
		assertThat(ServiceArea.of(List.of(AIRPORT), List.of(AIRPORT)).airport()).contains(AIRPORT);
	}

	@Test
	void noAirportWithinTheCity() {
		ServiceArea area = ServiceArea.of(List.of(CITY), List.of(CITY));

		assertThat(area.airport()).isEmpty();
		assertThat(area.city()).contains("PHNOM_PENH");
	}

	@Test
	void oneEndInTheServiceAreaIsEnoughOtherwiseNoCity() {
		assertThat(ServiceArea.of(List.of(), List.of(CITY)).city()).contains("PHNOM_PENH");
		assertThat(ServiceArea.of(List.of(CITY), List.of()).city()).contains("PHNOM_PENH");
		assertThat(ServiceArea.of(List.of(), List.of(AIRPORT)).city()).contains("PHNOM_PENH");
		assertThat(ServiceArea.of(List.of(), List.of()).city()).isEmpty();
	}

	@Test
	void restrictedAtEitherEnd() {
		ZoneHit restricted = zone("PHNOM_PENH", ZoneType.RESTRICTED);

		assertThat(ServiceArea.of(List.of(CITY, restricted), List.of(CITY)).restrictedEnd()).isEqualTo("pickup");
		assertThat(ServiceArea.of(List.of(CITY), List.of(restricted)).restrictedEnd()).isEqualTo("dropoff");
	}

	@Test
	void thePickupsCityDecidesAndAnotherCitysAirportIsNotCharged() {
		ZoneHit siemReap = zone("SIEM_REAP", ZoneType.CITY);
		ZoneHit siemReapAirport = zone("SIEM_REAP", ZoneType.AIRPORT);

		ServiceArea area = ServiceArea.of(List.of(CITY), List.of(siemReap, siemReapAirport));
		assertThat(area.city()).contains("PHNOM_PENH");
		assertThat(area.airport()).isEmpty();
		// A city zone wins over an overlapping airport zone of another city at the same end.
		assertThat(ServiceArea.of(List.of(AIRPORT, siemReap), List.of()).city()).contains("SIEM_REAP");
	}
}

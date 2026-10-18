package com.mobility.core.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;

/** Read-only access to stored fare quotes, for booking. A quote never changes. */
public interface FareQuotes {

	/**
	 * Money in minor units of {@code currency}.
	 *
	 * @param airport the drop-off or pickup is in an AIRPORT zone
	 * @param serviceFeeMinor corporate service fee shown with the quote (invoiced on top); null without a company
	 */
	record QuoteSummary(UUID quoteId, UUID requestedBy, UUID corporateId, String city, VehicleClass vehicleClass,
			UUID pickupPlaceId, double pickupLat, double pickupLng, UUID dropoffPlaceId, double dropoffLat, double dropoffLng,
			Instant pickupAt, long durationSeconds, long distanceMeters, String currency, long totalMinor,
			String equivalentCurrency, long equivalentMinor, Long serviceFeeMinor, boolean airport, Instant eta,
			Instant expiresAt) {
	}

	Optional<QuoteSummary> find(UUID quoteId);
}

package com.mobility.core.pricing.quote;

import java.util.Optional;
import java.util.UUID;

import com.mobility.core.pricing.FareQuotes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FareQuotesService implements FareQuotes {

	private final FareQuoteRepository quotes;

	FareQuotesService(FareQuoteRepository quotes) {
		this.quotes = quotes;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<QuoteSummary> find(UUID quoteId) {
		return quotes.findById(quoteId)
			.map(q -> new QuoteSummary(q.getId(), q.getRequestedBy(), q.getCorporateId(), q.getCity(), q.getVehicleClass(),
					q.getPickupPlaceId(), q.getPickupLat(), q.getPickupLng(), q.getDropoffPlaceId(), q.getDropoffLat(),
					q.getDropoffLng(), q.getPickupAt(), q.getDurationSeconds(), q.getDistanceMeters(), q.getCurrency(),
					q.getTotalMinor(), q.getEquivalentCurrency(), q.getEquivalentMinor(), q.getServiceFeeMinor(),
					q.getAirportZoneId() != null, q.getEta(), q.getExpiresAt()));
	}
}

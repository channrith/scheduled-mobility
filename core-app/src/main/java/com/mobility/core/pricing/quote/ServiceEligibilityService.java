package com.mobility.core.pricing.quote;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.pricing.ServiceEligibility;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class ServiceEligibilityService implements ServiceEligibility {
    private final QuoteService quotes;
    private final MeteredPricingService meteredPricing;

    ServiceEligibilityService(QuoteService quotes, MeteredPricingService meteredPricing) {
        this.quotes = quotes;
        this.meteredPricing = meteredPricing;
    }

    @Transactional(readOnly = true)
    public Coverage check(
            EligibilityLocation pickup, EligibilityLocation dropoff, VehicleClass vehicleClass,
            Instant at, boolean airport, boolean metered) {
        // Use the authoritative pickup-only meter preparation (reads only), including airport rules.
        if (metered || airport) {
            meteredPricing.prepare(
                    pickup.placeId(), pickup.latitude(), pickup.longitude(), vehicleClass, at, airport);
        }
        var from = quotes.resolve(
                new QuoteService.Location(pickup.placeId(), pickup.latitude(), pickup.longitude()), "pickup");
        var to = dropoff == null ? null : quotes.resolve(
                new QuoteService.Location(dropoff.placeId(), dropoff.latitude(), dropoff.longitude()), "dropoff");
        var eligible = quotes.eligibility(from, to, vehicleClass, null, at);
        if (airport && eligible.area().airport().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.not-an-airport-trip");
        }
        return new Coverage(
                eligible.city(), eligible.pickupZones().stream().map(z -> z.type().name()).distinct().toList(),
                eligible.dropoffZones().stream().map(z -> z.type().name()).distinct().toList(),
                eligible.area().airport().map(z -> z.id()).orElse(null));
    }
}

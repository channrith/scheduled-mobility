package com.mobility.core.pricing.quote;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.place.PlaceDirectory;
import com.mobility.core.pricing.MeteredPricing;
import com.mobility.core.pricing.rule.PricingRuleService;
import com.mobility.core.pricing.settings.PricingSettings;
import com.mobility.core.pricing.zone.LatLng;
import com.mobility.core.pricing.zone.ZoneService;
import com.mobility.core.shared.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class MeteredPricingService implements MeteredPricing {
    private final PlaceDirectory places;
    private final ZoneService zones;
    private final PricingRuleService rules;
    private final PricingSettings settings;
    private final SurgeProvider surge;

    MeteredPricingService(
            PlaceDirectory places,
            ZoneService zones,
            PricingRuleService rules,
            PricingSettings settings,
            SurgeProvider surge) {
        this.places = places;
        this.zones = zones;
        this.rules = rules;
        this.settings = settings;
        this.surge = surge;
    }

    @Transactional(readOnly = true)
    public Terms prepare(
            UUID placeId,
            Double latitude,
            Double longitude,
            VehicleClass vehicleClass,
            Instant at,
            boolean airport) {
        if (vehicleClass == null) throw invalid("vehicleClass");
        if (placeId != null) {
            var place = places.get(placeId).orElseThrow(() -> invalid("pickup.placeId"));
            placeId = place.id();
            latitude = place.latitude();
            longitude = place.longitude();
        }
        if (latitude == null
                || longitude == null
                || !Double.isFinite(latitude)
                || !Double.isFinite(longitude)) throw invalid("pickup");
        var position = new LatLng(latitude, longitude);
        if (!position.inCambodia())
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "pricing.outside-service-area");
        var area = ServiceArea.of(zones.zonesAt(position), List.of());
        if (area.restrictedEnd() != null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pricing.restricted-zone");
        if (airport && area.airport().isEmpty())
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.not-an-airport-trip");
        var city =
                area.city()
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_CONTENT,
                                                "pricing.outside-service-area"));
        var rule =
                rules.ruleFor(city, vehicleClass, null, at)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_CONTENT,
                                                "pricing.no-rule"));
        var zone = area.airport().orElse(null);
        return new Terms(
                rule.getId(),
                placeId,
                latitude,
                longitude,
                city,
                vehicleClass,
                zone != null,
                rule.getCurrency(),
                rule.getBaseMinor(),
                rule.getPerKmMinor(),
                rule.getPerMinMinor(),
                rule.getMinFareMinor(),
                rule.getFuelIndexFactor(),
                rule.getMaxSurge(),
                settings.fuelIndex(),
                surge.surge(city, vehicleClass, at),
                zone == null ? null : zone.surchargeMinor(),
                zone == null ? null : zone.surchargeCurrency(),
                settings.usdToKhr());
    }

    public MeteredFare finish(Terms t, long meters, long seconds) {
        if (meters < 0 || seconds < 0) throw invalid("meter");
        var fare =
                FareCalculator.calculate(
                        new FareCalculator.Inputs(
                                new FareCalculator.Tariff(
                                        t.currency(),
                                        t.baseMinor(),
                                        t.perKmMinor(),
                                        t.perMinMinor(),
                                        t.minimumMinor(),
                                        t.fuelFactor(),
                                        t.maxSurge()),
                                meters,
                                seconds,
                                t.fuelIndex(),
                                t.surge(),
                                t.airportMinor() == null
                                        ? null
                                        : new FareCalculator.Money(
                                                t.airportMinor(), t.airportCurrency()),
                                t.usdToKhr(),
                                null));
        return new MeteredFare(
                fare.currency(),
                fare.totalMinor(),
                fare.equivalentCurrency(),
                fare.equivalentMinor());
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "booking.invalid-field")
                .withProperty("field", field);
    }
}

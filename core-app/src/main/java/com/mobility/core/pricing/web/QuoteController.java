package com.mobility.core.pricing.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.pricing.quote.FareCalculator;
import com.mobility.core.pricing.quote.FareCalculator.LineType;
import com.mobility.core.pricing.quote.FareQuote;
import com.mobility.core.pricing.quote.QuoteService;
import com.mobility.core.pricing.quote.QuoteService.Location;
import com.mobility.core.pricing.quote.QuoteService.QuoteRequest;
import com.mobility.core.pricing.routing.Route.RoutingSource;
import com.mobility.core.pricing.rule.PricingRuleService;
import com.mobility.core.shared.openapi.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quotes")
@Tag(name = "Quotes", description = "Fixed fares: a quote is the final fare of the ride")
class QuoteController {

	private final QuoteService quotes;

	private final PricingRuleService rules;

	QuoteController(QuoteService quotes, PricingRuleService rules) {
		this.quotes = quotes;
		this.rules = rules;
	}

	/** {@code placeId} (a landmark) or {@code latitude} + {@code longitude}. */
	record LocationRequest(UUID placeId, Double latitude, Double longitude) {
	}

	record CreateQuoteRequest(@NotNull @Valid LocationRequest pickup, @NotNull @Valid LocationRequest dropoff,
			@NotNull VehicleClass vehicleClass, Instant pickupAt, UUID corporateId) {
	}

	record LocationView(UUID placeId, double latitude, double longitude) {
	}

	/** @param equivalentMinor the line in the equivalent currency, for information */
	record QuoteLineView(LineType type, long amountMinor, long equivalentMinor) {
	}

	record MoneyView(String currency, long amountMinor) {
	}

	/** Invoiced to the corporate client on top of the fare; not part of {@code totalMinor}. */
	record ServiceFeeView(BigDecimal pct, long amountMinor, long equivalentMinor) {
	}

	/**
	 * Money in minor units (USD cents, KHR riel) of {@code currency}; {@code equivalent} is the total in the other
	 * currency at {@code usdKhrRate}.
	 *
	 * @param eta arrival at the drop-off: pickup time + driving time
	 * @param expiresAt book before this time to keep the fare
	 * @param corporateOverride the company's own price was used (not the city price)
	 */
	record QuoteView(UUID id, String city, VehicleClass vehicleClass, UUID corporateId, LocationView pickup,
			LocationView dropoff, Instant pickupAt, String currency, long totalMinor, MoneyView equivalent,
			BigDecimal usdKhrRate, List<QuoteLineView> breakdown, ServiceFeeView serviceFee, long distanceMeters,
			long durationSeconds, RoutingSource routingSource, Instant eta, Instant expiresAt, UUID ruleId,
			boolean corporateOverride, BigDecimal fuelIndex, BigDecimal surge, UUID airportZoneId, Instant createdAt) {
	}

	@Operation(summary = "Get a fare quote",
			description = "Pickup and drop-off as a landmark (`placeId`) or a map position. One end must be in our service "
					+ "area; the price is the version in force at `pickupAt` (default now): with `corporateId`, the "
					+ "company's override if it has one, else the city price. The quote is stored and never changes; it can "
					+ "be booked until `expiresAt`. `corporateId` needs staff or that company's admin/booker.")
	@ProblemResponse(status = 422, description = "`pricing.outside-service-area`, `pricing.restricted-zone` (`end`), "
			+ "`pricing.no-rule`, `pricing.no-route`")
	@ProblemResponse(status = 503, description = "`pricing.routing-unavailable`: no quote without a road route")
	@PostMapping
	ResponseEntity<QuoteView> create(@Valid @RequestBody CreateQuoteRequest request) {
		FareQuote quote = quotes.create(new QuoteRequest(location(request.pickup()), location(request.dropoff()),
				request.vehicleClass(), request.pickupAt(), request.corporateId()));
		return ResponseEntity.created(URI.create("/api/v1/quotes/" + quote.getId())).body(view(quote));
	}

	@Operation(summary = "A quote", description = "Your own quotes; staff see all.")
	@GetMapping("/{quoteId}")
	QuoteView get(@PathVariable UUID quoteId) {
		return view(quotes.get(quoteId));
	}

	private static Location location(LocationRequest r) {
		return new Location(r.placeId(), r.latitude(), r.longitude());
	}

	private QuoteView view(FareQuote q) {
		boolean override = rules.get(q.getRuleId()).getCorporateId() != null;
		ServiceFeeView fee = q.getServiceFeeMinor() == null ? null
				: new ServiceFeeView(q.getServiceFeePct(), q.getServiceFeeMinor(), FareCalculator.equivalent(q.getServiceFeeMinor(), q.getCurrency(), q.getUsdKhrRate()));
		return new QuoteView(q.getId(), q.getCity(), q.getVehicleClass(), q.getCorporateId(),
				new LocationView(q.getPickupPlaceId(), q.getPickupLat(), q.getPickupLng()),
				new LocationView(q.getDropoffPlaceId(), q.getDropoffLat(), q.getDropoffLng()), q.getPickupAt(),
				q.getCurrency(), q.getTotalMinor(), new MoneyView(q.getEquivalentCurrency(), q.getEquivalentMinor()),
				q.getUsdKhrRate(), q.getBreakdown().stream().map(l -> new QuoteLineView(l.type(), l.amountMinor(), l.equivalentMinor())).toList(),
				fee, q.getDistanceMeters(), q.getDurationSeconds(), q.getRoutingSource(), q.getEta(), q.getExpiresAt(),
				q.getRuleId(), override, q.getFuelIndex(), q.getSurge(), q.getAirportZoneId(), q.getCreatedAt());
	}
}

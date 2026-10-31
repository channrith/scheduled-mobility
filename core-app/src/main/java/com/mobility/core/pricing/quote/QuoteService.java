package com.mobility.core.pricing.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.mobility.core.corporate.AccountStatus;
import com.mobility.core.corporate.CorporateDirectory;
import com.mobility.core.corporate.CorporateDirectory.CorporateSummary;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.Authz;
import com.mobility.core.identity.CurrentUser;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.place.PlaceDirectory;
import com.mobility.core.place.PlaceDirectory.PlaceSummary;
import com.mobility.core.pricing.quote.FareCalculator.Fare;
import com.mobility.core.pricing.quote.FareCalculator.Inputs;
import com.mobility.core.pricing.quote.FareCalculator.Money;
import com.mobility.core.pricing.quote.FareCalculator.Tariff;
import com.mobility.core.pricing.routing.Route;
import com.mobility.core.pricing.routing.RoutingClient;
import com.mobility.core.pricing.routing.RoutingException;
import com.mobility.core.pricing.rule.PricingRule;
import com.mobility.core.pricing.rule.PricingRuleService;
import com.mobility.core.pricing.settings.PricingProperties;
import com.mobility.core.pricing.settings.PricingSettings;
import com.mobility.core.pricing.zone.LatLng;
import com.mobility.core.pricing.zone.ZoneService;
import com.mobility.core.pricing.zone.ZoneService.ZoneHit;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quotes are final: the fare is fixed at quote time and the row is never changed. */
@Service
public class QuoteService {

	/** One end of a ride: a landmark ({@code placeId}) or a map position. */
	public record Location(UUID placeId, Double latitude, Double longitude) {
	}

	public record QuoteRequest(Location pickup, Location dropoff, VehicleClass vehicleClass, Instant pickupAt,
			UUID corporateId) {
	}

	/** A small allowance for a pickup time chosen a moment ago. */
	static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

	static final Duration MAX_AHEAD = Duration.ofDays(366);

	private final FareQuoteRepository quotes;

	private final PricingRuleService rules;

	private final ZoneService zones;

	private final PricingSettings settings;

	private final RoutingClient routing;

	private final SurgeProvider surge;

	private final PlaceDirectory places;

	private final CorporateDirectory corporates;

	private final CurrentUserProvider currentUser;

	private final Authz authz;

	private final PricingProperties properties;

	private final Clock clock;

	QuoteService(FareQuoteRepository quotes, PricingRuleService rules, ZoneService zones, PricingSettings settings,
			RoutingClient routing, SurgeProvider surge, PlaceDirectory places, CorporateDirectory corporates,
			CurrentUserProvider currentUser, Authz authz, PricingProperties properties, Clock clock) {
		this.quotes = quotes;
		this.rules = rules;
		this.zones = zones;
		this.settings = settings;
		this.routing = routing;
		this.surge = surge;
		this.places = places;
		this.corporates = corporates;
		this.currentUser = currentUser;
		this.authz = authz;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public FareQuote create(QuoteRequest request) {
		CurrentUser user = currentUser.require();
		Instant now = StoredTime.of(clock.instant());
		if (request.vehicleClass() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.invalid-field").withProperty("field", "vehicleClass");
		}
		Instant pickupAt = request.pickupAt() == null ? now : StoredTime.of(request.pickupAt());
		if (pickupAt.isBefore(now.minus(PAST_TOLERANCE)) || pickupAt.isAfter(now.plus(MAX_AHEAD))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.pickup-time-invalid");
		}
		CorporateSummary corporate = corporate(request.corporateId());
		Resolved pickup = resolve(request.pickup(), "pickup");
		Resolved dropoff = resolve(request.dropoff(), "dropoff");

		ServiceArea area = ServiceArea.of(zones.zonesAt(pickup.position()), zones.zonesAt(dropoff.position()));
		if (area.restrictedEnd() != null) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pricing.restricted-zone").withProperty("end", area.restrictedEnd());
		}
		String city = area.city()
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pricing.outside-service-area"));
		PricingRule rule = rules.ruleFor(city, request.vehicleClass(), request.corporateId(), pickupAt)
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pricing.no-rule"));

		Route route = route(pickup.position(), dropoff.position());
		BigDecimal fuelIndex = settings.fuelIndex();
		BigDecimal rate = settings.usdToKhr();
		ZoneHit airport = area.airport().orElse(null);
		Money surcharge = airport == null ? null : new Money(airport.surchargeMinor(), airport.surchargeCurrency());
		BigDecimal feePct = corporate == null ? null : corporate.serviceFeePct();
		Fare fare = FareCalculator.calculate(new Inputs(
				new Tariff(rule.getCurrency(), rule.getBaseMinor(), rule.getPerKmMinor(), rule.getPerMinMinor(),
						rule.getMinFareMinor(), rule.getFuelIndexFactor(), rule.getMaxSurge()),
				route.distanceMeters(), route.durationSeconds(), fuelIndex,
				surge.surge(city, request.vehicleClass(), pickupAt), surcharge, rate, feePct));

		return quotes.save(new FareQuote(new FareQuote.Content(user.userId(), request.corporateId(), city,
				request.vehicleClass(), pickup.placeId(), pickup.position().latitude(), pickup.position().longitude(),
				dropoff.placeId(), dropoff.position().latitude(), dropoff.position().longitude(), pickupAt, rule.getId(),
				route.distanceMeters(), route.durationSeconds(), route.source(), fuelIndex, rate,
				airport == null ? null : airport.id(), fare, feePct, pickupAt.plusSeconds(route.durationSeconds()),
				now.plus(properties.quoteTtl()), now)));
	}

	/** The requester's own quote, or any quote for staff; otherwise "not found". */
	@Transactional(readOnly = true)
	public FareQuote get(UUID id) {
		CurrentUser user = currentUser.require();
		return quotes.findById(id)
			.filter(q -> user.isStaff() || q.getRequestedBy().equals(user.userId()))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pricing.quote-not-found"));
	}

	private CorporateSummary corporate(UUID corporateId) {
		if (corporateId == null) {
			return null;
		}
		if (!authz.canAccessCorporate(corporateId)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "auth.forbidden");
		}
		CorporateSummary corporate = corporates.find(corporateId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pricing.corporate-not-found"));
		if (corporate.status() != AccountStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "pricing.corporate-inactive");
		}
		return corporate;
	}

	private record Resolved(UUID placeId, LatLng position) {
	}

	/** A landmark wins over coordinates; merged landmarks resolve to the place they were merged into. */
	private Resolved resolve(Location location, String end) {
		if (location != null && location.placeId() != null) {
			PlaceSummary place = places.get(location.placeId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pricing.place-not-found").withProperty("end", end));
			return new Resolved(place.id(), new LatLng(place.latitude(), place.longitude()));
		}
		if (location == null || location.latitude() == null || location.longitude() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.location-required").withProperty("end", end);
		}
		LatLng position = new LatLng(location.latitude(), location.longitude());
		if (!position.inCambodia()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pricing.outside-service-area").withProperty("end", end);
		}
		return new Resolved(null, position);
	}

	private Route route(LatLng from, LatLng to) {
		try {
			return routing.route(from, to);
		}
		catch (RoutingException e) {
			// Fares are binding: no quote from a guess when the routing service is down.
			throw e.kind() == RoutingException.Kind.NO_ROUTE
					? new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "pricing.no-route")
					: new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "pricing.routing-unavailable");
		}
	}
}

package com.mobility.core.pricing.rule;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.corporate.CorporateDirectory;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.pricing.settings.PricingProperties;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Price lines and their versions. Versions are only ever added. */
@Service
public class PricingRuleService {

	/** A price line: a city and vehicle class, optionally one corporate client's override. */
	public record LineKey(String city, VehicleClass vehicleClass, UUID corporateId) {
	}

	/** Input from the API; validated here. {@code validFrom} null = now. */
	public record RuleInput(String city, VehicleClass vehicleClass, UUID corporateId, String currency, Long baseMinor,
			Long perKmMinor, Long perMinMinor, Long minFareMinor, BigDecimal fuelIndexFactor, BigDecimal maxSurge,
			Instant validFrom, Instant validTo) {
	}

	public record LineSummary(LineKey key, PricingRule current, List<PricingRule> upcoming, PricingRule latest) {
	}

	/** A small allowance for clock differences between the console and the server. */
	static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

	static final long MAX_AMOUNT_MINOR = 1_000_000_000L;

	private final PricingRuleRepository rules;

	private final PricingProperties properties;

	private final CorporateDirectory corporates;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	PricingRuleService(PricingRuleRepository rules, PricingProperties properties, CorporateDirectory corporates,
			AuditLog auditLog, CurrentUserProvider currentUser, Clock clock) {
		this.rules = rules;
		this.properties = properties;
		this.corporates = corporates;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Transactional
	public PricingRule createVersion(RuleInput input) {
		return save(validate(input));
	}

	/** Ends a line from {@code validFrom} (null = now) with a version that has no price. */
	@Transactional
	public PricingRule retire(LineKey key, Instant validFrom) {
		LineKey line = validateKey(key);
		PricingRule latest = rules.line(line.city(), line.vehicleClass(), line.corporateId()).stream().findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pricing.rule-not-found"));
		Instant from = validFrom(validFrom);
		return save(new RuleTerms(line.city(), line.vehicleClass(), line.corporateId(), latest.getCurrency(),
				latest.getBaseMinor(), latest.getPerKmMinor(), latest.getPerMinMinor(), latest.getMinFareMinor(),
				latest.getFuelIndexFactor(), latest.getMaxSurge(), from, null, false));
	}

	/**
	 * The rule for a ride at {@code at}: the company's override if one is in force, otherwise the city price.
	 */
	@Transactional(readOnly = true)
	public Optional<PricingRule> ruleFor(String city, VehicleClass vehicleClass, UUID corporateId, Instant at) {
		List<PricingRule> overrides = corporateId == null ? List.of() : rules.line(city, vehicleClass, corporateId);
		return select(overrides, rules.line(city, vehicleClass, null), at);
	}

	/** The override line's version in force, else the city line's: an ended or retired override falls back. */
	static Optional<PricingRule> select(List<PricingRule> overrideVersions, List<PricingRule> cityVersions, Instant at) {
		Optional<PricingRule> override = new RuleTimeline(overrideVersions).at(at);
		return override.isPresent() ? override : new RuleTimeline(cityVersions).at(at);
	}

	@Transactional(readOnly = true)
	public PricingRule get(UUID id) {
		return rules.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pricing.rule-not-found"));
	}

	/** Every line matching the filters (null = all), with its current and upcoming versions. */
	@Transactional(readOnly = true)
	public List<LineSummary> lines(String city, VehicleClass vehicleClass, UUID corporateId) {
		List<String> cities = city == null ? properties.cities() : List.of(city);
		List<VehicleClass> classes = vehicleClass == null ? List.copyOf(EnumSet.allOf(VehicleClass.class)) : List.of(vehicleClass);
		Map<LineKey, List<PricingRule>> byLine = new LinkedHashMap<>();
		for (PricingRule r : rules.lines(cities, classes, corporateId)) {
			byLine.computeIfAbsent(new LineKey(r.getCity(), r.getVehicleClass(), r.getCorporateId()), k -> new ArrayList<>()).add(r);
		}
		Instant now = clock.instant();
		List<LineSummary> result = new ArrayList<>();
		byLine.forEach((key, versions) -> {
			RuleTimeline timeline = new RuleTimeline(versions);
			PricingRule latest = versions.stream().max(RuleTimeline.ORDER).orElseThrow();
			result.add(new LineSummary(key, timeline.at(now).orElse(null), timeline.upcoming(now), latest));
		});
		result.sort(Comparator.comparing((LineSummary l) -> l.key().city()).thenComparing(l -> l.key().vehicleClass()));
		return result;
	}

	@Transactional(readOnly = true)
	public List<RuleTimeline.Entry> history(LineKey key) {
		LineKey line = validateKey(key);
		return new RuleTimeline(rules.line(line.city(), line.vehicleClass(), line.corporateId())).entries(clock.instant());
	}

	private PricingRule save(RuleTerms terms) {
		PricingRule rule = rules.save(new PricingRule(terms, currentUser.require().userId(), clock.instant()));
		Map<String, Object> details = new HashMap<>();
		details.put("city", terms.city());
		details.put("vehicleClass", terms.vehicleClass().name());
		details.put("active", terms.active());
		details.put("validFrom", terms.validFrom().toString());
		if (terms.corporateId() != null) {
			details.put("corporateId", terms.corporateId().toString());
		}
		auditLog.record(terms.active() ? "pricing.rule.version-created" : "pricing.rule.retired", "pricing_rule", rule.getId(),
				details);
		return rule;
	}

	private RuleTerms validate(RuleInput in) {
		LineKey key = validateKey(new LineKey(in.city(), in.vehicleClass(), in.corporateId()));
		String currency = in.currency() == null ? "" : in.currency().strip().toUpperCase(Locale.ROOT);
		if (!List.of("USD", "KHR").contains(currency)) {
			throw invalid("currency");
		}
		long base = amount(in.baseMinor(), "baseMinor");
		long perKm = amount(in.perKmMinor(), "perKmMinor");
		long perMin = amount(in.perMinMinor(), "perMinMinor");
		long minFare = amount(in.minFareMinor(), "minFareMinor");
		BigDecimal factor = decimal(in.fuelIndexFactor(), 3, BigDecimal.ZERO, BigDecimal.TWO, "fuelIndexFactor");
		BigDecimal maxSurge = decimal(in.maxSurge(), 2, BigDecimal.ONE, BigDecimal.valueOf(5), "maxSurge");
		Instant from = validFrom(in.validFrom());
		if (in.validTo() != null && !in.validTo().isAfter(from)) {
			throw invalid("validTo");
		}
		return new RuleTerms(key.city(), key.vehicleClass(), key.corporateId(), currency, base, perKm, perMin, minFare,
				factor, maxSurge, from, in.validTo(), true);
	}

	private LineKey validateKey(LineKey key) {
		String city = key.city() == null ? "" : key.city().strip().toUpperCase(Locale.ROOT);
		if (!properties.cities().contains(city)) {
			throw invalid("city");
		}
		if (key.vehicleClass() == null) {
			throw invalid("vehicleClass");
		}
		if (key.corporateId() != null && corporates.find(key.corporateId()).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "pricing.corporate-not-found");
		}
		return new LineKey(city, key.vehicleClass(), key.corporateId());
	}

	/** New versions start now or later: the past stays as it was quoted. */
	private Instant validFrom(Instant requested) {
		Instant now = clock.instant();
		if (requested == null) {
			return now;
		}
		if (requested.isBefore(now.minus(PAST_TOLERANCE))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "pricing.valid-from-past");
		}
		return requested.isBefore(now) ? now : requested;
	}

	private static long amount(Long value, String field) {
		if (value == null || value < 0 || value > MAX_AMOUNT_MINOR) {
			throw invalid(field);
		}
		return value;
	}

	private static BigDecimal decimal(BigDecimal value, int maxScale, BigDecimal min, BigDecimal max, String field) {
		if (value == null || value.stripTrailingZeros().scale() > maxScale || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
			throw invalid(field);
		}
		return value.setScale(maxScale);
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "pricing.invalid-field").withProperty("field", field);
	}
}

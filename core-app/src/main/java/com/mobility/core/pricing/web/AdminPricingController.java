package com.mobility.core.pricing.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.UserDirectory.UserSummary;
import com.mobility.core.pricing.rule.PricingRule;
import com.mobility.core.pricing.rule.PricingRuleService;
import com.mobility.core.pricing.rule.PricingRuleService.LineKey;
import com.mobility.core.pricing.rule.PricingRuleService.LineSummary;
import com.mobility.core.pricing.rule.PricingRuleService.RuleInput;
import com.mobility.core.pricing.settings.PricingProperties;
import com.mobility.core.pricing.settings.PricingSettings;
import com.mobility.core.pricing.settings.PricingSettings.CurrentValue;
import com.mobility.core.pricing.settings.Setting;
import com.mobility.core.pricing.settings.SettingChange;
import com.mobility.core.pricing.web.PersonViews.PersonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pricing")
@Tag(name = "Admin · Pricing", description = "Fare rules (versioned), fuel index and exchange rate")
class AdminPricingController {

	static final String VIEW = "hasAnyRole('ADMIN', 'DISPATCHER', 'SUPPORT')";

	private final PricingRuleService rules;

	private final PricingSettings settings;

	private final PricingProperties properties;

	private final PersonViews people;

	AdminPricingController(PricingRuleService rules, PricingSettings settings, PricingProperties properties,
			PersonViews people) {
		this.rules = rules;
		this.settings = settings;
		this.properties = properties;
		this.people = people;
	}

	/** Money in minor units of {@code currency}. */
	record RuleView(UUID id, String city, VehicleClass vehicleClass, UUID corporateId, String currency, long baseMinor,
			long perKmMinor, long perMinMinor, long minFareMinor, BigDecimal fuelIndexFactor, BigDecimal maxSurge,
			Instant validFrom, Instant validTo, boolean active, PersonView createdBy, Instant createdAt) {

		static RuleView of(PricingRule r, Map<UUID, UserSummary> names) {
			return r == null ? null
					: new RuleView(r.getId(), r.getCity(), r.getVehicleClass(), r.getCorporateId(), r.getCurrency(),
							r.getBaseMinor(), r.getPerKmMinor(), r.getPerMinMinor(), r.getMinFareMinor(),
							r.getFuelIndexFactor(), r.getMaxSurge(), r.getValidFrom(), r.getValidTo(), r.isActive(),
							PersonViews.person(r.getCreatedBy(), names), r.getCreatedAt());
		}
	}

	/**
	 * @param current in force now (null: no price now)
	 * @param upcoming versions that start later, oldest first
	 * @param latest the newest version (a retirement shows the line was ended)
	 */
	record PriceLineView(String city, VehicleClass vehicleClass, UUID corporateId, RuleView current, List<RuleView> upcoming,
			RuleView latest) {
	}

	/**
	 * @param effectiveUntil when it stops (or stopped) being in force; null = open-ended
	 * @param period UPCOMING, CURRENT, PAST, NEVER_IN_FORCE (replaced before it started) or RETIREMENT
	 */
	record HistoryEntryView(RuleView rule, Instant effectiveUntil, String period) {
	}

	record RuleRequest(@NotNull String city, @NotNull VehicleClass vehicleClass, UUID corporateId, @NotNull String currency,
			@NotNull Long baseMinor, @NotNull Long perKmMinor, @NotNull Long perMinMinor, @NotNull Long minFareMinor,
			@NotNull BigDecimal fuelIndexFactor, @NotNull BigDecimal maxSurge, Instant validFrom, Instant validTo) {

		RuleInput input() {
			return new RuleInput(city, vehicleClass, corporateId, currency, baseMinor, perKmMinor, perMinMinor, minFareMinor,
					fuelIndexFactor, maxSurge, validFrom, validTo);
		}
	}

	record RetireRequest(@NotNull String city, @NotNull VehicleClass vehicleClass, UUID corporateId, Instant validFrom) {
	}

	/** @param configuredDefault no admin has set it yet: the value comes from configuration */
	record SettingView(String setting, BigDecimal value, PersonView changedBy, Instant changedAt, boolean configuredDefault) {
	}

	record SettingsView(SettingView fuelIndex, SettingView usdKhrRate, List<SettingView> fuelIndexHistory,
			List<SettingView> usdKhrRateHistory) {
	}

	record SettingRequest(@NotNull BigDecimal value) {
	}

	@Operation(summary = "Cities", description = "City codes rules and zones can use.")
	@GetMapping("/cities")
	@PreAuthorize(VIEW)
	List<String> cities() {
		return properties.cities();
	}

	@Operation(summary = "Price lines",
			description = "One entry per city × vehicle class with the version in force now and upcoming ones. Without "
					+ "`corporateId`: city prices; with it: that company's overrides.")
	@GetMapping("/rules")
	@PreAuthorize(VIEW)
	List<PriceLineView> lines(@RequestParam(required = false) String city, @RequestParam(required = false) VehicleClass vehicleClass,
			@RequestParam(required = false) UUID corporateId) {
		List<LineSummary> lines = rules.lines(city, vehicleClass, corporateId);
		Map<UUID, UserSummary> names = people.lookup(lines.stream()
			.flatMap(l -> Stream.concat(Stream.of(l.current(), l.latest()), l.upcoming().stream()))
			.filter(r -> r != null)
			.map(PricingRule::getCreatedBy)
			.toList());
		return lines.stream()
			.map(l -> new PriceLineView(l.key().city(), l.key().vehicleClass(), l.key().corporateId(),
					RuleView.of(l.current(), names), l.upcoming().stream().map(r -> RuleView.of(r, names)).toList(),
					RuleView.of(l.latest(), names)))
			.toList();
	}

	@Operation(summary = "Version history of one line", description = "Newest first, with when each version is in force.")
	@GetMapping("/rules/history")
	@PreAuthorize(VIEW)
	List<HistoryEntryView> history(@RequestParam String city, @RequestParam VehicleClass vehicleClass,
			@RequestParam(required = false) UUID corporateId) {
		var entries = rules.history(new LineKey(city, vehicleClass, corporateId));
		Map<UUID, UserSummary> names = people.lookup(entries.stream().map(e -> e.rule().getCreatedBy()).toList());
		return entries.stream()
			.map(e -> new HistoryEntryView(RuleView.of(e.rule(), names), e.effectiveUntil(), e.period().name()))
			.toList();
	}

	@Operation(summary = "One rule version")
	@GetMapping("/rules/{ruleId}")
	@PreAuthorize(VIEW)
	RuleView rule(@PathVariable UUID ruleId) {
		PricingRule rule = rules.get(ruleId);
		return RuleView.of(rule, people.lookup(List.of(rule.getCreatedBy())));
	}

	@Operation(summary = "Create a rule version",
			description = "Adds a version to the line (city + vehicle class, optionally `corporateId`). It is in force from "
					+ "`validFrom` (default now; not in the past) until `validTo` or the next version. Existing versions are "
					+ "never changed. Money in minor units of `currency`; `fuelIndexFactor` 0–2, `maxSurge` 1–5.")
	@PostMapping("/rules")
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<RuleView> create(@Valid @RequestBody RuleRequest request) {
		PricingRule rule = rules.createVersion(request.input());
		return ResponseEntity.created(URI.create("/api/v1/admin/pricing/rules/" + rule.getId()))
			.body(RuleView.of(rule, people.lookup(List.of(rule.getCreatedBy()))));
	}

	@Operation(summary = "Retire a line",
			description = "From `validFrom` (default now) the line has no price: a company override then falls back to the "
					+ "city price; a city line can no longer be quoted.")
	@PostMapping("/rules/retire")
	@PreAuthorize("hasRole('ADMIN')")
	RuleView retire(@Valid @RequestBody RetireRequest request) {
		PricingRule rule = rules.retire(new LineKey(request.city(), request.vehicleClass(), request.corporateId()),
				request.validFrom());
		return RuleView.of(rule, people.lookup(List.of(rule.getCreatedBy())));
	}

	@Operation(summary = "Fuel index and exchange rate", description = "Current values and the last 50 changes of each.")
	@GetMapping("/settings")
	@PreAuthorize(VIEW)
	SettingsView settings() {
		CurrentValue fuel = settings.current(Setting.FUEL_INDEX);
		CurrentValue rate = settings.current(Setting.USD_KHR_RATE);
		List<SettingChange> fuelHistory = settings.history(Setting.FUEL_INDEX);
		List<SettingChange> rateHistory = settings.history(Setting.USD_KHR_RATE);
		List<UUID> ids = new ArrayList<>();
		ids.add(fuel.changedBy());
		ids.add(rate.changedBy());
		Stream.concat(fuelHistory.stream(), rateHistory.stream()).forEach(c -> ids.add(c.getChangedBy()));
		Map<UUID, UserSummary> names = people.lookup(ids);
		return new SettingsView(view(fuel, names), view(rate, names),
				fuelHistory.stream().map(c -> view(c, names)).toList(), rateHistory.stream().map(c -> view(c, names)).toList());
	}

	@Operation(summary = "Set the fuel index",
			description = "1.000 = the fuel price the rules were set at; 1.080 = 8 % higher. Range 0.5–3, three decimals. "
					+ "Applies to new quotes, weighted by each rule's `fuelIndexFactor`.")
	@PutMapping("/settings/fuel-index")
	@PreAuthorize("hasRole('ADMIN')")
	SettingView setFuelIndex(@Valid @RequestBody SettingRequest request) {
		CurrentValue value = settings.change(Setting.FUEL_INDEX, request.value());
		return view(value, people.lookup(List.of(value.changedBy())));
	}

	@Operation(summary = "Set the USD→KHR exchange rate", description = "Riel per US dollar, 1000–10000. Applies to new quotes.")
	@PutMapping("/settings/exchange-rate")
	@PreAuthorize("hasRole('ADMIN')")
	SettingView setExchangeRate(@Valid @RequestBody SettingRequest request) {
		CurrentValue value = settings.change(Setting.USD_KHR_RATE, request.value());
		return view(value, people.lookup(List.of(value.changedBy())));
	}

	private static SettingView view(CurrentValue v, Map<UUID, UserSummary> names) {
		return new SettingView(v.setting().name(), v.value(), PersonViews.person(v.changedBy(), names), v.changedAt(),
				v.configuredDefault());
	}

	private static SettingView view(SettingChange c, Map<UUID, UserSummary> names) {
		return new SettingView(c.getSetting().name(), c.getValue(), PersonViews.person(c.getChangedBy(), names),
				c.getChangedAt(), false);
	}
}

package com.mobility.core.pricing.settings;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param cities city codes that rules and zones may use
 * @param quoteTtl how long a quote can be booked
 * @param defaultFuelIndex until an admin sets one (1.000 = baseline)
 * @param defaultUsdKhrRate riel per US dollar until an admin sets one
 */
@Validated
@ConfigurationProperties("pricing")
public record PricingProperties(
		@DefaultValue("PHNOM_PENH") @NotEmpty List<String> cities,
		@DefaultValue("15m") Duration quoteTtl,
		@DefaultValue("1.000") BigDecimal defaultFuelIndex,
		@DefaultValue("4100") BigDecimal defaultUsdKhrRate,
		@DefaultValue @Valid Routing routing) {

	public enum RoutingProvider {
		/** Road distance and duration from an OSRM server. */
		OSRM,
		/** Straight-line distance × detour factor at an average speed: tests and local runs only. */
		STRAIGHT_LINE
	}

	public record Routing(
			@DefaultValue("STRAIGHT_LINE") RoutingProvider provider,
			URI osrmUrl,
			@DefaultValue("5s") Duration timeout,
			@DefaultValue("1.3") BigDecimal detourFactor,
			@DefaultValue("25") BigDecimal averageSpeedKmh) {
	}
}

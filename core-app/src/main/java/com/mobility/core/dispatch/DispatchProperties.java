package com.mobility.core.dispatch;

import java.math.BigDecimal;
import java.time.Duration;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Tunables of the dispatch module ({@code dispatch.*}). */
@Validated
@ConfigurationProperties("dispatch")
public record DispatchProperties(
		@DefaultValue @Valid Risk risk,
		@DefaultValue @Valid Eta eta,
		@DefaultValue @Valid Confirmation confirmation,
		@DefaultValue("2h") Duration unassignedLeadTime,
		@DefaultValue @Valid Planning planning,
		@DefaultValue @Valid Shifts shifts) {

	/**
	 * @param window trips with a pickup this soon are evaluated
	 * @param greenAbove slack above this is GREEN
	 * @param amberAbove slack above this (up to greenAbove) is AMBER; below it RED
	 * @param pingStale a driver position older than this counts as no position (RED)
	 */
	public record Risk(@DefaultValue("90m") Duration window, @DefaultValue("15m") Duration greenAbove,
			@DefaultValue("5m") Duration amberAbove, @DefaultValue("3m") Duration pingStale) {
	}

	/** Straight-line estimate: distance × detour factor at an average speed. */
	public record Eta(@DefaultValue("1.4") BigDecimal detourFactor, @DefaultValue("20") BigDecimal speedKmh) {
	}

	/**
	 * @param leadTime confirm this long before pickup
	 * @param grace at least this long after being assigned
	 */
	public record Confirmation(@DefaultValue("60m") Duration leadTime, @DefaultValue("10m") Duration grace) {
	}

	/**
	 * @param buffer free time between a drop-off (plus travel) and the next pickup
	 * @param fairnessMinutes added to a driver's score per trip they already have that day
	 */
	public record Planning(@DefaultValue("10m") Duration buffer, @DefaultValue("10") int fairnessMinutes) {
	}

	public record Shifts(@DefaultValue("14h") Duration maxLength, @DefaultValue("30d") Duration maxAhead) {
	}
}

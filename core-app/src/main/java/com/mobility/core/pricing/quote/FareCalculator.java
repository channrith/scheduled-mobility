package com.mobility.core.pricing.quote;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The fare of one ride. Pure arithmetic in BigDecimal; amounts are rounded once, at the end:
 *
 * <pre>
 * subtotal = base + km × per_km + min × per_min
 * fuel     = subtotal × (fuel_index − 1) × fuel_index_factor
 * surge    = clamp(requested, 1, max_surge)
 * fare     = max(min_fare, (subtotal + fuel) × surge)
 * total    = fare + airport surcharge (converted to the rule's currency)
 * </pre>
 *
 * USD totals round to the cent, KHR totals to 100 riel (the smallest note in use). Lines are rounded to the
 * minor unit and a ROUNDING line makes them add up to the total exactly.
 */
public final class FareCalculator {

	public enum LineType {
		BASE, DISTANCE, TIME, FUEL_INDEX, SURGE, MINIMUM_FARE, AIRPORT, ROUNDING
	}

	/** Money in minor units: USD cents, KHR riel. */
	public record Money(long amountMinor, String currency) {
	}

	/** The rule's terms (minor units of {@code currency}). */
	public record Tariff(String currency, long baseMinor, long perKmMinor, long perMinMinor, long minFareMinor,
			BigDecimal fuelIndexFactor, BigDecimal maxSurge) {
	}

	/**
	 * @param requestedSurge null = 1
	 * @param airportSurcharge null = no airport at either end
	 * @param usdKhrRate riel per US dollar
	 * @param serviceFeePct corporate service fee shown with the quote (not in the total); null = none
	 */
	public record Inputs(Tariff tariff, long distanceMeters, long durationSeconds, BigDecimal fuelIndex,
			BigDecimal requestedSurge, Money airportSurcharge, BigDecimal usdKhrRate, BigDecimal serviceFeePct) {
	}

	/** @param equivalentMinor the amount in the other currency (USD ↔ KHR), for information */
	public record Line(LineType type, long amountMinor, long equivalentMinor) {
	}

	/**
	 * @param surge the multiplier applied (already capped)
	 * @param serviceFeeMinor null without a corporate account; in {@code currency}
	 */
	public record Fare(String currency, List<Line> lines, long totalMinor, String equivalentCurrency, long equivalentMinor,
			BigDecimal surge, Long serviceFeeMinor, Long serviceFeeEquivalentMinor) {
	}

	private static final MathContext MC = MathContext.DECIMAL64;

	private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

	private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private FareCalculator() {
	}

	public static Fare calculate(Inputs in) {
		Tariff t = in.tariff();
		String currency = t.currency();
		BigDecimal km = BigDecimal.valueOf(in.distanceMeters()).divide(THOUSAND, MC);
		BigDecimal minutes = BigDecimal.valueOf(in.durationSeconds()).divide(SIXTY, MC);

		BigDecimal base = BigDecimal.valueOf(t.baseMinor());
		BigDecimal distance = km.multiply(BigDecimal.valueOf(t.perKmMinor()), MC);
		BigDecimal time = minutes.multiply(BigDecimal.valueOf(t.perMinMinor()), MC);
		BigDecimal subtotal = base.add(distance).add(time);

		BigDecimal fuel = subtotal.multiply(in.fuelIndex().subtract(BigDecimal.ONE), MC).multiply(t.fuelIndexFactor(), MC);
		BigDecimal afterFuel = subtotal.add(fuel);

		BigDecimal surge = cappedSurge(in.requestedSurge(), t.maxSurge());
		BigDecimal surgeAmount = afterFuel.multiply(surge.subtract(BigDecimal.ONE), MC);
		BigDecimal fare = afterFuel.add(surgeAmount);

		BigDecimal minimum = BigDecimal.valueOf(t.minFareMinor()).subtract(fare).max(BigDecimal.ZERO);
		BigDecimal airport = in.airportSurcharge() == null ? BigDecimal.ZERO
				: convert(BigDecimal.valueOf(in.airportSurcharge().amountMinor()), in.airportSurcharge().currency(), currency,
						in.usdKhrRate());

		BigDecimal exact = fare.add(minimum).add(airport);
		long total = roundTotal(exact, currency);
		String other = other(currency);

		List<Line> lines = new ArrayList<>();
		long sum = 0;
		sum += add(lines, LineType.BASE, base, true, currency, in.usdKhrRate());
		sum += add(lines, LineType.DISTANCE, distance, true, currency, in.usdKhrRate());
		sum += add(lines, LineType.TIME, time, true, currency, in.usdKhrRate());
		sum += add(lines, LineType.FUEL_INDEX, fuel, false, currency, in.usdKhrRate());
		sum += add(lines, LineType.SURGE, surgeAmount, false, currency, in.usdKhrRate());
		sum += add(lines, LineType.MINIMUM_FARE, minimum, false, currency, in.usdKhrRate());
		sum += add(lines, LineType.AIRPORT, airport, false, currency, in.usdKhrRate());
		add(lines, LineType.ROUNDING, BigDecimal.valueOf(total - sum), false, currency, in.usdKhrRate());

		long equivalent = equivalent(total, currency, in.usdKhrRate());
		Long fee = null;
		Long feeEquivalent = null;
		if (in.serviceFeePct() != null) {
			fee = roundTotal(BigDecimal.valueOf(total).multiply(in.serviceFeePct(), MC).divide(HUNDRED, MC), currency);
			feeEquivalent = equivalent(fee, currency, in.usdKhrRate());
		}
		return new Fare(currency, List.copyOf(lines), total, other, equivalent, surge, fee, feeEquivalent);
	}

	/** An amount in the other currency (USD ↔ KHR), rounded like totals: USD to the cent, KHR to 100 riel. */
	public static long equivalent(long minor, String currency, BigDecimal usdKhrRate) {
		String other = other(currency);
		return roundTotal(convert(BigDecimal.valueOf(minor), currency, other, usdKhrRate), other);
	}

	/** Surge is never below 1 and never above the rule's maximum. */
	static BigDecimal cappedSurge(BigDecimal requested, BigDecimal max) {
		BigDecimal s = requested == null ? BigDecimal.ONE : requested;
		return s.max(BigDecimal.ONE).min(max).setScale(2, RoundingMode.HALF_UP);
	}

	/** Exact conversion between minor units: 100 cents = rate riel. */
	static BigDecimal convert(BigDecimal minor, String from, String to, BigDecimal usdKhrRate) {
		if (from.equals(to)) {
			return minor;
		}
		if (from.equals("USD") && to.equals("KHR")) {
			return minor.multiply(usdKhrRate, MC).divide(HUNDRED, MC);
		}
		if (from.equals("KHR") && to.equals("USD")) {
			return minor.multiply(HUNDRED, MC).divide(usdKhrRate, MC);
		}
		throw new IllegalArgumentException("Unsupported currencies " + from + " → " + to);
	}

	/** USD to the cent; KHR to 100 riel. Half up. */
	static long roundTotal(BigDecimal minor, String currency) {
		if (currency.equals("KHR")) {
			return minor.divide(HUNDRED, MC).setScale(0, RoundingMode.HALF_UP).longValueExact() * 100;
		}
		return minor.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	static String other(String currency) {
		return currency.equals("USD") ? "KHR" : "USD";
	}

	/** Adds a line rounded to the minor unit (zero optional lines are left out); returns its amount. */
	private static long add(List<Line> lines, LineType type, BigDecimal exact, boolean always, String currency,
			BigDecimal rate) {
		long amount = exact.setScale(0, RoundingMode.HALF_UP).longValueExact();
		if (amount == 0 && !always) {
			return 0;
		}
		long equivalent = convert(BigDecimal.valueOf(amount), currency, other(currency), rate).setScale(0, RoundingMode.HALF_UP)
			.longValueExact();
		lines.add(new Line(type, amount, equivalent));
		return amount;
	}
}

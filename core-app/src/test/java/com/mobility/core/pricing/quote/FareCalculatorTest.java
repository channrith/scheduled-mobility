package com.mobility.core.pricing.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.mobility.core.pricing.quote.FareCalculator.Fare;
import com.mobility.core.pricing.quote.FareCalculator.Inputs;
import com.mobility.core.pricing.quote.FareCalculator.Line;
import com.mobility.core.pricing.quote.FareCalculator.LineType;
import com.mobility.core.pricing.quote.FareCalculator.Money;
import com.mobility.core.pricing.quote.FareCalculator.Tariff;
import org.junit.jupiter.api.Test;

class FareCalculatorTest {

	static final BigDecimal RATE = new BigDecimal("4100");

	/** $2.00 base, $0.50/km, $0.10/min, $5.00 minimum, fuel factor 1, max surge 2. */
	static final Tariff USD = new Tariff("USD", 200, 50, 10, 500, BigDecimal.ONE, BigDecimal.TWO);

	/** 8,000៛ base, 2,000៛/km, 400៛/min, 20,000៛ minimum, fuel factor 0.5, max surge 1.5. */
	static final Tariff KHR = new Tariff("KHR", 8000, 2000, 400, 20000, new BigDecimal("0.5"), new BigDecimal("1.5"));

	static Inputs inputs(Tariff tariff, long meters, long seconds) {
		return new Inputs(tariff, meters, seconds, BigDecimal.ONE, null, null, RATE, null);
	}

	static long line(Fare fare, LineType type) {
		return fare.lines().stream().filter(l -> l.type() == type).mapToLong(Line::amountMinor).sum();
	}

	static void linesAddUp(Fare fare) {
		assertThat(fare.lines().stream().mapToLong(Line::amountMinor).sum()).isEqualTo(fare.totalMinor());
	}

	@Test
	void baseDistanceAndTime() {
		Fare fare = FareCalculator.calculate(inputs(USD, 12_400, 25 * 60));

		assertThat(line(fare, LineType.BASE)).isEqualTo(200);
		assertThat(line(fare, LineType.DISTANCE)).isEqualTo(620);
		assertThat(line(fare, LineType.TIME)).isEqualTo(250);
		assertThat(fare.totalMinor()).isEqualTo(1070);
		assertThat(fare.lines()).extracting(Line::type).containsExactly(LineType.BASE, LineType.DISTANCE, LineType.TIME);
		// $10.70 × 4,100 = 43,870៛ → 43,900៛ (100 riel steps).
		assertThat(fare.equivalentCurrency()).isEqualTo("KHR");
		assertThat(fare.equivalentMinor()).isEqualTo(43_900);
		assertThat(fare.surge()).isEqualByComparingTo("1");
	}

	@Test
	void minimumFareTopsUpShortRides() {
		Fare fare = FareCalculator.calculate(inputs(USD, 1_000, 3 * 60));

		// 200 + 50 + 30 = 280 → 500
		assertThat(line(fare, LineType.MINIMUM_FARE)).isEqualTo(220);
		assertThat(fare.totalMinor()).isEqualTo(500);
		linesAddUp(fare);
	}

	@Test
	void fuelIndexIsWeightedByTheRulesFactor() {
		Tariff halfFuel = new Tariff("USD", 200, 50, 10, 0, new BigDecimal("0.5"), BigDecimal.TWO);
		Fare fare = FareCalculator.calculate(new Inputs(halfFuel, 10_000, 20 * 60, new BigDecimal("1.08"), null, null, RATE, null));

		// subtotal 200 + 500 + 200 = 900; fuel 900 × 0.08 × 0.5 = 36
		assertThat(line(fare, LineType.FUEL_INDEX)).isEqualTo(36);
		assertThat(fare.totalMinor()).isEqualTo(936);

		Fare cheaperFuel = FareCalculator.calculate(new Inputs(halfFuel, 10_000, 20 * 60, new BigDecimal("0.9"), null, null, RATE, null));
		assertThat(line(cheaperFuel, LineType.FUEL_INDEX)).isEqualTo(-45);
		assertThat(cheaperFuel.totalMinor()).isEqualTo(855);
	}

	@Test
	void surgeNeverExceedsTheRulesMaximumNorGoesBelowOne() {
		Fare capped = FareCalculator.calculate(new Inputs(USD, 10_000, 20 * 60, BigDecimal.ONE, new BigDecimal("3.5"), null, RATE, null));
		assertThat(capped.surge()).isEqualByComparingTo("2");
		assertThat(capped.totalMinor()).isEqualTo(1800);
		assertThat(line(capped, LineType.SURGE)).isEqualTo(900);

		Fare within = FareCalculator.calculate(new Inputs(USD, 10_000, 20 * 60, BigDecimal.ONE, new BigDecimal("1.25"), null, RATE, null));
		assertThat(within.totalMinor()).isEqualTo(1125);

		Fare discount = FareCalculator.calculate(new Inputs(USD, 10_000, 20 * 60, BigDecimal.ONE, new BigDecimal("0.5"), null, RATE, null));
		assertThat(discount.surge()).isEqualByComparingTo("1");
		assertThat(discount.totalMinor()).isEqualTo(900);
	}

	@Test
	void surgeAppliesBeforeTheMinimumAndNotToTheAirportSurcharge() {
		Fare fare = FareCalculator.calculate(new Inputs(USD, 1_000, 60, BigDecimal.ONE, BigDecimal.TWO,
				new Money(1000, "USD"), RATE, null));

		// 200 + 50 + 10 = 260, × 2 = 520 (above the 500 minimum), + 1000 airport
		assertThat(line(fare, LineType.SURGE)).isEqualTo(260);
		assertThat(line(fare, LineType.MINIMUM_FARE)).isZero();
		assertThat(line(fare, LineType.AIRPORT)).isEqualTo(1000);
		assertThat(fare.totalMinor()).isEqualTo(1520);
	}

	@Test
	void airportSurchargeInTheOtherCurrencyIsConverted() {
		// A 41,000៛ surcharge on a USD rule is $10.00 at 4,100.
		Fare fare = FareCalculator.calculate(new Inputs(USD, 30_000, 45 * 60, BigDecimal.ONE, null, new Money(41_000, "KHR"),
				RATE, null));

		assertThat(line(fare, LineType.AIRPORT)).isEqualTo(1000);
		// 200 + 1500 + 450 + 1000
		assertThat(fare.totalMinor()).isEqualTo(3150);
		linesAddUp(fare);
	}

	@Test
	void rielTotalsRoundTo100WithARoundingLine() {
		Fare fare = FareCalculator.calculate(inputs(KHR, 7_300, 19 * 60 + 30));

		// 8000 + 14600 + 7800 = 30,400៛ exactly; then a messier ride
		assertThat(fare.totalMinor()).isEqualTo(30_400);
		Fare messy = FareCalculator.calculate(inputs(KHR, 7_333, 19 * 60 + 47));
		// 8000 + 14666 + 7913.33 = 30,579.33 → 30,600
		assertThat(messy.totalMinor()).isEqualTo(30_600);
		assertThat(messy.totalMinor() % 100).isZero();
		assertThat(line(messy, LineType.ROUNDING)).isEqualTo(21);
		linesAddUp(messy);
		// 30,600៛ / 4,100 = $7.4634 → $7.46
		assertThat(messy.equivalentCurrency()).isEqualTo("USD");
		assertThat(messy.equivalentMinor()).isEqualTo(746);
	}

	@Test
	void linesAlwaysAddUpToTheTotal() {
		for (long meters = 0; meters < 40_000; meters += 1_337) {
			Fare fare = FareCalculator.calculate(new Inputs(KHR, meters, meters / 7, new BigDecimal("1.137"),
					new BigDecimal("1.33"), new Money(333, "USD"), new BigDecimal("4087.5"), null));
			linesAddUp(fare);
			assertThat(fare.totalMinor() % 100).isZero();
		}
	}

	@Test
	void corporateServiceFeeIsShownButNotPartOfTheTotal() {
		Fare fare = FareCalculator.calculate(new Inputs(USD, 12_400, 25 * 60, BigDecimal.ONE, null, null, RATE,
				new BigDecimal("8.00")));

		assertThat(fare.totalMinor()).isEqualTo(1070);
		// 8 % of $10.70 = $0.856 → $0.86; 86 cents = 3,526៛ → 3,500៛
		assertThat(fare.serviceFeeMinor()).isEqualTo(86);
		assertThat(fare.serviceFeeEquivalentMinor()).isEqualTo(3_500);
		assertThat(FareCalculator.calculate(inputs(USD, 12_400, 25 * 60)).serviceFeeMinor()).isNull();
	}

	@Test
	void zeroDistanceStillPaysTheMinimum() {
		Fare fare = FareCalculator.calculate(inputs(USD, 0, 0));

		assertThat(fare.totalMinor()).isEqualTo(500);
		assertThat(line(fare, LineType.DISTANCE)).isZero();
	}
}

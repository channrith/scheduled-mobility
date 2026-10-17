package com.mobility.core.pricing.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mobility.core.driver.VehicleClass;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Corporate override versus city price. */
class RuleSelectionTest {

	static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	static final UUID COMPANY = UUID.randomUUID();

	final AtomicLong seq = new AtomicLong();

	PricingRule version(UUID corporateId, int dayFrom, Integer dayTo, boolean active, long base) {
		PricingRule rule = new PricingRule(new RuleTerms("PHNOM_PENH", VehicleClass.CAR, corporateId, "USD", base, 50, 10, 500,
				BigDecimal.ONE, BigDecimal.TWO, day(dayFrom), dayTo == null ? null : day(dayTo), active), null, T0);
		ReflectionTestUtils.setField(rule, "seq", seq.incrementAndGet());
		return rule;
	}

	static Instant day(int d) {
		return T0.plusSeconds(86_400L * d);
	}

	@Test
	void theCompanysOverrideBeatsTheCityPrice() {
		PricingRule city = version(null, 0, null, true, 200);
		PricingRule override = version(COMPANY, 0, null, true, 150);

		assertThat(PricingRuleService.select(List.of(override), List.of(city), day(1))).contains(override);
	}

	@Test
	void withoutAnOverrideInForceTheCityPriceApplies() {
		PricingRule city = version(null, 0, null, true, 200);
		PricingRule laterOverride = version(COMPANY, 10, null, true, 150);
		PricingRule endedOverride = version(COMPANY, 0, 5, true, 150);
		PricingRule retired = version(COMPANY, 3, null, false, 150);

		assertThat(PricingRuleService.select(List.of(), List.of(city), day(1))).contains(city);
		assertThat(PricingRuleService.select(List.of(laterOverride), List.of(city), day(1))).contains(city);
		assertThat(PricingRuleService.select(List.of(laterOverride), List.of(city), day(11))).contains(laterOverride);
		assertThat(PricingRuleService.select(List.of(endedOverride), List.of(city), day(6))).contains(city);
		assertThat(PricingRuleService.select(List.of(endedOverride, retired), List.of(city), day(4))).contains(city);
	}

	@Test
	void anOverrideDoesNotNeedACityPrice() {
		PricingRule override = version(COMPANY, 0, null, true, 150);

		assertThat(PricingRuleService.select(List.of(override), List.of(), day(1))).contains(override);
		assertThat(PricingRuleService.select(List.of(), List.of(), day(1))).isEmpty();
	}
}

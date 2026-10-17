package com.mobility.core.pricing.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.pricing.rule.RuleTimeline.Period;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RuleTimelineTest {

	static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	final AtomicLong seq = new AtomicLong();

	PricingRule version(int dayFrom, Integer dayTo, boolean active, long base) {
		Instant to = dayTo == null ? null : T0.plusSeconds(86_400L * dayTo);
		PricingRule rule = new PricingRule(new RuleTerms("PHNOM_PENH", VehicleClass.CAR, null, "USD", base, 50, 10, 500,
				BigDecimal.ONE, BigDecimal.TWO, T0.plusSeconds(86_400L * dayFrom), to, active), null, T0);
		ReflectionTestUtils.setField(rule, "seq", seq.incrementAndGet());
		return rule;
	}

	static Instant day(double d) {
		return T0.plusSeconds((long) (86_400 * d));
	}

	@Test
	void latestStartedVersionWins() {
		PricingRule v1 = version(0, null, true, 100);
		PricingRule v2 = version(10, null, true, 200);
		RuleTimeline timeline = new RuleTimeline(List.of(v2, v1));

		assertThat(timeline.at(day(-1))).isEmpty();
		assertThat(timeline.at(day(0))).contains(v1);
		assertThat(timeline.at(day(9.99))).contains(v1);
		assertThat(timeline.at(day(10))).contains(v2);
		assertThat(timeline.at(day(400))).contains(v2);
	}

	@Test
	void sameStartTheVersionCreatedLastWins() {
		PricingRule first = version(5, null, true, 100);
		PricingRule correction = version(5, null, true, 150);
		RuleTimeline timeline = new RuleTimeline(List.of(correction, first));

		assertThat(timeline.at(day(6))).contains(correction);
		assertThat(timeline.entries(day(6))).extracting(RuleTimeline.Entry::period)
			.containsExactly(Period.CURRENT, Period.NEVER_IN_FORCE);
	}

	@Test
	void expiredOrRetiredLineHasNoPriceAndOlderVersionsDoNotComeBack() {
		PricingRule v1 = version(0, null, true, 100);
		PricingRule limited = version(10, 20, true, 200);
		RuleTimeline timeline = new RuleTimeline(List.of(v1, limited));
		assertThat(timeline.at(day(15))).contains(limited);
		assertThat(timeline.at(day(21))).isEmpty();

		PricingRule retired = version(30, null, false, 200);
		RuleTimeline withRetirement = new RuleTimeline(List.of(v1, retired));
		assertThat(withRetirement.at(day(29))).contains(v1);
		assertThat(withRetirement.at(day(31))).isEmpty();
	}

	@Test
	void historyNewestFirstWithPeriodsAndEnds() {
		PricingRule v1 = version(0, null, true, 100);
		PricingRule v2 = version(10, 15, true, 200);
		PricingRule v3 = version(20, null, true, 300);
		List<RuleTimeline.Entry> entries = new RuleTimeline(List.of(v1, v3, v2)).entries(day(12));

		assertThat(entries).extracting(RuleTimeline.Entry::rule).containsExactly(v3, v2, v1);
		assertThat(entries).extracting(RuleTimeline.Entry::period)
			.containsExactly(Period.UPCOMING, Period.CURRENT, Period.PAST);
		assertThat(entries).extracting(RuleTimeline.Entry::effectiveUntil).containsExactly(null, day(15), day(10));
		assertThat(new RuleTimeline(List.of(v1, v3, v2)).upcoming(day(12))).containsExactly(v3);
	}

	@Test
	void versionNotYetReadBackCountsAsNewest() {
		PricingRule older = version(5, null, true, 100);
		PricingRule fresh = new PricingRule(new RuleTerms("PHNOM_PENH", VehicleClass.CAR, null, "USD", 999, 50, 10, 500,
				BigDecimal.ONE, BigDecimal.TWO, day(5), null, true), null, T0);

		assertThat(new RuleTimeline(List.of(fresh, older)).at(day(6))).contains(fresh);
	}
}

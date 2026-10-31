package com.mobility.core.dispatch.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.mobility.core.dispatch.DispatchProperties.Risk;
import com.mobility.core.dispatch.RiskTier;
import com.mobility.core.dispatch.risk.SlackTiers.Evaluation;
import com.mobility.core.dispatch.risk.SlackTiers.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlackTiersTest {

	static final Risk RISK = new Risk(Duration.ofMinutes(90), Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(3));

	static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");

	@ParameterizedTest(name = "slack {0} s → {1}")
	@CsvSource({ "3600, GREEN", "901, GREEN", "900, AMBER", "600, AMBER", "300, AMBER", "299, RED", "0, RED", "-600, RED" })
	void tiersAtEveryBoundary(long slackSeconds, RiskTier expected) {
		assertThat(SlackTiers.tier(Duration.ofSeconds(slackSeconds), RISK)).isEqualTo(expected);
	}

	@Test
	void slackIsTimeToPickupMinusEta() {
		Evaluation e = SlackTiers.evaluate(NOW, NOW.plus(Duration.ofMinutes(40)), NOW.minusSeconds(20), Duration.ofMinutes(30), RISK);

		assertThat(e.slack()).isEqualTo(Duration.ofMinutes(10));
		assertThat(e.tier()).isEqualTo(RiskTier.AMBER);
		assertThat(e.reason()).isEqualTo(Reason.SLACK);
	}

	@Test
	void noPositionForThreeMinutesIsRedWhateverTheSlack() {
		Instant pickup = NOW.plus(Duration.ofMinutes(80));

		assertThat(SlackTiers.evaluate(NOW, pickup, null, Duration.ZERO, RISK))
			.isEqualTo(new Evaluation(RiskTier.RED, null, Reason.NO_PING));
		assertThat(SlackTiers.evaluate(NOW, pickup, NOW.minus(Duration.ofMinutes(3)), Duration.ZERO, RISK).reason())
			.isEqualTo(Reason.NO_PING);
		assertThat(SlackTiers.evaluate(NOW, pickup, NOW.minus(Duration.ofSeconds(179)), Duration.ZERO, RISK).tier())
			.isEqualTo(RiskTier.GREEN);
	}

	@Test
	void aDriverAlreadyLateIsRed() {
		Evaluation e = SlackTiers.evaluate(NOW, NOW.plus(Duration.ofMinutes(10)), NOW, Duration.ofMinutes(25), RISK);

		assertThat(e.slack()).isEqualTo(Duration.ofMinutes(-15));
		assertThat(e.tier()).isEqualTo(RiskTier.RED);
	}
}

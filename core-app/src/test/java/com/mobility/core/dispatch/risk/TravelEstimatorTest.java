package com.mobility.core.dispatch.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TravelEstimatorTest {

	@Test
	void distanceTimesDetourAtAverageSpeed() {
		TravelEstimator estimator = new TravelEstimator(1.4, 20);

		// Royal Palace → Techo airport: about 23 km straight, × 1.4 ≈ 32.5 km, at 20 km/h ≈ 97 minutes.
		Duration d = estimator.between(11.5636, 104.9312, 11.3650, 104.8620);
		assertThat(d.toMinutes()).isBetween(92L, 102L);
		assertThat(estimator.between(11.5636, 104.9312, 11.5636, 104.9312)).isZero();
	}
}

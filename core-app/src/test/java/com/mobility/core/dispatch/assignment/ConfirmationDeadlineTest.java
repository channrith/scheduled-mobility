package com.mobility.core.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.mobility.core.dispatch.DispatchProperties.Confirmation;
import org.junit.jupiter.api.Test;

class ConfirmationDeadlineTest {

	static final Confirmation RULES = new Confirmation(Duration.ofMinutes(60), Duration.ofMinutes(10));

	static final Instant PICKUP = Instant.parse("2026-11-02T03:00:00Z");

	@Test
	void sixtyMinutesBeforePickup() {
		assertThat(ConfirmationDeadline.of(PICKUP, PICKUP.minus(Duration.ofDays(1)), RULES)).isEqualTo(PICKUP.minus(Duration.ofMinutes(60)));
	}

	@Test
	void aLateAssignmentGetsTenMinutesButNeverBeyondThePickup() {
		Instant assigned = PICKUP.minus(Duration.ofMinutes(30));
		assertThat(ConfirmationDeadline.of(PICKUP, assigned, RULES)).isEqualTo(assigned.plus(Duration.ofMinutes(10)));
		assertThat(ConfirmationDeadline.of(PICKUP, PICKUP.minus(Duration.ofMinutes(5)), RULES)).isEqualTo(PICKUP);
	}

	@Test
	void missedOnlyWhenUnconfirmedAtOrAfterTheDeadline() {
		Instant deadline = PICKUP.minus(Duration.ofMinutes(60));
		assertThat(ConfirmationDeadline.missed(deadline, null, deadline.minusSeconds(1))).isFalse();
		assertThat(ConfirmationDeadline.missed(deadline, null, deadline)).isTrue();
		assertThat(ConfirmationDeadline.missed(deadline, deadline.minusSeconds(60), deadline.plusSeconds(60))).isFalse();
	}
}

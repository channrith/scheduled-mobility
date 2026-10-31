package com.mobility.core.booking.ride;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Test;

class RecurrenceTest {

	/** Monday 2 Nov 2026, 07:30 in Phnom Penh. */
	static final Instant FIRST = ZonedDateTime.of(2026, 11, 2, 7, 30, 0, 0, BusinessTime.ZONE).toInstant();

	@Test
	void sameLocalTimeOnTheChosenWeekdaysUntilTheEndDate() {
		List<Instant> pickups = Recurrence.pickups(FIRST, Set.of(MONDAY, WEDNESDAY, FRIDAY), LocalDate.of(2026, 11, 13));

		assertThat(pickups).hasSize(6);
		assertThat(pickups.getFirst()).isEqualTo(FIRST);
		assertThat(pickups).allSatisfy(p -> assertThat(p.atZone(BusinessTime.ZONE).toLocalTime().toString()).isEqualTo("07:30"));
		assertThat(pickups.getLast().atZone(BusinessTime.ZONE).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 13));
	}

	@Test
	void theFirstRidesWeekdayMustBeIncluded() {
		assertThatThrownBy(() -> Recurrence.pickups(FIRST, Set.of(TUESDAY), LocalDate.of(2026, 11, 30)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getProperties()).containsEntry("field", "days"));
	}

	@Test
	void atMost90DaysAndNotBeforeTheFirstRide() {
		assertThat(Recurrence.pickups(FIRST, Set.of(MONDAY), LocalDate.of(2026, 11, 2).plusDays(90))).hasSize(13);
		assertThatThrownBy(() -> Recurrence.pickups(FIRST, Set.of(MONDAY), LocalDate.of(2026, 11, 2).plusDays(91)))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Recurrence.pickups(FIRST, Set.of(MONDAY), LocalDate.of(2026, 11, 1)))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Recurrence.pickups(FIRST, Set.of(), LocalDate.of(2026, 11, 30))).isInstanceOf(ApiException.class);
	}
}

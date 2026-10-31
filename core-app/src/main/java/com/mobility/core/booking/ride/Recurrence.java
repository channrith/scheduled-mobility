package com.mobility.core.booking.ride;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A weekly series: the quote's pickup time of day (Phnom Penh time) on the chosen weekdays, from the quote's date to
 * {@code until}. The first trip is the quote's own pickup, so its weekday must be one of the days.
 */
final class Recurrence {

	/** How far a series may run. */
	static final int MAX_DAYS = 90;

	private Recurrence() {
	}

	/** @throws ApiException 400 {@code booking.recurrence-invalid} with the offending {@code field} */
	static List<Instant> pickups(Instant firstPickup, Set<DayOfWeek> days, LocalDate until) {
		if (days == null || days.isEmpty()) {
			throw invalid("days");
		}
		LocalDateTime first = LocalDateTime.ofInstant(firstPickup, BusinessTime.ZONE);
		LocalDate start = first.toLocalDate();
		if (!days.contains(start.getDayOfWeek())) {
			throw invalid("days");
		}
		if (until == null || until.isBefore(start) || ChronoUnit.DAYS.between(start, until) > MAX_DAYS) {
			throw invalid("until");
		}
		List<Instant> pickups = new ArrayList<>();
		for (LocalDate d = start; !d.isAfter(until); d = d.plusDays(1)) {
			if (days.contains(d.getDayOfWeek())) {
				pickups.add(d.atTime(first.toLocalTime()).atZone(BusinessTime.ZONE).toInstant());
			}
		}
		return pickups;
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "booking.recurrence-invalid").withProperty("field", field);
	}
}

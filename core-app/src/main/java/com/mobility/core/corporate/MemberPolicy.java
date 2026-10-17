package com.mobility.core.corporate;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * What a member may book. Every field is optional: null (or an empty list) means no restriction.
 *
 * @param allowedVehicleClasses classes the member may ride in
 * @param allowedHours pickup times allowed, in Phnom Penh time; any window that matches allows the ride
 * @param maxFare highest fare for one ride
 */
public record MemberPolicy(List<VehicleClass> allowedVehicleClasses, List<HoursWindow> allowedHours, Money maxFare) {

	public static final MemberPolicy UNRESTRICTED = new MemberPolicy(null, null, null);

	/**
	 * Pickup window on the given days, [from, to). If {@code to} is before {@code from} the window runs past
	 * midnight: e.g. MONDAY 22:00–06:00 covers Monday 22:00 to Tuesday 06:00.
	 */
	public record HoursWindow(List<DayOfWeek> days, LocalTime from, LocalTime to) {

		boolean contains(LocalDateTime pickup) {
			Set<DayOfWeek> on = EnumSet.copyOf(days);
			LocalTime time = pickup.toLocalTime();
			DayOfWeek day = pickup.getDayOfWeek();
			if (from.isBefore(to)) {
				return on.contains(day) && !time.isBefore(from) && time.isBefore(to);
			}
			// Overnight: the evening part on a listed day, or the early-morning part after a listed day.
			return (on.contains(day) && !time.isBefore(from)) || (on.contains(day.minus(1)) && time.isBefore(to));
		}
	}

	/** Money in minor units (USD cents or KHR riel). */
	public record Money(long amountMinor, String currency) {
	}

	/** @throws ApiException 400 {@code corporate.policy-invalid} */
	public MemberPolicy validated() {
		if (allowedHours != null) {
			for (HoursWindow w : allowedHours) {
				if (w == null || w.days() == null || w.days().isEmpty() || w.from() == null || w.to() == null
						|| w.from().equals(w.to())) {
					throw invalid("allowedHours");
				}
			}
		}
		if (maxFare != null && (maxFare.amountMinor() <= 0 || !List.of("USD", "KHR").contains(maxFare.currency()))) {
			throw invalid("maxFare");
		}
		if (allowedVehicleClasses != null && allowedVehicleClasses.stream().anyMatch(java.util.Objects::isNull)) {
			throw invalid("allowedVehicleClasses");
		}
		return this;
	}

	/** Pure check of one ride against this policy (account and membership are checked by PolicyChecker). */
	public List<PolicyViolation> evaluate(RideRequest ride) {
		List<PolicyViolation> violations = new ArrayList<>();
		if (allowedVehicleClasses != null && !allowedVehicleClasses.isEmpty()
				&& !allowedVehicleClasses.contains(ride.vehicleClass())) {
			violations.add(PolicyViolation.VEHICLE_CLASS_NOT_ALLOWED);
		}
		if (allowedHours != null && !allowedHours.isEmpty()) {
			LocalDateTime local = LocalDateTime.ofInstant(ride.pickupAt(), BusinessTime.ZONE);
			if (allowedHours.stream().noneMatch(w -> w.contains(local))) {
				violations.add(PolicyViolation.OUTSIDE_ALLOWED_HOURS);
			}
		}
		if (maxFare != null) {
			if (!maxFare.currency().equals(ride.currency())) {
				violations.add(PolicyViolation.CURRENCY_MISMATCH);
			}
			else if (ride.fareMinor() > maxFare.amountMinor()) {
				violations.add(PolicyViolation.FARE_ABOVE_LIMIT);
			}
		}
		return violations;
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "corporate.policy-invalid").withProperty("field", field);
	}
}

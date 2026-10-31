package com.mobility.core.corporate;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.mobility.core.corporate.MemberPolicy.HoursWindow;
import com.mobility.core.corporate.MemberPolicy.Money;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MemberPolicyTest {

	/** 2026-10-12 is a Monday. Times are Phnom Penh local. */
	static RideRequest ride(VehicleClass vehicleClass, String localDateTime, long fareMinor, String currency) {
		return new RideRequest(vehicleClass, LocalDateTime.parse(localDateTime).atZone(BusinessTime.ZONE).toInstant(),
				fareMinor, currency);
	}

	static RideRequest ride(String localDateTime) {
		return ride(VehicleClass.CAR, localDateTime, 500, "USD");
	}

	@Test
	void emptyPolicyAllowsEverything() {
		assertThat(MemberPolicy.UNRESTRICTED.evaluate(ride(VehicleClass.VAN, "2026-10-11T03:00", 999_999, "KHR")))
			.isEmpty();
		assertThat(new MemberPolicy(List.of(), List.of(), null).evaluate(ride("2026-10-11T03:00"))).isEmpty();
	}

	@Test
	void vehicleClassMustBeAllowed() {
		MemberPolicy policy = new MemberPolicy(List.of(VehicleClass.TUKTUK_REMORK, VehicleClass.CAR), null, null);

		assertThat(policy.evaluate(ride(VehicleClass.CAR, "2026-10-12T09:00", 500, "USD"))).isEmpty();
		assertThat(policy.evaluate(ride(VehicleClass.SUV, "2026-10-12T09:00", 500, "USD")))
			.containsExactly(PolicyViolation.VEHICLE_CLASS_NOT_ALLOWED);
	}

	@ParameterizedTest(name = "{0} → allowed={1}")
	@CsvSource({
			"2026-10-12T07:00, true",   // Monday, start is inclusive
			"2026-10-12T06:59, false",
			"2026-10-12T19:59, true",
			"2026-10-12T20:00, false",  // end is exclusive
			"2026-10-16T12:00, true",   // Friday
			"2026-10-17T12:00, false" }) // Saturday
	void weekdayOfficeHours(String pickup, boolean allowed) {
		MemberPolicy policy = new MemberPolicy(null,
				List.of(new HoursWindow(List.of(MONDAY, TUESDAY, java.time.DayOfWeek.WEDNESDAY,
						java.time.DayOfWeek.THURSDAY, FRIDAY), LocalTime.of(7, 0), LocalTime.of(20, 0))),
				null);

		assertThat(policy.evaluate(ride(pickup)).isEmpty()).isEqualTo(allowed);
	}

	@ParameterizedTest(name = "{0} → allowed={1}")
	@CsvSource({
			"2026-10-12T22:00, true",   // Monday evening
			"2026-10-13T05:59, true",   // Tuesday early morning belongs to Monday's window
			"2026-10-13T06:00, false",
			"2026-10-12T05:00, false",  // Monday early morning would need Sunday in the window
			"2026-10-12T21:59, false" })
	void overnightWindowRunsPastMidnight(String pickup, boolean allowed) {
		MemberPolicy policy = new MemberPolicy(null,
				List.of(new HoursWindow(List.of(MONDAY), LocalTime.of(22, 0), LocalTime.of(6, 0))), null);

		assertThat(policy.evaluate(ride(pickup)).isEmpty()).isEqualTo(allowed);
	}

	@Test
	void anyMatchingWindowAllows() {
		MemberPolicy policy = new MemberPolicy(null, List.of(
				new HoursWindow(List.of(MONDAY), LocalTime.of(7, 0), LocalTime.of(9, 0)),
				new HoursWindow(List.of(SATURDAY, SUNDAY), LocalTime.of(10, 0), LocalTime.of(14, 0))), null);

		assertThat(policy.evaluate(ride("2026-10-12T08:00"))).isEmpty();
		assertThat(policy.evaluate(ride("2026-10-18T11:00"))).isEmpty();
		assertThat(policy.evaluate(ride("2026-10-12T12:00"))).containsExactly(PolicyViolation.OUTSIDE_ALLOWED_HOURS);
	}

	@Test
	void pickupTimeIsReadInPhnomPenhNotUtc() {
		// 01:00 UTC Monday = 08:00 Monday in Phnom Penh.
		MemberPolicy policy = new MemberPolicy(null,
				List.of(new HoursWindow(List.of(MONDAY), LocalTime.of(7, 0), LocalTime.of(9, 0))), null);

		assertThat(policy.evaluate(new RideRequest(VehicleClass.CAR, java.time.Instant.parse("2026-10-12T01:00:00Z"), 1,
				"USD"))).isEmpty();
	}

	@Test
	void fareLimitIsInclusiveAndCurrencyMustMatch() {
		MemberPolicy policy = new MemberPolicy(null, null, new Money(1500, "USD"));

		assertThat(policy.evaluate(ride(VehicleClass.CAR, "2026-10-12T09:00", 1500, "USD"))).isEmpty();
		assertThat(policy.evaluate(ride(VehicleClass.CAR, "2026-10-12T09:00", 1501, "USD")))
			.containsExactly(PolicyViolation.FARE_ABOVE_LIMIT);
		assertThat(policy.evaluate(ride(VehicleClass.CAR, "2026-10-12T09:00", 6000, "KHR")))
			.containsExactly(PolicyViolation.CURRENCY_MISMATCH);
	}

	@Test
	void reportsEveryViolation() {
		MemberPolicy policy = new MemberPolicy(List.of(VehicleClass.MOTO),
				List.of(new HoursWindow(List.of(MONDAY), LocalTime.of(7, 0), LocalTime.of(9, 0))), new Money(100, "USD"));

		assertThat(policy.evaluate(ride(VehicleClass.CAR, "2026-10-12T12:00", 200, "USD"))).containsExactly(
				PolicyViolation.VEHICLE_CLASS_NOT_ALLOWED, PolicyViolation.OUTSIDE_ALLOWED_HOURS,
				PolicyViolation.FARE_ABOVE_LIMIT);
	}

	@Test
	void invalidPoliciesAreRejected() {
		assertInvalid(new MemberPolicy(null, List.of(new HoursWindow(List.of(), LocalTime.of(7, 0), LocalTime.of(9, 0))), null), "allowedHours");
		assertInvalid(new MemberPolicy(null, List.of(new HoursWindow(List.of(MONDAY), LocalTime.of(7, 0), LocalTime.of(7, 0))), null), "allowedHours");
		assertInvalid(new MemberPolicy(null, null, new Money(0, "USD")), "maxFare");
		assertInvalid(new MemberPolicy(null, null, new Money(100, "EUR")), "maxFare");
		assertThat(new MemberPolicy(List.of(VehicleClass.CAR), null, new Money(100, "KHR")).validated()).isNotNull();
	}

	private static void assertInvalid(MemberPolicy policy, String field) {
		assertThatThrownBy(policy::validated).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getCode()).isEqualTo("corporate.policy-invalid");
			assertThat(ex.getProperties()).containsEntry("field", field);
		});
	}
}

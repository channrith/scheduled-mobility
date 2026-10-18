package com.mobility.core.booking.trip;

import static com.mobility.core.booking.TripStatus.ASSIGNED;
import static com.mobility.core.booking.TripStatus.CANCELLED_BY_DRIVER;
import static com.mobility.core.booking.TripStatus.CANCELLED_BY_OPS;
import static com.mobility.core.booking.TripStatus.CANCELLED_BY_PASSENGER;
import static com.mobility.core.booking.TripStatus.COMPLETED;
import static com.mobility.core.booking.TripStatus.DISPUTED;
import static com.mobility.core.booking.TripStatus.DRIVER_ARRIVED;
import static com.mobility.core.booking.TripStatus.DRIVER_EN_ROUTE;
import static com.mobility.core.booking.TripStatus.IN_PROGRESS;
import static com.mobility.core.booking.TripStatus.NO_SHOW;
import static com.mobility.core.booking.TripStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Test;

class TripStateMachineTest {

	@Test
	void theHappyPathIsAllowedStepByStep() {
		List<TripStatus> path = List.of(SCHEDULED, ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS, COMPLETED);
		for (int i = 0; i + 1 < path.size(); i++) {
			assertThat(TripStateMachine.allowed(path.get(i), path.get(i + 1))).as(path.get(i) + " → " + path.get(i + 1)).isTrue();
		}
	}

	@Test
	void stepsCannotBeSkippedOrReversed() {
		assertThat(TripStateMachine.allowed(SCHEDULED, DRIVER_EN_ROUTE)).isFalse();
		assertThat(TripStateMachine.allowed(ASSIGNED, IN_PROGRESS)).isFalse();
		assertThat(TripStateMachine.allowed(DRIVER_EN_ROUTE, COMPLETED)).isFalse();
		assertThat(TripStateMachine.allowed(IN_PROGRESS, DRIVER_ARRIVED)).isFalse();
		assertThat(TripStateMachine.allowed(COMPLETED, IN_PROGRESS)).isFalse();
	}

	@Test
	void ridersCanCancelOnlyUntilTheDriverArrives() {
		assertThat(TripStateMachine.allowed(SCHEDULED, CANCELLED_BY_PASSENGER)).isTrue();
		assertThat(TripStateMachine.allowed(ASSIGNED, CANCELLED_BY_PASSENGER)).isTrue();
		assertThat(TripStateMachine.allowed(DRIVER_EN_ROUTE, CANCELLED_BY_PASSENGER)).isTrue();
		assertThat(TripStateMachine.allowed(DRIVER_ARRIVED, CANCELLED_BY_PASSENGER)).isFalse();
		assertThat(TripStateMachine.allowed(IN_PROGRESS, CANCELLED_BY_PASSENGER)).isFalse();
	}

	@Test
	void aDroppedTripGoesBackToScheduledAndNoShowNeedsAnArrival() {
		assertThat(TripStateMachine.allowed(ASSIGNED, SCHEDULED)).isTrue();
		assertThat(TripStateMachine.allowed(DRIVER_EN_ROUTE, SCHEDULED)).isTrue();
		assertThat(TripStateMachine.allowed(DRIVER_ARRIVED, SCHEDULED)).isFalse();
		assertThat(TripStateMachine.allowed(DRIVER_ARRIVED, NO_SHOW)).isTrue();
		assertThat(TripStateMachine.allowed(DRIVER_EN_ROUTE, NO_SHOW)).isFalse();
		assertThat(TripStateMachine.allowed(SCHEDULED, CANCELLED_BY_DRIVER)).isFalse();
	}

	@Test
	void endStatesStayEndedExceptADispute() {
		for (TripStatus end : List.of(CANCELLED_BY_PASSENGER, CANCELLED_BY_DRIVER, CANCELLED_BY_OPS, NO_SHOW)) {
			for (TripStatus to : TripStatus.values()) {
				assertThat(TripStateMachine.allowed(end, to)).as(end + " → " + to).isFalse();
			}
		}
		assertThat(TripStateMachine.allowed(COMPLETED, DISPUTED)).isTrue();
		assertThat(TripStateMachine.allowed(DISPUTED, COMPLETED)).isTrue();
		assertThat(TripStateMachine.allowed(COMPLETED, CANCELLED_BY_OPS)).isFalse();
	}

	@Test
	void aRefusedChangeIsA409AndLeavesTheTripAsItWas() {
		Trip trip = new Trip(UUID.randomUUID(), 1, Instant.parse("2026-11-02T01:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

		assertThatThrownBy(() -> trip.moveTo(IN_PROGRESS, Instant.now())).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getStatus().value()).isEqualTo(409));
		assertThat(trip.getStatus()).isEqualTo(SCHEDULED);
		assertThat(trip.getStartedAt()).isNull();
	}

	@Test
	void backToScheduledClearsTheDriver() {
		Instant now = Instant.parse("2026-11-01T00:00:00Z");
		Trip trip = new Trip(UUID.randomUUID(), 1, now.plusSeconds(3600), now);
		trip.assign(UUID.randomUUID(), UUID.randomUUID(), now);
		trip.moveTo(DRIVER_EN_ROUTE, now);

		trip.moveTo(SCHEDULED, now);

		assertThat(trip.getDriverId()).isNull();
		assertThat(trip.getAssignedAt()).isNull();
		assertThat(trip.getEnRouteAt()).isNull();
	}
}

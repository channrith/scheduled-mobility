package com.mobility.core.dispatch.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.risk.TravelEstimator;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Commitment;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.DayPlan;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.DayProblem;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Driver;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Point;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Rules;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Suggestion;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.TripToPlan;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Window;
import com.mobility.core.driver.VehicleClass;
import org.junit.jupiter.api.Test;

class GreedyAssignmentStrategyTest {

	static final Instant DAY = Instant.parse("2026-11-02T00:00:00Z");

	static final Point CENTRE = new Point(11.5564, 104.9282);

	/** About 11 km north of the centre (≈ 46 min at 1.4 × straight line, 20 km/h). */
	static final Point NORTH = new Point(11.6564, 104.9282);

	static final Rules RULES = new Rules(Duration.ofMinutes(10), 10);

	final GreedyAssignmentStrategy strategy = new GreedyAssignmentStrategy(new TravelEstimator(new DispatchProperties(
			new DispatchProperties.Risk(Duration.ofMinutes(90), Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(3)),
			new DispatchProperties.Eta(new java.math.BigDecimal("1.4"), new java.math.BigDecimal("20")),
			new DispatchProperties.Confirmation(Duration.ofMinutes(60), Duration.ofMinutes(10)), Duration.ofHours(2),
			new DispatchProperties.Planning(Duration.ofMinutes(10), 10),
			new DispatchProperties.Shifts(Duration.ofHours(14), Duration.ofDays(30)))));

	static Instant at(int hour, int minute) {
		return DAY.plus(Duration.ofHours(hour)).plus(Duration.ofMinutes(minute));
	}

	static List<Window> allDay() {
		return List.of(new Window(at(0, 0), at(14, 0)));
	}

	static Driver driver(String name, VehicleClass vc, Point start, List<Window> shifts) {
		return new Driver(UUID.nameUUIDFromBytes(name.getBytes()), name, vc, start, shifts);
	}

	static TripToPlan trip(String id, VehicleClass vc, Instant pickup, int minutes, Point from, Point to) {
		return new TripToPlan(UUID.nameUUIDFromBytes(id.getBytes()), vc, pickup, pickup.plus(Duration.ofMinutes(minutes)), from, to);
	}

	DayPlan plan(List<TripToPlan> trips, List<Driver> drivers, List<Commitment> commitments) {
		return strategy.plan(new DayProblem(trips, drivers, commitments, RULES));
	}

	@Test
	void nearestDriverOfTheRightClassWithAShiftIsPrimaryAndTheNextIsBackup() {
		Driver near = driver("near", VehicleClass.CAR, CENTRE, allDay());
		Driver far = driver("far", VehicleClass.CAR, NORTH, allDay());
		Driver moto = driver("moto", VehicleClass.MOTO, CENTRE, allDay());
		Driver offShift = driver("off", VehicleClass.CAR, CENTRE, List.of(new Window(at(10, 0), at(14, 0))));

		Suggestion s = plan(List.of(trip("t1", VehicleClass.CAR, at(3, 0), 30, CENTRE, NORTH)), List.of(far, moto, offShift, near),
				List.of()).suggestions().getFirst();

		assertThat(s.primary().driverId()).isEqualTo(near.driverId());
		assertThat(s.backup().driverId()).isEqualTo(far.driverId());
		assertThat(s.primary().travel()).isZero();
	}

	@Test
	void tripsAreSpreadFairly() {
		Driver a = driver("a", VehicleClass.CAR, CENTRE, allDay());
		Driver b = driver("b", VehicleClass.CAR, CENTRE, allDay());

		List<Suggestion> s = plan(List.of(trip("t1", VehicleClass.CAR, at(2, 0), 20, CENTRE, CENTRE),
				trip("t2", VehicleClass.CAR, at(5, 0), 20, CENTRE, CENTRE)), List.of(a, b), List.of()).suggestions();

		assertThat(s.get(0).primary().driverId()).isNotEqualTo(s.get(1).primary().driverId());
	}

	@Test
	void aDriverCannotTakeATripTheyCannotReachInTime() {
		Driver busy = driver("busy", VehicleClass.CAR, CENTRE, allDay());
		// Busy drops off up north at 03:00; the next pickup is in the centre at 03:20 — about 46 min away.
		Commitment held = new Commitment(busy.driverId(), UUID.randomUUID(), at(2, 30), at(3, 0), CENTRE, NORTH);

		Suggestion s = plan(List.of(trip("t2", VehicleClass.CAR, at(3, 20), 20, CENTRE, CENTRE)), List.of(busy), List.of(held))
			.suggestions().getFirst();

		assertThat(s.primary()).isNull();
		assertThat(s.unplannedReason()).isEqualTo("NO_FREE_DRIVER");
		Suggestion later = plan(List.of(trip("t3", VehicleClass.CAR, at(4, 30), 20, CENTRE, CENTRE)), List.of(busy), List.of(held))
			.suggestions().getFirst();
		assertThat(later.primary().driverId()).isEqualTo(busy.driverId());
		assertThat(later.primary().travel().toMinutes()).isBetween(40L, 52L);
	}

	@Test
	void reasonsWhenNobodyFits() {
		Driver moto = driver("moto", VehicleClass.MOTO, CENTRE, allDay());
		Driver car = driver("car", VehicleClass.CAR, CENTRE, List.of());

		assertThat(plan(List.of(trip("t1", VehicleClass.VAN, at(3, 0), 30, CENTRE, NORTH)), List.of(moto, car), List.of())
			.suggestions().getFirst().unplannedReason()).isEqualTo("NO_DRIVER_WITH_CLASS");
		assertThat(plan(List.of(trip("t1", VehicleClass.CAR, at(3, 0), 30, CENTRE, NORTH)), List.of(moto, car), List.of())
			.suggestions().getFirst().unplannedReason()).isEqualTo("NO_SHIFT");
	}

	@Test
	void theBackupIsADifferentDriverAndOnlyOneCandidateMeansNoBackup() {
		Driver only = driver("only", VehicleClass.CAR, CENTRE, allDay());

		Suggestion s = plan(List.of(trip("t1", VehicleClass.CAR, at(3, 0), 30, CENTRE, NORTH)), List.of(only), List.of())
			.suggestions().getFirst();

		assertThat(s.primary().driverId()).isEqualTo(only.driverId());
		assertThat(s.backup()).isNull();
	}
}

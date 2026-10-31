package com.mobility.core.dispatch.suggest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;

/**
 * Plans a day: a primary and a backup driver per trip that needs one. Pure: it gets a snapshot and returns a plan, so
 * a solver (e.g. Timefold) can replace the simple rules without touching the rest of dispatch.
 */
public interface AssignmentStrategy {

	record Point(double latitude, double longitude) {
	}

	/** A trip to plan. */
	record TripToPlan(UUID tripId, VehicleClass vehicleClass, Instant pickupAt, Instant dropoffAt, Point pickup, Point dropoff) {
	}

	/** @param start where the driver begins the day (home zone centre, or the city centre) */
	record Driver(UUID driverId, String name, VehicleClass vehicleClass, Point start, List<Window> shifts) {
	}

	record Window(Instant from, Instant to) {
	}

	/** A trip the driver already has (primary), which the plan must fit around. */
	record Commitment(UUID driverId, UUID tripId, Instant pickupAt, Instant dropoffAt, Point pickup, Point dropoff) {
	}

	/**
	 * @param buffer free time between arriving at a pickup and its pickup time
	 * @param fairnessMinutes added to a driver's score per trip they already have that day
	 */
	record Rules(Duration buffer, int fairnessMinutes) {
	}

	record DayProblem(List<TripToPlan> trips, List<Driver> drivers, List<Commitment> commitments, Rules rules) {
	}

	/** @param travel from the previous drop-off (or the day's start) to this pickup */
	record Choice(UUID driverId, String name, Duration travel, double score) {
	}

	/** @param unplannedReason when there is no primary: NO_DRIVER_WITH_CLASS, NO_SHIFT or NO_FREE_DRIVER */
	record Suggestion(UUID tripId, Choice primary, Choice backup, String unplannedReason) {
	}

	record DayPlan(List<Suggestion> suggestions) {
	}

	DayPlan plan(DayProblem problem);
}

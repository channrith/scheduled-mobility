package com.mobility.core.dispatch.suggest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.dispatch.risk.TravelEstimator;
import org.springframework.stereotype.Component;

/**
 * Simple rules, trips in pickup order: a driver qualifies with the trip's vehicle class, a shift covering pickup to
 * drop-off, and time to get there from their previous drop-off (or the day's start) plus the buffer, and to the next
 * trip after it. The lowest score wins: travel minutes + a fairness penalty per trip already planned for them that
 * day. The backup is the next-best qualifying driver (backups do not take up a driver's time).
 */
@Component
public class GreedyAssignmentStrategy implements AssignmentStrategy {

	private final TravelEstimator travel;

	public GreedyAssignmentStrategy(TravelEstimator travel) {
		this.travel = travel;
	}

	/** A driver's day: trips they hold or are planned for, by pickup. */
	private record Slot(Instant pickupAt, Instant dropoffAt, Point pickup, Point dropoff) {
	}

	private record Candidate(Driver driver, Duration travel, double score) {
	}

	@Override
	public DayPlan plan(DayProblem problem) {
		Map<UUID, List<Slot>> days = new HashMap<>();
		for (Driver d : problem.drivers()) {
			days.put(d.driverId(), new ArrayList<>());
		}
		for (Commitment c : problem.commitments()) {
			days.computeIfAbsent(c.driverId(), k -> new ArrayList<>()).add(new Slot(c.pickupAt(), c.dropoffAt(), c.pickup(), c.dropoff()));
		}
		List<Suggestion> suggestions = new ArrayList<>();
		for (TripToPlan trip : problem.trips().stream().sorted(Comparator.comparing(TripToPlan::pickupAt)).toList()) {
			List<Driver> ofClass = problem.drivers().stream().filter(d -> d.vehicleClass() == trip.vehicleClass()).toList();
			List<Driver> onShift = ofClass.stream().filter(d -> covered(d, trip)).toList();
			List<Candidate> candidates = onShift.stream()
				.map(d -> candidate(d, days.get(d.driverId()), trip, problem.rules()))
				.flatMap(Optional::stream)
				.sorted(Comparator.comparingDouble(Candidate::score).thenComparing(c -> c.driver().driverId()))
				.toList();
			if (candidates.isEmpty()) {
				String why = ofClass.isEmpty() ? "NO_DRIVER_WITH_CLASS" : onShift.isEmpty() ? "NO_SHIFT" : "NO_FREE_DRIVER";
				suggestions.add(new Suggestion(trip.tripId(), null, null, why));
				continue;
			}
			Candidate primary = candidates.getFirst();
			Candidate backup = candidates.size() > 1 ? candidates.get(1) : null;
			days.get(primary.driver().driverId()).add(new Slot(trip.pickupAt(), trip.dropoffAt(), trip.pickup(), trip.dropoff()));
			suggestions.add(new Suggestion(trip.tripId(), choice(primary), backup == null ? null : choice(backup), null));
		}
		return new DayPlan(suggestions);
	}

	private static boolean covered(Driver d, TripToPlan trip) {
		return d.shifts().stream().anyMatch(w -> !w.from().isAfter(trip.pickupAt()) && !w.to().isBefore(trip.dropoffAt()));
	}

	/** Fits between the driver's previous and next slots, or empty. */
	private Optional<Candidate> candidate(Driver driver, List<Slot> day, TripToPlan trip, Rules rules) {
		Slot previous = null;
		Slot next = null;
		for (Slot s : day) {
			boolean overlaps = s.pickupAt().isBefore(trip.dropoffAt()) && s.dropoffAt().isAfter(trip.pickupAt());
			if (overlaps) {
				return Optional.empty();
			}
			if (!s.dropoffAt().isAfter(trip.pickupAt()) && (previous == null || s.dropoffAt().isAfter(previous.dropoffAt()))) {
				previous = s;
			}
			if (!s.pickupAt().isBefore(trip.dropoffAt()) && (next == null || s.pickupAt().isBefore(next.pickupAt()))) {
				next = s;
			}
		}
		Point from = previous == null ? driver.start() : previous.dropoff();
		Duration toPickup = between(from, trip.pickup());
		if (previous != null && previous.dropoffAt().plus(toPickup).plus(rules.buffer()).isAfter(trip.pickupAt())) {
			return Optional.empty();
		}
		if (next != null && trip.dropoffAt().plus(between(trip.dropoff(), next.pickup())).plus(rules.buffer()).isAfter(next.pickupAt())) {
			return Optional.empty();
		}
		double score = toPickup.toSeconds() / 60.0 + (double) rules.fairnessMinutes() * day.size();
		return Optional.of(new Candidate(driver, toPickup, score));
	}

	private Duration between(Point a, Point b) {
		return travel.between(a.latitude(), a.longitude(), b.latitude(), b.longitude());
	}

	private static Choice choice(Candidate c) {
		return new Choice(c.driver().driverId(), c.driver().name(), c.travel(), Math.round(c.score() * 10) / 10.0);
	}
}

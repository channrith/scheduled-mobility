package com.mobility.core.dispatch.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.RiskTier;
import com.mobility.core.dispatch.assignment.TripAssignment;
import com.mobility.core.dispatch.assignment.TripAssignmentRepository;
import com.mobility.core.dispatch.issue.ExceptionService;
import com.mobility.core.dispatch.issue.ExceptionType;
import com.mobility.core.dispatch.issue.Severity;
import com.mobility.core.dispatch.risk.DriverPositions.Position;
import com.mobility.core.dispatch.risk.SlackTiers.Evaluation;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.driver.DriverDirectory.DriverInfo;
import com.mobility.core.shared.time.StoredTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tiers every assigned trip picking up within the window: slack = time to pickup − the driver's ETA from their latest
 * position. AMBER opens (or updates) AT_RISK at HIGH, RED at CRITICAL, GREEN closes it.
 */
@Component
public class RiskEvaluator {

	public record Outcome(UUID tripId, Evaluation evaluation) {
	}

	private final Trips trips;

	private final TripAssignmentRepository assignments;

	private final DriverDirectory drivers;

	private final DriverPositions positions;

	private final TravelEstimator travel;

	private final ExceptionService exceptions;

	private final DispatchProperties properties;

	private final Clock clock;

	RiskEvaluator(Trips trips, TripAssignmentRepository assignments, DriverDirectory drivers, DriverPositions positions,
			TravelEstimator travel, ExceptionService exceptions, DispatchProperties properties, Clock clock) {
		this.trips = trips;
		this.assignments = assignments;
		this.drivers = drivers;
		this.positions = positions;
		this.travel = travel;
		this.exceptions = exceptions;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public List<Outcome> evaluate() {
		Instant now = StoredTime.of(clock.instant());
		List<TripInfo> near = trips.pickingUpBetween(now, now.plus(properties.risk().window()),
				List.of(TripStatus.ASSIGNED, TripStatus.DRIVER_EN_ROUTE));
		Map<UUID, DriverInfo> byDriver = drivers.getAll(near.stream().map(TripInfo::driverId).distinct().toList()).stream()
			.collect(Collectors.toMap(DriverInfo::driverId, Function.identity()));
		Map<UUID, TripAssignment> byTrip = new HashMap<>();
		assignments.findByTripIdIn(near.stream().map(TripInfo::tripId).toList()).forEach(a -> byTrip.put(a.getTripId(), a));
		return near.stream().map(trip -> {
			DriverInfo driver = byDriver.get(trip.driverId());
			Optional<Position> at = driver == null ? Optional.empty() : positions.latest(driver.userId());
			Duration eta = at.map(p -> travel.between(p.latitude(), p.longitude(), trip.pickupLat(), trip.pickupLng()))
				.orElse(Duration.ZERO);
			Evaluation e = SlackTiers.evaluate(now, trip.pickupAt(), at.map(Position::recordedAt).orElse(null), eta,
					properties.risk());
			TripAssignment a = byTrip.computeIfAbsent(trip.tripId(), id -> TripAssignment.forTrip(id, now));
			a.risk(e.tier(), e.slack(), e.reason().name(), now);
			assignments.save(a);
			if (e.tier() == RiskTier.GREEN) {
				exceptions.autoResolve(trip.tripId(), ExceptionType.AT_RISK);
			}
			else {
				Map<String, Object> details = new HashMap<>();
				details.put("tier", e.tier().name());
				details.put("reason", e.reason().name());
				if (e.slack() != null) {
					details.put("slackSeconds", e.slack().toSeconds());
				}
				exceptions.raise(ExceptionType.AT_RISK, trip.tripId(), trip.driverId(),
						e.tier() == RiskTier.RED ? Severity.CRITICAL : Severity.HIGH, trip.pickupAt(), details);
			}
			return new Outcome(trip.tripId(), e);
		}).toList();
	}
}

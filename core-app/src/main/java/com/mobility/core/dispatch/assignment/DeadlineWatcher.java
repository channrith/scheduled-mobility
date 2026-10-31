package com.mobility.core.dispatch.assignment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.issue.ExceptionService;
import com.mobility.core.dispatch.issue.ExceptionType;
import com.mobility.core.dispatch.issue.Severity;
import com.mobility.core.shared.time.StoredTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exceptions that come from time passing: unconfirmed drivers past their deadline (UNCONFIRMED, BACKUP_UNCONFIRMED),
 * trips without a driver 2 hours before pickup (UNASSIGNED) and airport/corporate rides without a backup then
 * (NO_BACKUP).
 */
@Component
public class DeadlineWatcher {

	private final TripAssignmentRepository assignments;

	private final Trips trips;

	private final ExceptionService exceptions;

	private final DispatchProperties properties;

	private final Clock clock;

	DeadlineWatcher(TripAssignmentRepository assignments, Trips trips, ExceptionService exceptions, DispatchProperties properties,
			Clock clock) {
		this.assignments = assignments;
		this.trips = trips;
		this.exceptions = exceptions;
		this.properties = properties;
		this.clock = clock;
	}

	/** @return how many exceptions were raised or refreshed */
	@Transactional
	public int check() {
		Instant now = StoredTime.of(clock.instant());
		int raised = 0;
		List<TripAssignment> late = assignments.unconfirmedBy(now);
		Map<UUID, TripInfo> byTrip = trips.findAll(late.stream().map(TripAssignment::getTripId).toList()).stream()
			.collect(java.util.stream.Collectors.toMap(TripInfo::tripId, t -> t));
		for (TripAssignment a : late) {
			TripInfo trip = byTrip.get(a.getTripId());
			if (trip == null || !(trip.status() == TripStatus.ASSIGNED || trip.status() == TripStatus.DRIVER_EN_ROUTE)) {
				continue;
			}
			if (ConfirmationDeadline.missed(a.getPrimaryDeadline(), a.getPrimaryConfirmedAt(), now)
					&& a.getPrimaryDriverId() != null) {
				exceptions.raise(ExceptionType.UNCONFIRMED, trip.tripId(), a.getPrimaryDriverId(), Severity.HIGH, trip.pickupAt(),
						Map.of("deadline", a.getPrimaryDeadline().toString()));
				raised++;
			}
			if (ConfirmationDeadline.missed(a.getBackupDeadline(), a.getBackupConfirmedAt(), now) && a.getBackupDriverId() != null) {
				exceptions.raise(ExceptionType.BACKUP_UNCONFIRMED, trip.tripId(), a.getBackupDriverId(), Severity.MEDIUM,
						trip.pickupAt(), Map.of("deadline", a.getBackupDeadline().toString()));
				raised++;
			}
		}
		List<TripInfo> soon = trips.pickingUpBetween(now, now.plus(properties.unassignedLeadTime()),
				List.of(TripStatus.SCHEDULED, TripStatus.ASSIGNED, TripStatus.DRIVER_EN_ROUTE));
		Map<UUID, TripAssignment> soonAssignments = new java.util.HashMap<>();
		assignments.findByTripIdIn(soon.stream().map(TripInfo::tripId).toList()).forEach(x -> soonAssignments.put(x.getTripId(), x));
		for (TripInfo trip : soon) {
			if (trip.status() == TripStatus.SCHEDULED) {
				exceptions.raise(ExceptionType.UNASSIGNED, trip.tripId(), null, Severity.HIGH, trip.pickupAt(), Map.of());
				raised++;
			}
			else if (trip.slaTrip()) {
				TripAssignment x = soonAssignments.get(trip.tripId());
				if (x == null || x.getBackupDriverId() == null) {
					exceptions.raise(ExceptionType.NO_BACKUP, trip.tripId(), null, Severity.MEDIUM, trip.pickupAt(), Map.of());
					raised++;
				}
			}
		}
		return raised;
	}
}

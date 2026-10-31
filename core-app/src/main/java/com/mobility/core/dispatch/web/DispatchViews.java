package com.mobility.core.dispatch.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.RiskTier;
import com.mobility.core.dispatch.assignment.AssignmentHistoryEntry;
import com.mobility.core.dispatch.assignment.AssignmentService.Warning;
import com.mobility.core.dispatch.assignment.ReasonCode;
import com.mobility.core.dispatch.assignment.TripAssignment;
import com.mobility.core.dispatch.issue.ExceptionCase;
import com.mobility.core.dispatch.issue.ExceptionStatus;
import com.mobility.core.dispatch.issue.ExceptionType;
import com.mobility.core.dispatch.issue.Severity;
import com.mobility.core.dispatch.shift.ShiftBlock;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Choice;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Suggestion;
import com.mobility.core.driver.VehicleClass;

/** API shapes of the dispatch module. */
final class DispatchViews {

	private DispatchViews() {
	}

	record ShiftView(UUID id, UUID driverId, Instant startsAt, Instant endsAt, String status, Long version) {
	}

	/**
	 * @param version send back to change the backup or switch to it
	 * @param riskTier null until evaluated (trips more than 90 minutes away)
	 */
	record DispatchAssignmentView(UUID primaryDriverId, Instant primaryDeadline, Instant primaryConfirmedAt, UUID backupDriverId,
			Instant backupDeadline, Instant backupConfirmedAt, ReasonCode reasonCode, String note, UUID assignedBy,
			Instant assignedAt, RiskTier riskTier, Long slackSeconds, String riskReason, Instant riskEvaluatedAt, Long version) {
	}

	record DispatchPointView(double latitude, double longitude) {
	}

	/**
	 * @param tripVersion send back to assign, reassign or unassign
	 * @param slaTrip airport or corporate ride: needs a backup
	 */
	record DispatchTripView(UUID tripId, UUID rideId, BookingType type, TripStatus status, Instant pickupAt, Instant dropoffAt,
			VehicleClass vehicleClass, DispatchPointView pickup, DispatchPointView dropoff, UUID corporateId, String flightNo,
			boolean slaTrip, UUID driverId, long tripVersion, DispatchAssignmentView assignment) {
	}

	/** @param code NO_SHIFT (no shift block covers the trip) or OVERLAP (another trip of the driver at that time) */
	record WarningView(UUID driverId, String role, String code, UUID otherTripId) {
	}

	record AssignmentResultView(DispatchTripView trip, List<WarningView> warnings) {
	}

	record AssignmentHistoryView(String action, String role, UUID driverId, UUID previousDriverId, ReasonCode reasonCode,
			String note, UUID actorUserId, Instant occurredAt) {
	}

	record DispatchTripDetailView(DispatchTripView trip, List<WarningView> warnings, List<AssignmentHistoryView> history) {
	}

	/** @param role PRIMARY or BACKUP: the driver's part in this trip */
	record LaneTripView(String role, DispatchTripView trip) {
	}

	record DriverLaneView(UUID driverId, String name, VehicleClass vehicleClass, List<ShiftView> shifts, List<LaneTripView> trips) {
	}

	/** @param unassigned open trips without a driver, by pickup */
	record TimelineView(LocalDate date, List<DriverLaneView> lanes, List<DispatchTripView> unassigned) {
	}

	record ChoiceView(UUID driverId, String name, long travelMinutes, double score) {
	}

	record SuggestionView(UUID tripId, ChoiceView primary, ChoiceView backup, String unplannedReason) {
	}

	record DaySuggestionsView(LocalDate date, List<SuggestionView> suggestions) {
	}

	record AppliedView(UUID tripId, String error) {
	}

	record ExceptionView(UUID id, ExceptionType type, UUID tripId, UUID driverId, Severity severity, ExceptionStatus status,
			Instant pickupAt, UUID assignedDispatcherId, Map<String, Object> details, Instant openedAt, Instant updatedAt,
			Instant resolvedAt, String resolutionNote, Long version) {
	}

	static ShiftView shift(ShiftBlock s) {
		return new ShiftView(s.getId(), s.getDriverId(), s.getStartsAt(), s.getEndsAt(), s.getStatus(), s.getVersion());
	}

	static DispatchAssignmentView assignment(TripAssignment a) {
		return a == null ? null
				: new DispatchAssignmentView(a.getPrimaryDriverId(), a.getPrimaryDeadline(), a.getPrimaryConfirmedAt(),
						a.getBackupDriverId(), a.getBackupDeadline(), a.getBackupConfirmedAt(), a.getReasonCode(), a.getNote(),
						a.getAssignedBy(), a.getAssignedAt(), a.getRiskTier(), a.getSlackSeconds(), a.getRiskReason(),
						a.getRiskEvaluatedAt(), a.getVersion());
	}

	static DispatchTripView trip(TripInfo t, TripAssignment a) {
		return new DispatchTripView(t.tripId(), t.rideId(), t.type(), t.status(), t.pickupAt(), t.dropoffAt(), t.vehicleClass(),
				new DispatchPointView(t.pickupLat(), t.pickupLng()), new DispatchPointView(t.dropoffLat(), t.dropoffLng()),
				t.corporateId(), t.flightNo(), t.slaTrip(), t.driverId(), t.version(), assignment(a));
	}

	static WarningView warning(Warning w) {
		return new WarningView(w.driverId(), w.role().name(), w.code(), w.otherTripId());
	}

	static AssignmentHistoryView history(AssignmentHistoryEntry h) {
		return new AssignmentHistoryView(h.getAction().name(), h.getRole() == null ? null : h.getRole().name(), h.getDriverId(),
				h.getPreviousDriverId(), h.getReasonCode(), h.getNote(), h.getActorUserId(), h.getOccurredAt());
	}

	static SuggestionView suggestion(Suggestion s) {
		return new SuggestionView(s.tripId(), choice(s.primary()), choice(s.backup()), s.unplannedReason());
	}

	static ChoiceView choice(Choice c) {
		return c == null ? null : new ChoiceView(c.driverId(), c.name(), Math.round(c.travel().toSeconds() / 60.0), c.score());
	}

	static ExceptionView exception(ExceptionCase e) {
		return new ExceptionView(e.getId(), e.getType(), e.getTripId(), e.getDriverId(), e.getSeverity(), e.getStatus(),
				e.getPickupAt(), e.getAssignedDispatcherId(), e.getDetails(), e.getOpenedAt(), e.getUpdatedAt(), e.getResolvedAt(),
				e.getResolutionNote(), e.getVersion());
	}
}

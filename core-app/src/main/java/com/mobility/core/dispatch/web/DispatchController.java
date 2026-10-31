package com.mobility.core.dispatch.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.assignment.AssignmentService;
import com.mobility.core.dispatch.assignment.AssignmentService.Result;
import com.mobility.core.dispatch.assignment.ReasonCode;
import com.mobility.core.dispatch.assignment.TripAssignment;
import com.mobility.core.dispatch.assignment.TripAssignmentRepository;
import com.mobility.core.dispatch.shift.ShiftBlock;
import com.mobility.core.dispatch.shift.ShiftService;
import com.mobility.core.dispatch.suggest.SuggestionService;
import com.mobility.core.dispatch.suggest.SuggestionService.Accept;
import com.mobility.core.dispatch.web.DispatchViews.AppliedView;
import com.mobility.core.dispatch.web.DispatchViews.AssignmentResultView;
import com.mobility.core.dispatch.web.DispatchViews.DaySuggestionsView;
import com.mobility.core.dispatch.web.DispatchViews.DispatchTripDetailView;
import com.mobility.core.dispatch.web.DispatchViews.DispatchTripView;
import com.mobility.core.dispatch.web.DispatchViews.DriverLaneView;
import com.mobility.core.dispatch.web.DispatchViews.LaneTripView;
import com.mobility.core.dispatch.web.DispatchViews.ShiftView;
import com.mobility.core.dispatch.web.DispatchViews.TimelineView;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.driver.DriverDirectory.DriverInfo;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch")
@PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
@Tag(name = "Dispatch", description = "Dispatcher console: day timeline, suggestions, assigning drivers and backups")
class DispatchController {

	private final AssignmentService assignments;

	private final TripAssignmentRepository assignmentRepository;

	private final ShiftService shifts;

	private final SuggestionService suggestions;

	private final Trips trips;

	private final DriverDirectory drivers;

	private final CurrentUserProvider currentUser;

	DispatchController(AssignmentService assignments, TripAssignmentRepository assignmentRepository, ShiftService shifts,
			SuggestionService suggestions, Trips trips, DriverDirectory drivers, CurrentUserProvider currentUser) {
		this.assignments = assignments;
		this.assignmentRepository = assignmentRepository;
		this.shifts = shifts;
		this.suggestions = suggestions;
		this.trips = trips;
		this.drivers = drivers;
		this.currentUser = currentUser;
	}

	/** @param tripVersion the trip's version as you saw it */
	record AssignTripRequest(@NotNull UUID driverId, UUID backupDriverId, @NotNull ReasonCode reasonCode, String note,
			@NotNull Long tripVersion) {
	}

	/** @param backupDriverId null clears the backup (not allowed for airport and corporate rides) */
	record SetBackupRequest(UUID backupDriverId, @NotNull ReasonCode reasonCode, String note, @NotNull Long assignmentVersion) {
	}

	record UnassignTripRequest(@NotNull ReasonCode reasonCode, String note, @NotNull Long tripVersion) {
	}

	record SwitchToBackupRequest(@NotNull Long assignmentVersion, String note) {
	}

	record AcceptItem(@NotNull UUID tripId, @NotNull UUID driverId, UUID backupDriverId, @NotNull Long tripVersion) {
	}

	record AcceptSuggestionsRequest(@NotEmpty List<@Valid AcceptItem> items) {
	}

	@Operation(summary = "Shift blocks of a day", description = "`date` in Phnom Penh time (YYYY-MM-DD).")
	@GetMapping("/shifts")
	List<ShiftView> shifts(@RequestParam LocalDate date) {
		return shifts.between(start(date), start(date.plusDays(1))).stream().map(DispatchViews::shift).toList();
	}

	@Operation(summary = "Day timeline by driver",
			description = "Each driver with shift blocks or trips that day: their blocks and trips (as primary or backup, with "
					+ "status, risk tier and confirmations), plus the open trips without a driver.")
	@GetMapping("/timeline")
	TimelineView timeline(@RequestParam LocalDate date) {
		Instant from = start(date);
		Instant to = start(date.plusDays(1));
		List<TripInfo> day = trips.pickingUpBetween(from, to, List.of(TripStatus.values()));
		Map<UUID, TripAssignment> byTrip = new HashMap<>();
		assignmentRepository.findByTripIdIn(day.stream().map(TripInfo::tripId).toList()).forEach(a -> byTrip.put(a.getTripId(), a));
		List<ShiftBlock> blocks = shifts.between(from, to);
		Set<UUID> driverIds = new HashSet<>();
		blocks.forEach(b -> driverIds.add(b.getDriverId()));
		day.forEach(t -> {
			if (t.driverId() != null) {
				driverIds.add(t.driverId());
			}
			TripAssignment a = byTrip.get(t.tripId());
			if (a != null && a.getBackupDriverId() != null && t.status().isOpen()) {
				driverIds.add(a.getBackupDriverId());
			}
		});
		Map<UUID, DriverInfo> info = new LinkedHashMap<>();
		drivers.getAll(driverIds).stream().sorted(Comparator.comparing(d -> d.fullName() == null ? "" : d.fullName()))
			.forEach(d -> info.put(d.driverId(), d));
		List<DriverLaneView> lanes = new ArrayList<>();
		for (DriverInfo d : info.values()) {
			List<LaneTripView> laneTrips = new ArrayList<>();
			for (TripInfo t : day) {
				TripAssignment a = byTrip.get(t.tripId());
				if (d.driverId().equals(t.driverId())) {
					laneTrips.add(new LaneTripView("PRIMARY", DispatchViews.trip(t, a)));
				}
				else if (a != null && d.driverId().equals(a.getBackupDriverId()) && t.status().isOpen()) {
					laneTrips.add(new LaneTripView("BACKUP", DispatchViews.trip(t, a)));
				}
			}
			lanes.add(new DriverLaneView(d.driverId(), d.fullName(), d.vehicle() == null ? null : d.vehicle().vehicleClass(),
					blocks.stream().filter(b -> b.getDriverId().equals(d.driverId())).map(DispatchViews::shift).toList(), laneTrips));
		}
		List<DispatchTripView> unassigned = day.stream().filter(t -> t.status() == TripStatus.SCHEDULED)
			.map(t -> DispatchViews.trip(t, byTrip.get(t.tripId())))
			.toList();
		return new TimelineView(date, lanes, unassigned);
	}

	@Operation(summary = "A trip as dispatch sees it", description = "With warnings and the assignment history.")
	@GetMapping("/trips/{tripId}")
	DispatchTripDetailView trip(@PathVariable UUID tripId) {
		TripInfo t = trips.find(tripId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.trip-not-found"));
		TripAssignment a = assignmentRepository.findById(tripId).orElse(null);
		return new DispatchTripDetailView(DispatchViews.trip(t, a),
				a == null ? List.of() : assignments.warnings(t, a).stream().map(DispatchViews::warning).toList(),
				assignments.history(tripId).stream().map(DispatchViews::history).toList());
	}

	@Operation(summary = "Suggested drivers for a day",
			description = "A primary and a backup for every trip still without a driver, by simple rules: vehicle class, a "
					+ "shift covering the trip, time to get there from the previous drop-off (or home zone), spread fairly.")
	@GetMapping("/suggestions")
	DaySuggestionsView suggestions(@RequestParam LocalDate date) {
		return new DaySuggestionsView(date,
				suggestions.forDay(date).suggestions().stream().map(DispatchViews::suggestion).toList());
	}

	@Operation(summary = "Accept suggestions",
			description = "Assigns each item (reason SUGGESTION_ACCEPTED) on its own: one conflict does not undo the others.")
	@PostMapping("/suggestions/accept")
	List<AppliedView> accept(@Valid @RequestBody AcceptSuggestionsRequest request) {
		return suggestions.accept(request.items().stream()
			.map(i -> new Accept(i.tripId(), i.driverId(), i.backupDriverId(), i.tripVersion()))
			.toList(), me()).stream().map(a -> new AppliedView(a.tripId(), a.error())).toList();
	}

	@Operation(summary = "Assign or reassign the driver (and backup)",
			description = "Needs a `reasonCode` (OTHER also a `note`) and the trip version you saw. Airport and corporate rides "
					+ "need a `backupDriverId`. Returns warnings (no shift, overlapping trip) that do not block.")
	@PostMapping("/trips/{tripId}/assign")
	AssignmentResultView assign(@PathVariable UUID tripId, @Valid @RequestBody AssignTripRequest request) {
		return result(assignments.assign(tripId, request.driverId(), request.backupDriverId(), request.reasonCode(),
				request.note(), request.tripVersion(), me()));
	}

	@Operation(summary = "Set, change or clear the backup", description = "Needs the assignment version you saw.")
	@PutMapping("/trips/{tripId}/backup")
	AssignmentResultView backup(@PathVariable UUID tripId, @Valid @RequestBody SetBackupRequest request) {
		return result(assignments.setBackup(tripId, request.backupDriverId(), request.reasonCode(), request.note(),
				request.assignmentVersion(), me()));
	}

	@Operation(summary = "Take the driver off (→ SCHEDULED)")
	@PostMapping("/trips/{tripId}/unassign")
	AssignmentResultView unassign(@PathVariable UUID tripId, @Valid @RequestBody UnassignTripRequest request) {
		return result(assignments.unassign(tripId, request.reasonCode(), request.note(), request.tripVersion(), me()));
	}

	@Operation(summary = "Switch to the backup (one click)",
			description = "The backup becomes the driver; needs the assignment version you saw, so two clicks from the same "
					+ "view give one success and one 409.")
	@PostMapping("/trips/{tripId}/switch-to-backup")
	AssignmentResultView switchToBackup(@PathVariable UUID tripId, @Valid @RequestBody SwitchToBackupRequest request) {
		return result(assignments.switchToBackup(tripId, request.assignmentVersion(), request.note(), me()));
	}

	private static AssignmentResultView result(Result r) {
		return new AssignmentResultView(DispatchViews.trip(r.trip(), r.assignment()),
				r.warnings().stream().map(DispatchViews::warning).toList());
	}

	private UUID me() {
		return currentUser.require().userId();
	}

	private static Instant start(LocalDate date) {
		return date.atStartOfDay(BusinessTime.ZONE).toInstant();
	}
}

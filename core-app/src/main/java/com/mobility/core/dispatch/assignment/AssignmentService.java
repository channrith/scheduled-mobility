package com.mobility.core.dispatch.assignment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.DispatchEvents.BackupSwitched;
import com.mobility.core.dispatch.DispatchEvents.DriverAssignmentConfirmed;
import com.mobility.core.dispatch.DispatchEvents.DriverAssignmentDeclined;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.assignment.AssignmentHistoryEntry.Action;
import com.mobility.core.dispatch.assignment.AssignmentHistoryEntry.Role;
import com.mobility.core.dispatch.issue.ExceptionService;
import com.mobility.core.dispatch.issue.ExceptionType;
import com.mobility.core.dispatch.issue.Severity;
import com.mobility.core.dispatch.shift.ShiftBlock;
import com.mobility.core.dispatch.shift.ShiftBlockRepository;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.driver.DriverDirectory.DriverInfo;
import com.mobility.core.driver.DriverStatus;
import com.mobility.core.shared.outbox.Outbox;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual assignment of a trip's primary and backup driver. Every change needs a reason code, is audit-logged and
 * written to the assignment history; versions on the trip (booking) and on the assignment make concurrent changes
 * from the same view conflict (409).
 */
@Service
public class AssignmentService {

	/** Something worth a second look, not a refusal. */
	public record Warning(UUID driverId, Role role, String code, UUID otherTripId) {
	}

	public record Result(TripAssignment assignment, TripInfo trip, List<Warning> warnings) {
	}

	static final int MIN_OTHER_NOTE = 5;

	private final TripAssignmentRepository assignments;

	private final AssignmentHistoryRepository history;

	private final ShiftBlockRepository shifts;

	private final Trips trips;

	private final DriverDirectory drivers;

	private final ExceptionService exceptions;

	private final DispatchProperties properties;

	private final Outbox outbox;

	private final AuditLog auditLog;

	private final Clock clock;

	AssignmentService(TripAssignmentRepository assignments, AssignmentHistoryRepository history, ShiftBlockRepository shifts,
			Trips trips, DriverDirectory drivers, ExceptionService exceptions, DispatchProperties properties, Outbox outbox,
			AuditLog auditLog, Clock clock) {
		this.assignments = assignments;
		this.history = history;
		this.shifts = shifts;
		this.trips = trips;
		this.drivers = drivers;
		this.exceptions = exceptions;
		this.properties = properties;
		this.outbox = outbox;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * Assigns (or reassigns) the primary driver and sets the backup. Airport and corporate rides need a backup.
	 * {@code tripVersion} = the trip version the dispatcher saw.
	 */
	@Transactional
	public Result assign(UUID tripId, UUID driverId, UUID backupDriverId, ReasonCode reason, String note, long tripVersion,
			UUID actorUserId) {
		String n = reasonNote(reason, note);
		TripInfo trip = requireTrip(tripId);
		if (backupDriverId != null && backupDriverId.equals(driverId)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "dispatch.backup-same-as-primary");
		}
		if (trip.slaTrip() && backupDriverId == null) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "dispatch.backup-required");
		}
		if (backupDriverId != null) {
			checkDriver(backupDriverId, trip, "backup");
		}
		Instant now = now();
		TripAssignment a = assignments.findById(tripId).orElseGet(() -> new TripAssignment(tripId, now));
		UUID previous = trip.driverId();
		TripInfo assigned = trips.assign(tripId, driverId, tripVersion, actorUserId, reason + (n == null ? "" : ": " + n));
		a.setPrimary(driverId, ConfirmationDeadline.of(trip.pickupAt(), now, properties.confirmation()), null, reason, n,
				actorUserId, now);
		if (!Objects.equals(a.getBackupDriverId(), backupDriverId)) {
			UUID oldBackup = a.getBackupDriverId();
			a.setBackup(backupDriverId, ConfirmationDeadline.of(trip.pickupAt(), now, properties.confirmation()), null, now);
			record(tripId, backupDriverId == null ? Action.BACKUP_CLEARED : Action.BACKUP_SET, Role.BACKUP, backupDriverId,
					oldBackup, reason, n, actorUserId, now);
		}
		TripAssignment saved = assignments.saveAndFlush(a);
		record(tripId, previous == null ? Action.ASSIGNED : Action.REASSIGNED, Role.PRIMARY, driverId, previous, reason, n,
				actorUserId, now);
		exceptions.autoResolve(tripId, ExceptionType.UNASSIGNED, ExceptionType.UNCONFIRMED, ExceptionType.DRIVER_DECLINED,
				ExceptionType.AT_RISK);
		if (backupDriverId != null) {
			exceptions.autoResolve(tripId, ExceptionType.NO_BACKUP);
		}
		audit(previous == null ? "dispatch.trip.assigned" : "dispatch.trip.reassigned", tripId, reason,
				Map.of("driverId", driverId.toString(), "backupDriverId", String.valueOf(backupDriverId)));
		return new Result(saved, assigned, warnings(assigned, saved));
	}

	/** Sets, changes or clears the backup. {@code assignmentVersion} = the assignment version the dispatcher saw. */
	@Transactional
	public Result setBackup(UUID tripId, UUID backupDriverId, ReasonCode reason, String note, long assignmentVersion,
			UUID actorUserId) {
		String n = reasonNote(reason, note);
		TripInfo trip = requireTrip(tripId);
		TripAssignment a = requireAssignment(tripId, assignmentVersion);
		if (backupDriverId != null) {
			if (backupDriverId.equals(a.getPrimaryDriverId())) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "dispatch.backup-same-as-primary");
			}
			checkDriver(backupDriverId, trip, "backup");
		}
		else if (trip.slaTrip()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "dispatch.backup-required");
		}
		Instant now = now();
		UUID old = a.getBackupDriverId();
		a.setBackup(backupDriverId, ConfirmationDeadline.of(trip.pickupAt(), now, properties.confirmation()), null, now);
		TripAssignment saved = assignments.saveAndFlush(a);
		record(tripId, backupDriverId == null ? Action.BACKUP_CLEARED : Action.BACKUP_SET, Role.BACKUP, backupDriverId, old,
				reason, n, actorUserId, now);
		exceptions.autoResolve(tripId, ExceptionType.BACKUP_UNCONFIRMED);
		if (backupDriverId != null) {
			exceptions.autoResolve(tripId, ExceptionType.NO_BACKUP);
		}
		audit("dispatch.trip.backup-changed", tripId, reason, Map.of("backupDriverId", String.valueOf(backupDriverId)));
		return new Result(saved, trip, warnings(trip, saved));
	}

	@Transactional
	public Result unassign(UUID tripId, ReasonCode reason, String note, long tripVersion, UUID actorUserId) {
		String n = reasonNote(reason, note);
		TripInfo trip = requireTrip(tripId);
		Instant now = now();
		TripInfo after = trips.unassign(tripId, tripVersion, actorUserId, reason + (n == null ? "" : ": " + n), false);
		TripAssignment a = assignments.findById(tripId).orElseGet(() -> new TripAssignment(tripId, now));
		a.setPrimary(null, null, null, reason, n, actorUserId, now);
		TripAssignment saved = assignments.saveAndFlush(a);
		record(tripId, Action.UNASSIGNED, Role.PRIMARY, null, trip.driverId(), reason, n, actorUserId, now);
		exceptions.autoResolve(tripId, ExceptionType.UNCONFIRMED, ExceptionType.AT_RISK);
		audit("dispatch.trip.unassigned", tripId, reason, Map.of());
		return new Result(saved, after, List.of());
	}

	/**
	 * One click: the backup becomes the primary driver and the backup slot is emptied. {@code assignmentVersion} = the
	 * version the dispatcher saw, so two clicks from the same view give one success and one 409.
	 */
	@Transactional
	public Result switchToBackup(UUID tripId, long assignmentVersion, String note, UUID actorUserId) {
		TripAssignment a = requireAssignment(tripId, assignmentVersion);
		UUID backup = a.getBackupDriverId();
		if (backup == null) {
			throw new ApiException(HttpStatus.CONFLICT, "dispatch.no-backup");
		}
		TripInfo trip = requireTrip(tripId);
		String n = note == null || note.isBlank() ? null : note.strip();
		Instant now = now();
		UUID previous = trip.driverId();
		TripInfo assigned = trips.assign(tripId, backup, trip.version(), actorUserId,
				ReasonCode.BACKUP_SWITCH + (n == null ? "" : ": " + n));
		Instant confirmed = a.getBackupConfirmedAt();
		a.setPrimary(backup, ConfirmationDeadline.of(trip.pickupAt(), now, properties.confirmation()), confirmed,
				ReasonCode.BACKUP_SWITCH, n, actorUserId, now);
		a.setBackup(null, null, null, now);
		TripAssignment saved = assignments.saveAndFlush(a);
		record(tripId, Action.SWITCHED_TO_BACKUP, Role.PRIMARY, backup, previous, ReasonCode.BACKUP_SWITCH, n, actorUserId, now);
		exceptions.autoResolve(tripId, ExceptionType.UNCONFIRMED, ExceptionType.BACKUP_UNCONFIRMED, ExceptionType.AT_RISK,
				ExceptionType.DRIVER_DECLINED, ExceptionType.UNASSIGNED);
		if (assigned.slaTrip()) {
			exceptions.raise(ExceptionType.NO_BACKUP, tripId, null, Severity.MEDIUM, trip.pickupAt(), Map.of("after", "BACKUP_SWITCH"));
		}
		outbox.publish("dispatch.backup-switched", "trip", tripId, new BackupSwitched(tripId, previous, backup, now));
		audit("dispatch.trip.backup-switched", tripId, ReasonCode.BACKUP_SWITCH,
				Map.of("previousDriverId", String.valueOf(previous), "driverId", backup.toString()));
		return new Result(saved, assigned, warnings(assigned, saved));
	}

	/** The assigned driver (primary or backup) confirms they will do it. */
	@Transactional
	public TripAssignment confirm(UUID tripId, UUID driverUserId) {
		DriverInfo driver = driverOfUser(driverUserId);
		TripAssignment a = assignmentOf(tripId, driver.driverId());
		boolean primary = driver.driverId().equals(a.getPrimaryDriverId());
		Instant now = now();
		a.confirm(primary, now);
		TripAssignment saved = assignments.saveAndFlush(a);
		record(tripId, Action.CONFIRMED, primary ? Role.PRIMARY : Role.BACKUP, driver.driverId(), null, null, null, driverUserId, now);
		exceptions.autoResolve(tripId, primary ? ExceptionType.UNCONFIRMED : ExceptionType.BACKUP_UNCONFIRMED);
		outbox.publish("dispatch.assignment-confirmed", "trip", tripId,
				new DriverAssignmentConfirmed(tripId, driver.driverId(), primary ? "PRIMARY" : "BACKUP", now));
		return saved;
	}

	/** The assigned driver cannot do it: they are taken off and the trip goes to the exception queue. */
	@Transactional
	public TripAssignment decline(UUID tripId, UUID driverUserId, String reason) {
		DriverInfo driver = driverOfUser(driverUserId);
		TripAssignment a = assignmentOf(tripId, driver.driverId());
		boolean primary = driver.driverId().equals(a.getPrimaryDriverId());
		TripInfo trip = requireTrip(tripId);
		Instant now = now();
		String r = reason == null || reason.isBlank() ? null : reason.strip();
		if (primary) {
			trips.unassign(tripId, trip.version(), driverUserId, "Declined" + (r == null ? "" : ": " + r), true);
			a.setPrimary(null, null, null, a.getReasonCode(), a.getNote(), a.getAssignedBy(), now);
		}
		else {
			a.setBackup(null, null, null, now);
		}
		TripAssignment saved = assignments.saveAndFlush(a);
		record(tripId, Action.DECLINED, primary ? Role.PRIMARY : Role.BACKUP, driver.driverId(), null, null, r, driverUserId, now);
		exceptions.autoResolve(tripId, primary ? ExceptionType.UNCONFIRMED : ExceptionType.BACKUP_UNCONFIRMED);
		exceptions.raise(ExceptionType.DRIVER_DECLINED, tripId, driver.driverId(), primary ? Severity.HIGH : Severity.MEDIUM,
				trip.pickupAt(), Map.of("role", primary ? "PRIMARY" : "BACKUP"));
		outbox.publish("dispatch.assignment-declined", "trip", tripId,
				new DriverAssignmentDeclined(tripId, driver.driverId(), primary ? "PRIMARY" : "BACKUP", now));
		return saved;
	}

	@Transactional(readOnly = true)
	public List<AssignmentHistoryEntry> history(UUID tripId) {
		return history.findByTripIdOrderBySeq(tripId);
	}

	/** Shift and overlap warnings for the trip's primary and backup. */
	@Transactional(readOnly = true)
	public List<Warning> warnings(TripInfo trip, TripAssignment a) {
		List<Warning> warnings = new ArrayList<>();
		check(trip, a.getPrimaryDriverId(), Role.PRIMARY, warnings);
		check(trip, a.getBackupDriverId(), Role.BACKUP, warnings);
		return warnings;
	}

	private void check(TripInfo trip, UUID driverId, Role role, List<Warning> warnings) {
		if (driverId == null) {
			return;
		}
		List<ShiftBlock> blocks = shifts.overlapping(List.of(driverId), trip.pickupAt(), trip.dropoffAt());
		if (blocks.stream().noneMatch(b -> b.covers(trip.pickupAt(), trip.dropoffAt()))) {
			warnings.add(new Warning(driverId, role, "NO_SHIFT", null));
		}
		List<UUID> theirs = assignments.forDriver(driverId).stream()
			.filter(x -> driverId.equals(x.getPrimaryDriverId()) && !x.getTripId().equals(trip.tripId()))
			.map(TripAssignment::getTripId)
			.toList();
		trips.findAll(theirs).stream()
			.filter(o -> o.status().isOpen())
			.filter(o -> o.pickupAt().isBefore(trip.dropoffAt()) && o.dropoffAt().isAfter(trip.pickupAt()))
			.forEach(o -> warnings.add(new Warning(driverId, role, "OVERLAP", o.tripId())));
	}

	private void checkDriver(UUID driverId, TripInfo trip, String which) {
		DriverInfo d = drivers.get(driverId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.driver-not-found"));
		if (d.status() != DriverStatus.APPROVED) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "dispatch.driver-unavailable").withProperty("driver", which)
				.withProperty("reason", "NOT_APPROVED");
		}
		if (d.vehicle() == null || d.vehicle().vehicleClass() != trip.vehicleClass()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "dispatch.driver-unavailable").withProperty("driver", which)
				.withProperty("reason", d.vehicle() == null ? "NO_VEHICLE" : "VEHICLE_CLASS");
		}
	}

	private TripInfo requireTrip(UUID tripId) {
		TripInfo trip = trips.find(tripId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.trip-not-found"));
		if (!(trip.status() == TripStatus.SCHEDULED || trip.status() == TripStatus.ASSIGNED
				|| trip.status() == TripStatus.DRIVER_EN_ROUTE)) {
			throw new ApiException(HttpStatus.CONFLICT, "dispatch.trip-not-assignable").withProperty("status", trip.status().name());
		}
		return trip;
	}

	private TripAssignment requireAssignment(UUID tripId, long expectedVersion) {
		TripAssignment a = assignments.findById(tripId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.assignment-not-found"));
		if (!Objects.equals(a.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		return a;
	}

	/** The driver's own assignment (as primary or backup); otherwise "not found". */
	private TripAssignment assignmentOf(UUID tripId, UUID driverId) {
		return assignments.findById(tripId)
			.filter(a -> driverId.equals(a.getPrimaryDriverId()) || driverId.equals(a.getBackupDriverId()))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.trip-not-found"));
	}

	private DriverInfo driverOfUser(UUID userId) {
		return drivers.byUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "dispatch.not-a-driver"));
	}

	private static String reasonNote(ReasonCode reason, String note) {
		if (reason == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "dispatch.reason-code-required");
		}
		String n = note == null || note.isBlank() ? null : note.strip();
		if (reason == ReasonCode.OTHER && (n == null || n.length() < MIN_OTHER_NOTE)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "dispatch.note-required", MIN_OTHER_NOTE);
		}
		return n == null || n.length() <= 500 ? n : n.substring(0, 500);
	}

	private void record(UUID tripId, Action action, Role role, UUID driverId, UUID previous, ReasonCode reason, String note,
			UUID actor, Instant now) {
		history.save(new AssignmentHistoryEntry(tripId, action, role, driverId, previous, reason, note, actor, now));
	}

	private void audit(String action, UUID tripId, ReasonCode reason, Map<String, String> details) {
		Map<String, Object> all = new HashMap<>(details);
		all.put("reasonCode", reason.name());
		auditLog.record(action, "trip", tripId, all);
	}

	private Instant now() {
		return StoredTime.of(clock.instant());
	}
}

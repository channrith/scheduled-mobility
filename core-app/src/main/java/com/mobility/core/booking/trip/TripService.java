package com.mobility.core.booking.trip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.booking.BookingEvents.TripAssigned;
import com.mobility.core.booking.BookingEvents.TripCancelled;
import com.mobility.core.booking.BookingEvents.TripCashReported;
import com.mobility.core.booking.BookingEvents.TripCompleted;
import com.mobility.core.booking.BookingEvents.TripStatusChanged;
import com.mobility.core.booking.PaymentMethod;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.Ride;
import com.mobility.core.booking.ride.RideRepository;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.driver.DriverDirectory.DriverInfo;
import com.mobility.core.driver.DriverStatus;
import com.mobility.core.shared.outbox.Outbox;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every trip status change: guarded by {@link TripStateMachine}, saved with the version check, recorded as a
 * {@code trip_events} row and published through the outbox, all in one transaction.
 */
@Service
public class TripService {

	/** A no-show can be recorded this long after both the pickup time and the driver's arrival. */
	public static final Duration NO_SHOW_WAIT = Duration.ofMinutes(10);

	static final int MIN_REASON = 10;

	private final TripRepository trips;

	private final TripEventRepository events;

	private final RideRepository rides;

	private final DriverDirectory drivers;

	private final Outbox outbox;

	private final AuditLog auditLog;

	private final Clock clock;

	TripService(TripRepository trips, TripEventRepository events, RideRepository rides, DriverDirectory drivers,
			Outbox outbox, AuditLog auditLog, Clock clock) {
		this.trips = trips;
		this.events = events;
		this.rides = rides;
		this.drivers = drivers;
		this.outbox = outbox;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** Who acts: their user id and in which capacity. */
	public record Actor(UUID userId, TripActor role) {
	}

	// --- Creating ----------------------------------------------------------------------------------

	/** The trips of a new booking, each SCHEDULED with its first trip_events row. */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Trip> create(Ride ride, List<Instant> pickups, Actor actor) {
		Instant now = now();
		List<Trip> created = new ArrayList<>();
		for (int i = 0; i < pickups.size(); i++) {
			Trip trip = trips.save(new Trip(ride.getId(), i + 1, StoredTime.of(pickups.get(i)), now));
			events.save(new TripEvent(trip.getId(), null, TripStatus.SCHEDULED, actor.userId(), actor.role(), null, null, now));
			created.add(trip);
		}
		return created;
	}

	// --- Ops ---------------------------------------------------------------------------------------

	/**
	 * Assigns (or reassigns) an APPROVED driver whose current vehicle is of the trip's class. {@code expectedVersion}
	 * is the version the caller saw: a trip changed since then is a 409. Called by dispatch, which checks the reason
	 * code and writes the audit entry; {@code reason} goes into the trip_events row.
	 */
	@Transactional
	public Trip assign(UUID tripId, UUID driverId, long expectedVersion, Actor actor, String reason) {
		Trip trip = require(tripId);
		checkVersion(trip, expectedVersion);
		if (driverId.equals(trip.getDriverId())) {
			throw new ApiException(HttpStatus.CONFLICT, "booking.already-assigned");
		}
		Ride ride = ride(trip);
		DriverInfo driver = drivers.get(driverId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "booking.driver-not-found"));
		if (driver.status() != DriverStatus.APPROVED) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.driver-unavailable").withProperty("reason", "NOT_APPROVED");
		}
		if (driver.vehicle() == null || driver.vehicle().vehicleClass() != ride.getVehicleClass()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.driver-unavailable")
				.withProperty("reason", driver.vehicle() == null ? "NO_VEHICLE" : "VEHICLE_CLASS");
		}
		TripStatus from = trip.getStatus();
		trip.assign(driver.driverId(), driver.vehicle().vehicleId(), now());
		Trip saved = record(trip, from, actor, shortReason(reason));
		outbox.publish("booking.trip-assigned", "trip", saved.getId(),
				new TripAssigned(saved.getId(), saved.getRideId(), driver.driverId(), driver.vehicle().vehicleId(), saved.getPickupAt()));
		return saved;
	}

	/** Back to SCHEDULED without a driver, for someone else to take it (dispatch checks the reason code and audits). */
	@Transactional
	public Trip unassign(UUID tripId, long expectedVersion, String reason, Actor actor) {
		Trip trip = require(tripId);
		checkVersion(trip, expectedVersion);
		return change(trip, TripStatus.SCHEDULED, actor, shortReason(reason));
	}

	/** {@code byDriver}: the driver could not do it and nobody can replace them (CANCELLED_BY_DRIVER). */
	@Transactional
	public Trip cancelByOps(UUID tripId, String reason, boolean byDriver, Actor ops) {
		Trip trip = require(tripId);
		Trip saved = change(trip, byDriver ? TripStatus.CANCELLED_BY_DRIVER : TripStatus.CANCELLED_BY_OPS, ops, reason(reason));
		audit("booking.trip.cancelled", saved, Map.of("status", saved.getStatus().name()));
		return saved;
	}

	@Transactional
	public Trip dispute(UUID tripId, String reason, Actor ops) {
		Trip saved = change(require(tripId), TripStatus.DISPUTED, ops, reason(reason));
		audit("booking.trip.disputed", saved, Map.of());
		return saved;
	}

	@Transactional
	public Trip resolveDispute(UUID tripId, String reason, Actor ops) {
		Trip saved = change(require(tripId), TripStatus.COMPLETED, ops, reason(reason));
		audit("booking.trip.dispute-resolved", saved, Map.of());
		return saved;
	}

	// --- Driver ------------------------------------------------------------------------------------

	@Transactional
	public Trip driverStep(UUID tripId, TripStatus to, UUID driverUserId) {
		Trip trip = ownTrip(tripId, driverUserId);
		if (to == TripStatus.NO_SHOW && trip.getStatus() == TripStatus.DRIVER_ARRIVED) {
			Instant earliest = later(trip.getPickupAt(), trip.getArrivedAt()).plus(NO_SHOW_WAIT);
			if (now().isBefore(earliest)) {
				throw new ApiException(HttpStatus.CONFLICT, "booking.no-show-too-early").withProperty("earliest", earliest.toString());
			}
		}
		return change(trip, to, new Actor(driverUserId, TripActor.DRIVER), null);
	}

	/** Cash the rider paid, once per completed CASH trip; compared with the fare and its equivalent. */
	@Transactional
	public Trip reportCash(UUID tripId, UUID driverUserId, long amountMinor, String currency) {
		Trip trip = ownTrip(tripId, driverUserId);
		Ride ride = ride(trip);
		if (ride.getPaymentMethod() != PaymentMethod.CASH) {
			throw new ApiException(HttpStatus.CONFLICT, "booking.not-cash");
		}
		if (trip.getStatus() != TripStatus.COMPLETED && trip.getStatus() != TripStatus.DISPUTED) {
			throw new ApiException(HttpStatus.CONFLICT, "booking.not-completed");
		}
		if (trip.getCashReportedAt() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "booking.cash-already-reported");
		}
		if (amountMinor < 0 || !(currency.equals("USD") || currency.equals("KHR"))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "booking.invalid-field").withProperty("field", amountMinor < 0 ? "amountMinor" : "currency");
		}
		boolean matches = (currency.equals(ride.getCurrency()) && amountMinor == ride.getFareMinor())
				|| (currency.equals(ride.getEquivalentCurrency()) && amountMinor == ride.getEquivalentMinor());
		Instant now = now();
		trip.reportCash(amountMinor, currency, !matches, now);
		Trip saved = trips.saveAndFlush(trip);
		outbox.publish("booking.trip-cash-reported", "trip", saved.getId(), new TripCashReported(saved.getId(), saved.getRideId(),
				saved.getDriverId(), currency, amountMinor, ride.getCurrency(), ride.getFareMinor(), !matches, now));
		return saved;
	}

	// --- Rider / booker ----------------------------------------------------------------------------

	/** Riders and bookers can cancel until the driver has arrived (the state machine allows no later). */
	@Transactional
	public Trip cancelByRider(Trip trip, String reason, Actor actor) {
		String r = blankToNull(reason);
		return change(trip, TripStatus.CANCELLED_BY_PASSENGER, actor, r == null || r.length() <= 500 ? r : r.substring(0, 500));
	}

	// --- Reads -------------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public Trip require(UUID tripId) {
		return trips.findById(tripId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "booking.trip-not-found"));
	}

	/** The driver's own trip; another driver's is "not found". */
	@Transactional(readOnly = true)
	public Trip ownTrip(UUID tripId, UUID driverUserId) {
		DriverInfo driver = driverOf(driverUserId);
		return trips.findById(tripId)
			.filter(t -> driver.driverId().equals(t.getDriverId()))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "booking.trip-not-found"));
	}

	@Transactional(readOnly = true)
	public DriverInfo driverOf(UUID driverUserId) {
		return drivers.byUserId(driverUserId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "booking.not-a-driver"));
	}

	@Transactional(readOnly = true)
	public Map<UUID, List<TripEvent>> history(List<UUID> tripIds) {
		Map<UUID, List<TripEvent>> byTrip = new HashMap<>();
		for (TripEvent e : events.findByTripIdInOrderBySeq(tripIds)) {
			byTrip.computeIfAbsent(e.getTripId(), k -> new ArrayList<>()).add(e);
		}
		return byTrip;
	}

	// --- Internals ---------------------------------------------------------------------------------

	private Trip change(Trip trip, TripStatus to, Actor actor, String reason) {
		TripStatus from = trip.getStatus();
		UUID driverBefore = trip.getDriverId();
		trip.moveTo(to, now());
		Trip saved = record(trip, from, actor, reason);
		Instant at = saved.getUpdatedAtOr(now());
		if (to == TripStatus.COMPLETED && from == TripStatus.IN_PROGRESS) {
			Ride ride = ride(saved);
			outbox.publish("booking.trip-completed", "trip", saved.getId(), new TripCompleted(saved.getId(), saved.getRideId(),
					saved.getDriverId(), ride.getCurrency(), ride.getFareMinor(), ride.getPaymentMethod(), ride.getCorporateId(),
					saved.getCompletedAt()));
		}
		if (TripStatus.CANCELLED.contains(to) || to == TripStatus.NO_SHOW) {
			outbox.publish("booking.trip-cancelled", "trip", saved.getId(),
					new TripCancelled(saved.getId(), saved.getRideId(), to, driverBefore, at));
		}
		return saved;
	}

	/** Saves (version check), writes the trip_events row and TripStatusChanged. */
	private Trip record(Trip trip, TripStatus from, Actor actor, String reason) {
		Trip saved = trips.saveAndFlush(trip);
		Instant now = saved.getUpdatedAtOr(now());
		events.save(new TripEvent(saved.getId(), from, saved.getStatus(), actor.userId(), actor.role(), reason,
				saved.getDriverId(), now));
		outbox.publish("booking.trip-status-changed", "trip", saved.getId(),
				new TripStatusChanged(saved.getId(), saved.getRideId(), from, saved.getStatus(), actor.role().name(), now));
		return saved;
	}

	private void checkVersion(Trip trip, long expectedVersion) {
		if (!Objects.equals(trip.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
	}

	private Ride ride(Trip trip) {
		return rides.findById(trip.getRideId()).orElseThrow();
	}

	private void audit(String action, Trip trip, Map<String, String> details) {
		Map<String, Object> all = new HashMap<>(details);
		all.put("status", trip.getStatus().name());
		auditLog.record(action, "trip", trip.getId(), all);
	}

	private static String reason(String reason) {
		String r = blankToNull(reason);
		if (r == null || r.length() < MIN_REASON) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "booking.reason-required", MIN_REASON);
		}
		return r.length() > 500 ? r.substring(0, 500) : r;
	}

	private static String shortReason(String reason) {
		String r = blankToNull(reason);
		return r == null || r.length() <= 500 ? r : r.substring(0, 500);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	private static Instant later(Instant a, Instant b) {
		return b == null || a.isAfter(b) ? a : b;
	}

	private Instant now() {
		return StoredTime.of(clock.instant());
	}
}

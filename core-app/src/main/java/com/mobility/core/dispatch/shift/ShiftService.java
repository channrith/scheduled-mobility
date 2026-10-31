package com.mobility.core.dispatch.shift;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.booking.Trips;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.assignment.TripAssignment;
import com.mobility.core.dispatch.assignment.TripAssignmentRepository;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.driver.DriverDirectory.DriverInfo;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Drivers declare when they can drive; blocks of one driver never overlap. */
@Service
public class ShiftService {

	/** A block may start a little in the past (the driver is starting now). */
	static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

	private final ShiftBlockRepository shifts;

	private final TripAssignmentRepository assignments;

	private final Trips trips;

	private final DriverDirectory drivers;

	private final DispatchProperties properties;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	ShiftService(ShiftBlockRepository shifts, TripAssignmentRepository assignments, Trips trips, DriverDirectory drivers,
			DispatchProperties properties, JdbcTemplate jdbc, Clock clock) {
		this.shifts = shifts;
		this.assignments = assignments;
		this.trips = trips;
		this.drivers = drivers;
		this.properties = properties;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	public ShiftBlock create(UUID driverUserId, Instant startsAt, Instant endsAt) {
		DriverInfo driver = driverOf(driverUserId);
		Instant now = StoredTime.of(clock.instant());
		Instant start = StoredTime.of(startsAt);
		Instant end = StoredTime.of(endsAt);
		if (start == null || end == null || !end.isAfter(start)) {
			throw invalid("endsAt");
		}
		if (start.isBefore(now.minus(PAST_TOLERANCE)) || start.isAfter(now.plus(properties.shifts().maxAhead()))) {
			throw invalid("startsAt");
		}
		if (Duration.between(start, end).compareTo(properties.shifts().maxLength()) > 0) {
			throw invalid("endsAt");
		}
		lock(driver.driverId());
		List<ShiftBlock> clash = shifts.overlapping(List.of(driver.driverId()), start, end);
		if (!clash.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "dispatch.shift-overlap").withProperty("shiftId", clash.getFirst().getId());
		}
		return shifts.saveAndFlush(new ShiftBlock(driver.driverId(), start, end, driverUserId, now));
	}

	/** Refused while the driver has an open trip (as primary or backup) inside the block. */
	@Transactional
	public ShiftBlock cancel(UUID driverUserId, UUID shiftId) {
		DriverInfo driver = driverOf(driverUserId);
		lock(driver.driverId());
		ShiftBlock block = shifts.findById(shiftId)
			.filter(s -> s.getDriverId().equals(driver.driverId()) && s.isActive())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.shift-not-found"));
		List<UUID> tripIds = assignments.forDriver(driver.driverId()).stream().map(TripAssignment::getTripId).toList();
		List<UUID> inside = trips.findAll(tripIds).stream()
			.filter(t -> t.status().isOpen())
			.filter(t -> t.pickupAt().isBefore(block.getEndsAt()) && t.dropoffAt().isAfter(block.getStartsAt()))
			.filter(t -> Objects.equals(t.driverId(), driver.driverId()) || isBackup(t.tripId(), driver.driverId()))
			.map(Trips.TripInfo::tripId)
			.toList();
		if (!inside.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "dispatch.shift-has-trips").withProperty("tripIds", inside);
		}
		block.cancel(StoredTime.of(clock.instant()));
		return shifts.saveAndFlush(block);
	}

	@Transactional(readOnly = true)
	public List<ShiftBlock> mine(UUID driverUserId) {
		return shifts.upcoming(driverOf(driverUserId).driverId(), clock.instant().minus(Duration.ofHours(12)));
	}

	@Transactional(readOnly = true)
	public List<ShiftBlock> between(Instant from, Instant to) {
		return shifts.overlapping(from, to);
	}

	DriverInfo driverOf(UUID driverUserId) {
		return drivers.byUserId(driverUserId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "dispatch.not-a-driver"));
	}

	private boolean isBackup(UUID tripId, UUID driverId) {
		return assignments.findById(tripId).map(a -> driverId.equals(a.getBackupDriverId())).orElse(false);
	}

	/** Serializes changes to one driver's blocks (released at commit). */
	private void lock(UUID driverId) {
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, "dispatch.shift:" + driverId);
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "dispatch.invalid-field").withProperty("field", field);
	}
}

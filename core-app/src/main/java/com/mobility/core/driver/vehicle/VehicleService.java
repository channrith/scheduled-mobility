package com.mobility.core.driver.vehicle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.phone.PhoneNumbers;
import com.mobility.core.shared.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

	/** How far in the past an assignment may start (clock skew, a form filled in slowly). */
	private static final Duration PAST_TOLERANCE = Duration.ofMinutes(10);

	public record VehicleData(String plateNumber, VehicleClass vehicleClass, String make, String model, String color,
			Short modelYear, short seats, VehicleOwnership ownership, String ownerName, String ownerPhone) {
	}

	public record AssignmentView(UUID id, VehicleView vehicle, Instant validFrom, Instant validTo, boolean active) {
	}

	public record VehicleListItem(VehicleView vehicle, UUID assignedDriverId, String assignedDriverName) {
	}

	private final VehicleRepository vehicles;

	private final VehicleAssignmentRepository assignments;

	private final NamedParameterJdbcTemplate jdbc;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	VehicleService(VehicleRepository vehicles, VehicleAssignmentRepository assignments, NamedParameterJdbcTemplate jdbc,
			AuditLog auditLog, CurrentUserProvider currentUser, Clock clock) {
		this.vehicles = vehicles;
		this.assignments = assignments;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	// --- Queries -------------------------------------------------------------------------------

	/** The vehicle assigned to the driver right now. */
	@Transactional(readOnly = true)
	public Optional<VehicleView> activeVehicle(UUID driverId) {
		return Optional.ofNullable(activeVehicles(List.of(driverId)).get(driverId));
	}

	@Transactional(readOnly = true)
	public Map<UUID, VehicleView> activeVehicles(Collection<UUID> driverIds) {
		if (driverIds.isEmpty()) {
			return Map.of();
		}
		List<VehicleAssignment> active = assignments.findActiveForDrivers(driverIds, clock.instant());
		Map<UUID, Vehicle> byId = vehicles.findAllById(active.stream().map(VehicleAssignment::getVehicleId).toList())
			.stream()
			.collect(Collectors.toMap(Vehicle::getId, Function.identity()));
		Map<UUID, VehicleView> result = new HashMap<>();
		active.forEach(a -> result.put(a.getDriverId(), VehicleView.of(byId.get(a.getVehicleId()), a)));
		return result;
	}

	/** All of a driver's assignments, newest first (future, current and past). */
	@Transactional(readOnly = true)
	public List<AssignmentView> assignments(UUID driverId) {
		List<VehicleAssignment> all = assignments.findByDriverIdOrderByValidFromDesc(driverId);
		Map<UUID, Vehicle> byId = vehicles.findAllById(all.stream().map(VehicleAssignment::getVehicleId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Vehicle::getId, Function.identity()));
		Instant now = clock.instant();
		return all.stream()
			.map(a -> new AssignmentView(a.getId(), VehicleView.of(byId.get(a.getVehicleId()), a), a.getValidFrom(),
					a.getValidTo(), a.isActiveAt(now)))
			.toList();
	}

	@Transactional(readOnly = true)
	public VehicleView get(UUID vehicleId) {
		return VehicleView.of(find(vehicleId), null);
	}

	/**
	 * @param q plate (separators ignored) or owner name
	 */
	@Transactional(readOnly = true)
	public Page<VehicleListItem> list(String q, VehicleClass vehicleClass, VehicleStatus status, int page, int size) {
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
		StringBuilder where = new StringBuilder(" WHERE true");
		MapSqlParameterSource params = new MapSqlParameterSource();
		if (q != null && !q.isBlank()) {
			where.append(" AND (regexp_replace(v.plate_number, '[^A-Z0-9]', '', 'g') LIKE :plate"
					+ " OR lower(v.owner_name) LIKE :text)");
			params.addValue("plate", "%" + q.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "%");
			params.addValue("text", "%" + q.strip().toLowerCase(Locale.ROOT) + "%");
		}
		if (vehicleClass != null) {
			where.append(" AND v.vehicle_class = :class");
			params.addValue("class", vehicleClass.name());
		}
		if (status != null) {
			where.append(" AND v.status = :status");
			params.addValue("status", status.name());
		}
		Long total = jdbc.queryForObject("SELECT count(*) FROM driver.vehicles v" + where, params, Long.class);
		params.addValue("limit", request.getPageSize()).addValue("offset", request.getOffset());
		List<UUID> ids = jdbc.queryForList("SELECT v.id FROM driver.vehicles v" + where
				+ " ORDER BY v.created_at DESC, v.id LIMIT :limit OFFSET :offset", params, UUID.class);

		Map<UUID, Vehicle> byId = vehicles.findAllById(ids).stream().collect(Collectors.toMap(Vehicle::getId, Function.identity()));
		Map<UUID, VehicleAssignment> activeByVehicle = ids.isEmpty() ? Map.of()
				: assignments.findActiveForVehicles(ids, clock.instant())
					.stream()
					.collect(Collectors.toMap(VehicleAssignment::getVehicleId, Function.identity()));
		Map<UUID, String> driverNames = driverNames(activeByVehicle.values().stream().map(VehicleAssignment::getDriverId).toList());
		List<VehicleListItem> items = ids.stream().map(id -> {
			VehicleAssignment a = activeByVehicle.get(id);
			return new VehicleListItem(VehicleView.of(byId.get(id), a), a == null ? null : a.getDriverId(),
					a == null ? null : driverNames.get(a.getDriverId()));
		}).toList();
		return new PageImpl<>(items, request, total == null ? 0 : total);
	}

	// --- Commands ------------------------------------------------------------------------------

	@Transactional
	public VehicleView register(VehicleData data) {
		String plate = Vehicle.normalizePlate(data.plateNumber());
		if (vehicles.existsByPlateNumber(plate)) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.plate-taken");
		}
		Vehicle vehicle = new Vehicle(plate, data.vehicleClass(), data.make(), data.model(), data.color(),
				data.modelYear(), data.seats());
		applyOwner(vehicle, data);
		vehicles.saveAndFlush(vehicle);
		auditLog.record("vehicle.registered", "vehicle", vehicle.getId(),
				Map.of("vehicleClass", vehicle.getVehicleClass(), "ownership", vehicle.getOwnership()));
		return VehicleView.of(vehicle, null);
	}

	/** Full update of the editable fields. {@code expectedVersion} guards against concurrent edits. */
	@Transactional
	public VehicleView update(UUID vehicleId, VehicleData data, VehicleStatus status, long expectedVersion) {
		Vehicle vehicle = find(vehicleId);
		if (!Objects.equals(vehicle.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		String plate = Vehicle.normalizePlate(data.plateNumber());
		if (vehicles.existsByPlateNumberAndIdNot(plate, vehicleId)) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.plate-taken");
		}
		if (status == VehicleStatus.INACTIVE && vehicle.getStatus() == VehicleStatus.ACTIVE
				&& !assignments.findNotEndedByVehicle(vehicleId, clock.instant()).isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.has-assignment");
		}
		vehicle.update(plate, data.vehicleClass(), data.make(), data.model(), data.color(), data.modelYear(),
				data.seats());
		applyOwner(vehicle, data);
		vehicle.changeStatus(status);
		vehicles.saveAndFlush(vehicle);
		auditLog.record("vehicle.updated", "vehicle", vehicleId,
				Map.of("status", vehicle.getStatus(), "ownership", vehicle.getOwnership()));
		return VehicleView.of(vehicle, null);
	}

	/**
	 * Assigns the vehicle to the driver for [validFrom, validTo). An open-ended assignment of the driver that
	 * started earlier or is already running is ended at validFrom (reassignment); any other overlap is refused.
	 *
	 * @param validFrom null = now; may be in the future
	 * @param validTo null = open-ended
	 */
	@Transactional
	public AssignmentView assign(UUID driverId, UUID vehicleId, Instant validFrom, Instant validTo) {
		Instant now = clock.instant();
		Instant from = validFrom == null ? now : validFrom;
		if (from.isBefore(now.minus(PAST_TOLERANCE))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "vehicle.assignment-in-past");
		}
		if (validTo != null && !validTo.isAfter(from)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "vehicle.assignment-invalid-period");
		}
		lockDriver(driverId);
		Vehicle vehicle = vehicles.lockById(vehicleId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "vehicle.not-found"));
		if (vehicle.getStatus() != VehicleStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.inactive");
		}
		for (VehicleAssignment other : assignments.findNotEndedByVehicle(vehicleId, now)) {
			if (!other.getDriverId().equals(driverId) && other.overlaps(from, validTo)) {
				throw new ApiException(HttpStatus.CONFLICT, "vehicle.already-assigned");
			}
		}
		UUID actor = currentUser.require().userId();
		for (VehicleAssignment own : assignments.findNotEndedByDriver(driverId, now)) {
			if (!own.overlaps(from, validTo)) {
				continue;
			}
			// Reassignment: an open-ended assignment that started earlier, or is already running, ends where the
			// new one begins (a form's start time can be a few seconds before the current one started).
			boolean replaceable = own.getValidTo() == null
					&& (own.getValidFrom().isBefore(from) || !own.getValidFrom().isAfter(now));
			if (replaceable) {
				own.endAt(actor, from);
				assignments.saveAndFlush(own);
			}
			else {
				throw new ApiException(HttpStatus.CONFLICT, "driver.assignment-overlap");
			}
		}
		VehicleAssignment assignment = assignments.saveAndFlush(
				new VehicleAssignment(driverId, vehicleId, actor, now, from, validTo));
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("vehicleId", vehicleId);
		details.put("validFrom", from.toString());
		if (validTo != null) {
			details.put("validTo", validTo.toString());
		}
		auditLog.record("driver.vehicle.assigned", "driver", driverId, details);
		return new AssignmentView(assignment.getId(), VehicleView.of(vehicle, assignment), from, validTo,
				assignment.isActiveAt(now));
	}

	/** Ends a current assignment now, or cancels a future one. */
	@Transactional
	public void endAssignment(UUID driverId, UUID assignmentId) {
		VehicleAssignment assignment = assignments.findById(assignmentId)
			.filter(a -> a.getDriverId().equals(driverId))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "vehicle.assignment-not-found"));
		Instant now = clock.instant();
		if (assignment.getValidTo() != null && !assignment.getValidTo().isAfter(now)) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.assignment-ended");
		}
		assignment.endAt(currentUser.require().userId(), now);
		assignments.saveAndFlush(assignment);
		auditLog.record("driver.vehicle.unassigned", "driver", driverId,
				Map.of("vehicleId", assignment.getVehicleId(), "assignmentId", assignmentId));
	}

	private void applyOwner(Vehicle vehicle, VehicleData data) {
		VehicleOwnership ownership = data.ownership() == null ? VehicleOwnership.DRIVER : data.ownership();
		String name = blankToNull(data.ownerName());
		String phone = null;
		if (blankToNull(data.ownerPhone()) != null) {
			phone = PhoneNumbers.normalize(data.ownerPhone())
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "phone.invalid"));
		}
		if (ownership == VehicleOwnership.THIRD_PARTY && (name == null || phone == null)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "vehicle.owner-required");
		}
		vehicle.changeOwner(ownership, name, phone);
	}

	/** Serializes assignments per driver; also checks the driver exists. */
	private void lockDriver(UUID driverId) {
		List<UUID> locked = jdbc.queryForList("SELECT id FROM driver.drivers WHERE id = :id FOR UPDATE",
				new MapSqlParameterSource("id", driverId), UUID.class);
		if (locked.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "driver.not-found");
		}
	}

	private Map<UUID, String> driverNames(Collection<UUID> driverIds) {
		if (driverIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>();
		jdbc.query("SELECT id, full_name FROM driver.drivers WHERE id IN (:ids)", new MapSqlParameterSource("ids", driverIds),
				rs -> {
					names.put(rs.getObject("id", UUID.class), rs.getString("full_name"));
				});
		return names;
	}

	private Vehicle find(UUID vehicleId) {
		return vehicles.findById(vehicleId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "vehicle.not-found"));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}

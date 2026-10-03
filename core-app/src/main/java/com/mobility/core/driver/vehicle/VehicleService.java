package com.mobility.core.driver.vehicle;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

	public record RegisterVehicle(String plateNumber, VehicleClass vehicleClass, String make, String model,
			String color, Short modelYear, short seats) {
	}

	private final VehicleRepository vehicles;

	private final VehicleAssignmentRepository assignments;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	VehicleService(VehicleRepository vehicles, VehicleAssignmentRepository assignments, AuditLog auditLog,
			CurrentUserProvider currentUser, Clock clock) {
		this.vehicles = vehicles;
		this.assignments = assignments;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Optional<VehicleView> activeVehicle(UUID driverId) {
		return assignments.findByDriverIdAndUnassignedAtIsNull(driverId)
			.flatMap(a -> vehicles.findById(a.getVehicleId()).map(v -> VehicleView.of(v, a.getAssignedAt())));
	}

	@Transactional
	public VehicleView register(RegisterVehicle cmd) {
		String plate = Vehicle.normalizePlate(cmd.plateNumber());
		if (vehicles.existsByPlateNumber(plate)) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.plate-taken");
		}
		Vehicle vehicle = vehicles.saveAndFlush(new Vehicle(plate, cmd.vehicleClass(), cmd.make(), cmd.model(),
				cmd.color(), cmd.modelYear(), cmd.seats()));
		auditLog.record("vehicle.registered", "vehicle", vehicle.getId(),
				Map.of("vehicleClass", vehicle.getVehicleClass()));
		return VehicleView.of(vehicle, null);
	}

	@Transactional(readOnly = true)
	public VehicleView get(UUID vehicleId) {
		return VehicleView.of(find(vehicleId), null);
	}

	/**
	 * Assigns the vehicle to the driver, ending the driver's previous assignment if any. A vehicle can only
	 * be assigned to one driver at a time. The caller has checked that the driver exists.
	 */
	@Transactional
	public VehicleView assign(UUID driverId, UUID vehicleId) {
		Vehicle vehicle = find(vehicleId);
		if (vehicle.getStatus() != VehicleStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.inactive");
		}
		Optional<VehicleAssignment> ofVehicle = assignments.findByVehicleIdAndUnassignedAtIsNull(vehicleId);
		if (ofVehicle.isPresent()) {
			if (ofVehicle.get().getDriverId().equals(driverId)) {
				return VehicleView.of(vehicle, ofVehicle.get().getAssignedAt());
			}
			throw new ApiException(HttpStatus.CONFLICT, "vehicle.already-assigned");
		}
		UUID actor = currentUser.require().userId();
		assignments.findByDriverIdAndUnassignedAtIsNull(driverId).ifPresent(previous -> {
			previous.end(actor, clock.instant());
			assignments.saveAndFlush(previous);
		});
		VehicleAssignment assignment = assignments.saveAndFlush(
				new VehicleAssignment(driverId, vehicleId, actor, clock.instant()));
		auditLog.record("driver.vehicle.assigned", "driver", driverId, Map.of("vehicleId", vehicleId));
		return VehicleView.of(vehicle, assignment.getAssignedAt());
	}

	@Transactional
	public void unassign(UUID driverId) {
		VehicleAssignment current = assignments.findByDriverIdAndUnassignedAtIsNull(driverId)
			.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "vehicle.not-assigned"));
		current.end(currentUser.require().userId(), clock.instant());
		assignments.saveAndFlush(current);
		auditLog.record("driver.vehicle.unassigned", "driver", driverId, Map.of("vehicleId", current.getVehicleId()));
	}

	private Vehicle find(UUID vehicleId) {
		return vehicles.findById(vehicleId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "vehicle.not-found"));
	}
}

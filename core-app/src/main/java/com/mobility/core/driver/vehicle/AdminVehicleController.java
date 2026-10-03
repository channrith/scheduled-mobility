package com.mobility.core.driver.vehicle;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.driver.vehicle.VehicleService.RegisterVehicle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/vehicles")
@Tag(name = "Admin · Vehicles", description = "Fleet vehicles")
class AdminVehicleController {

	private final VehicleService vehicles;

	AdminVehicleController(VehicleService vehicles) {
		this.vehicles = vehicles;
	}

	@Operation(summary = "Register a vehicle", description = "The plate is normalized (uppercase, single spaces) and must be unique (`vehicle.plate-taken`).")
	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<VehicleView> register(@Valid @RequestBody RegisterVehicleRequest request) {
		VehicleView vehicle = vehicles.register(new RegisterVehicle(request.plateNumber(), request.vehicleClass(),
				request.make(), request.model(), request.color(), request.modelYear(), request.seats()));
		return ResponseEntity.created(URI.create("/api/v1/admin/vehicles/" + vehicle.id())).body(vehicle);
	}

	@Operation(summary = "Get a vehicle")
	@GetMapping("/{vehicleId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'SUPPORT', 'SAFETY_OFFICER')")
	VehicleView get(@PathVariable UUID vehicleId) {
		return vehicles.get(vehicleId);
	}

	record RegisterVehicleRequest(@NotBlank @Size(max = 20) String plateNumber, @NotNull VehicleClass vehicleClass,
			@Size(max = 50) String make, @Size(max = 50) String model, @Size(max = 30) String color,
			@Min(1980) @Max(2100) Short modelYear, @Min(1) @Max(20) short seats) {
	}
}

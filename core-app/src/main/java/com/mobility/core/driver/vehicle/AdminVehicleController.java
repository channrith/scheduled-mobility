package com.mobility.core.driver.vehicle;

import java.net.URI;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import com.mobility.core.driver.vehicle.VehicleService.VehicleData;
import com.mobility.core.driver.vehicle.VehicleService.VehicleListItem;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/vehicles")
@Tag(name = "Admin · Vehicles", description = "Fleet vehicles")
class AdminVehicleController {

	private static final String VIEW = "hasAnyRole('ADMIN', 'DISPATCHER', 'SUPPORT', 'SAFETY_OFFICER')";

	private final VehicleService vehicles;

	AdminVehicleController(VehicleService vehicles) {
		this.vehicles = vehicles;
	}

	@Operation(summary = "Register a vehicle",
			description = "The plate is normalized (uppercase, single spaces) and must be unique (`vehicle.plate-taken`). "
					+ "`ownership` THIRD_PARTY (e.g. a rented tuk-tuk) requires `ownerName` and `ownerPhone`.")
	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<VehicleView> register(@Valid @RequestBody VehicleRequest request) {
		VehicleView vehicle = vehicles.register(request.data());
		return ResponseEntity.created(URI.create("/api/v1/admin/vehicles/" + vehicle.id())).body(vehicle);
	}

	@Operation(summary = "Edit a vehicle",
			description = "Replaces all editable fields. `version` must match (`concurrent-update` otherwise). A vehicle "
					+ "with a current or future assignment cannot be set INACTIVE.")
	@PutMapping("/{vehicleId}")
	@PreAuthorize("hasRole('ADMIN')")
	VehicleView update(@PathVariable UUID vehicleId, @Valid @RequestBody UpdateVehicleRequest request) {
		return vehicles.update(vehicleId, request.data(), request.status(), request.version());
	}

	@Operation(summary = "List vehicles",
			description = "Newest first; `q` matches the plate (separators ignored) or owner name. Rows include the "
					+ "driver the vehicle is assigned to right now.")
	@GetMapping
	@PreAuthorize(VIEW)
	PageResponse<VehicleListItem> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) VehicleClass vehicleClass,
			@RequestParam(required = false) VehicleStatus status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return PageResponse.of(vehicles.list(q, vehicleClass, status, page, size), item -> item);
	}

	@Operation(summary = "Get a vehicle")
	@GetMapping("/{vehicleId}")
	@PreAuthorize(VIEW)
	VehicleView get(@PathVariable UUID vehicleId) {
		return vehicles.get(vehicleId);
	}

	record VehicleRequest(@NotBlank @Size(max = 20) String plateNumber, @NotNull VehicleClass vehicleClass,
			@Size(max = 50) String make, @Size(max = 50) String model, @Size(max = 30) String color,
			@Min(1980) @Max(2100) Short modelYear, @Min(1) @Max(20) short seats, VehicleOwnership ownership,
			@Size(max = 200) String ownerName, String ownerPhone) {

		VehicleData data() {
			return new VehicleData(plateNumber, vehicleClass, make, model, color, modelYear, seats, ownership, ownerName,
					ownerPhone);
		}
	}

	record UpdateVehicleRequest(@NotNull Long version, @NotNull VehicleStatus status,
			@NotBlank @Size(max = 20) String plateNumber, @NotNull VehicleClass vehicleClass, @Size(max = 50) String make,
			@Size(max = 50) String model, @Size(max = 30) String color, @Min(1980) @Max(2100) Short modelYear,
			@Min(1) @Max(20) short seats, VehicleOwnership ownership, @Size(max = 200) String ownerName,
			String ownerPhone) {

		VehicleData data() {
			return new VehicleData(plateNumber, vehicleClass, make, model, color, modelYear, seats, ownership, ownerName,
					ownerPhone);
		}
	}
}

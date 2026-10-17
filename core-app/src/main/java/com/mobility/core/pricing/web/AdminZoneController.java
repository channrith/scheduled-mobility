package com.mobility.core.pricing.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.pricing.zone.LatLng;
import com.mobility.core.pricing.zone.Zone;
import com.mobility.core.pricing.zone.ZoneService;
import com.mobility.core.pricing.zone.ZoneService.ZoneInput;
import com.mobility.core.pricing.zone.ZoneType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/admin/pricing/zones")
@Tag(name = "Admin · Pricing zones", description = "Service area, airport surcharge and restricted areas")
class AdminZoneController {

	private final ZoneService zones;

	AdminZoneController(ZoneService zones) {
		this.zones = zones;
	}

	/** @param polygon outer ring, not closed; surcharge in minor units of its currency (AIRPORT only) */
	record ZoneView(UUID id, String city, ZoneType type, String nameKm, String nameEn, List<LatLng> polygon,
			Long surchargeMinor, String surchargeCurrency, boolean active, Instant updatedAt, Long version) {
	}

	record ZoneRequest(@NotNull String city, @NotNull ZoneType type, String nameKm, String nameEn,
			@NotNull List<LatLng> polygon, Long surchargeMinor, String surchargeCurrency, Boolean active) {

		ZoneInput input() {
			return new ZoneInput(city, type, nameKm, nameEn, polygon, surchargeMinor, surchargeCurrency, active);
		}
	}

	record UpdateZoneRequest(@NotNull Long version, @NotNull String city, @NotNull ZoneType type, String nameKm,
			String nameEn, @NotNull List<LatLng> polygon, Long surchargeMinor, String surchargeCurrency, Boolean active) {

		ZoneInput input() {
			return new ZoneInput(city, type, nameKm, nameEn, polygon, surchargeMinor, surchargeCurrency, active);
		}
	}

	@Operation(summary = "Zones", description = "All zones (active and inactive), optionally of one city.")
	@GetMapping
	@PreAuthorize(AdminPricingController.VIEW)
	List<ZoneView> list(@RequestParam(required = false) String city) {
		return zones.list(city).stream().map(this::view).toList();
	}

	@Operation(summary = "One zone")
	@GetMapping("/{zoneId}")
	@PreAuthorize(AdminPricingController.VIEW)
	ZoneView get(@PathVariable UUID zoneId) {
		return view(zones.get(zoneId));
	}

	@Operation(summary = "Create a zone",
			description = "`polygon`: 3–500 points inside Cambodia, lines must not cross (`pricing.zone-invalid`). AIRPORT "
					+ "needs `surchargeMinor` + `surchargeCurrency`; other types ignore them.")
	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<ZoneView> create(@Valid @RequestBody ZoneRequest request) {
		Zone zone = zones.create(request.input());
		return ResponseEntity.created(URI.create("/api/v1/admin/pricing/zones/" + zone.getId())).body(view(zone));
	}

	@Operation(summary = "Edit a zone", description = "Shape, names, type, surcharge, active; `version` must match. "
			+ "Existing quotes keep the surcharge they were given.")
	@PutMapping("/{zoneId}")
	@PreAuthorize("hasRole('ADMIN')")
	ZoneView update(@PathVariable UUID zoneId, @Valid @RequestBody UpdateZoneRequest request) {
		return view(zones.update(zoneId, request.input(), request.version()));
	}

	private ZoneView view(Zone z) {
		return new ZoneView(z.getId(), z.getCity(), z.getType(), z.getNameKm(), z.getNameEn(), zones.polygon(z),
				z.getSurchargeMinor(), z.getSurchargeCurrency(), z.isActive(), z.getUpdatedAt(), z.getVersion());
	}
}

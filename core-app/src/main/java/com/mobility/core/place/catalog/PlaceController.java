package com.mobility.core.place.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.mobility.core.place.PlaceType;
import com.mobility.core.place.catalog.PlaceSearch.PlaceHit;
import com.mobility.core.place.catalog.PlaceService.PlaceInput;
import com.mobility.core.shared.web.ApiException;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Places", description = "Landmark search and the landmark database")
class PlaceController {

	static final String EDIT = "hasAnyRole('ADMIN', 'DISPATCHER')";

	private final PlaceService places;

	private final PlaceSearch search;

	PlaceController(PlaceService places, PlaceSearch search) {
		this.places = places;
		this.search = search;
	}

	record PlaceView(UUID id, String nameKm, String nameEn, double latitude, double longitude, PlaceType type,
			String pickupNotesKm, String pickupNotesEn, long usageCount, boolean verified, PlaceStatus status,
			UUID mergedInto, Instant updatedAt, Long version) {

		static PlaceView of(Place p) {
			return new PlaceView(p.getId(), p.getNameKm(), p.getNameEn(), p.getLatitude(), p.getLongitude(), p.getType(),
					p.getPickupNotesKm(), p.getPickupNotesEn(), p.getUsageCount(), p.isVerified(), p.getStatus(),
					p.getMergedInto(), p.getUpdatedAt(), p.getVersion());
		}
	}

	@Operation(summary = "Search landmarks",
			description = "Fuzzy match on Khmer and English names (partial words and small typos). With `lat`/`lng`, "
					+ "nearer places rank higher and `distanceMeters` is returned. `limit` 1–30 (default 10).")
	@GetMapping("/api/v1/places/search")
	List<PlaceHit> search(@RequestParam String q, @RequestParam(required = false) Double lat,
			@RequestParam(required = false) Double lng, @RequestParam(defaultValue = "10") int limit) {
		if (q.isBlank() || (lat == null) != (lng == null)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		return search.search(q, lat, lng, limit);
	}

	@Operation(summary = "List places (admin)", description = "Active places by name; filter `verified=false` to find pins still to check.")
	@GetMapping("/api/v1/admin/places")
	@PreAuthorize(EDIT)
	PageResponse<PlaceView> list(@RequestParam(required = false) String q, @RequestParam(required = false) Boolean verified,
			@RequestParam(required = false) PlaceType type, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return PageResponse.of(places.list(q, verified, type, page, size), PlaceView::of);
	}

	@Operation(summary = "Get a place")
	@GetMapping("/api/v1/admin/places/{placeId}")
	@PreAuthorize(EDIT)
	PlaceView get(@PathVariable UUID placeId) {
		return PlaceView.of(places.require(placeId));
	}

	@Operation(summary = "Create a place", description = "At least one name; the pin must be in Cambodia (`place.outside-cambodia`).")
	@PostMapping("/api/v1/admin/places")
	@PreAuthorize(EDIT)
	ResponseEntity<PlaceView> create(@Valid @RequestBody PlaceRequest request) {
		Place place = places.create(request.input());
		return ResponseEntity.created(URI.create("/api/v1/admin/places/" + place.getId())).body(PlaceView.of(place));
	}

	@Operation(summary = "Edit a place", description = "Names, pin, type, pickup notes and `verified`; `version` must match.")
	@PutMapping("/api/v1/admin/places/{placeId}")
	@PreAuthorize(EDIT)
	PlaceView update(@PathVariable UUID placeId, @Valid @RequestBody UpdatePlaceRequest request) {
		return PlaceView.of(places.update(placeId, request.input(), request.version()));
	}

	@Operation(summary = "Merge a duplicate into another place",
			description = "The duplicate stops appearing in search; its saved places and usage move to `intoPlaceId`.")
	@PostMapping("/api/v1/admin/places/{placeId}/merge")
	@PreAuthorize("hasRole('ADMIN')")
	PlaceView merge(@PathVariable UUID placeId, @Valid @RequestBody MergeRequest request) {
		return PlaceView.of(places.merge(placeId, request.intoPlaceId()));
	}

	record PlaceRequest(@Size(max = 200) String nameKm, @Size(max = 200) String nameEn, @NotNull Double latitude,
			@NotNull Double longitude, @NotNull PlaceType type, @Size(max = 1000) String pickupNotesKm,
			@Size(max = 1000) String pickupNotesEn, Boolean verified) {

		PlaceInput input() {
			return new PlaceInput(nameKm, nameEn, latitude, longitude, type, pickupNotesKm, pickupNotesEn, verified);
		}
	}

	record UpdatePlaceRequest(@NotNull Long version, @Size(max = 200) String nameKm, @Size(max = 200) String nameEn,
			@NotNull Double latitude, @NotNull Double longitude, @NotNull PlaceType type,
			@Size(max = 1000) String pickupNotesKm, @Size(max = 1000) String pickupNotesEn, Boolean verified) {

		PlaceInput input() {
			return new PlaceInput(nameKm, nameEn, latitude, longitude, type, pickupNotesKm, pickupNotesEn, verified);
		}
	}

	record MergeRequest(@NotNull UUID intoPlaceId) {
	}
}

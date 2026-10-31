package com.mobility.core.place.saved;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.place.PlaceDirectory;
import com.mobility.core.place.PlaceDirectory.PlaceSummary;
import com.mobility.core.place.saved.SavedPlaceService.SavedInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/saved-places")
@Tag(name = "Saved places", description = "The signed-in user's Home, Office and custom places")
class SavedPlaceController {

	private final SavedPlaceService saved;

	private final PlaceDirectory places;

	private final CurrentUserProvider currentUser;

	SavedPlaceController(SavedPlaceService saved, PlaceDirectory places, CurrentUserProvider currentUser) {
		this.saved = saved;
		this.places = places;
		this.currentUser = currentUser;
	}

	/** @param place the landmark, when saved from one; otherwise latitude/longitude/note describe the pin */
	record SavedView(UUID id, SavedLabel label, String customLabel, PlaceSummary place, Double latitude,
			Double longitude, String note, Long version) {
	}

	@Operation(summary = "My saved places")
	@GetMapping
	List<SavedView> list() {
		List<SavedPlace> mine = saved.list(currentUser.require().userId());
		Map<UUID, PlaceSummary> byId = mine.stream()
			.map(SavedPlace::getPlaceId)
			.filter(java.util.Objects::nonNull)
			.distinct()
			.map(places::get)
			.flatMap(java.util.Optional::stream)
			.collect(Collectors.toMap(PlaceSummary::id, Function.identity()));
		return mine.stream().map(s -> view(s, s.getPlaceId() == null ? null : byId.get(s.getPlaceId()))).toList();
	}

	@Operation(summary = "Save a place",
			description = "HOME and OFFICE once each (`place.saved-label-taken`); CUSTOM needs `customLabel`. Give a "
					+ "landmark `placeId`, or your own `latitude`/`longitude` with a `note` (e.g. a house).")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SavedView create(@Valid @RequestBody SavedRequest request) {
		return view(saved.create(currentUser.require().userId(), request.input()));
	}

	@Operation(summary = "Edit a saved place", description = "`version` must match.")
	@PutMapping("/{id}")
	SavedView update(@PathVariable UUID id, @Valid @RequestBody UpdateSavedRequest request) {
		return view(saved.update(currentUser.require().userId(), id, request.input(), request.version()));
	}

	@Operation(summary = "Delete a saved place")
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID id) {
		saved.delete(currentUser.require().userId(), id);
	}

	private SavedView view(SavedPlace s) {
		return view(s, s.getPlaceId() == null ? null : places.get(s.getPlaceId()).orElse(null));
	}

	private static SavedView view(SavedPlace s, PlaceSummary place) {
		return new SavedView(s.getId(), s.getLabel(), s.getCustomLabel(), place, s.getLatitude(), s.getLongitude(),
				s.getNote(), s.getVersion());
	}

	record SavedRequest(@NotNull SavedLabel label, @Size(max = 50) String customLabel, UUID placeId, Double latitude,
			Double longitude, @Size(max = 500) String note) {

		SavedInput input() {
			return new SavedInput(label, customLabel, placeId, latitude, longitude, note);
		}
	}

	record UpdateSavedRequest(@NotNull Long version, @NotNull SavedLabel label, @Size(max = 50) String customLabel,
			UUID placeId, Double latitude, Double longitude, @Size(max = 500) String note) {

		SavedInput input() {
			return new SavedInput(label, customLabel, placeId, latitude, longitude, note);
		}
	}
}

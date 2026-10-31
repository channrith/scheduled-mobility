package com.mobility.core.place.saved;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.place.PlaceDirectory;
import com.mobility.core.place.PlaceDirectory.PlaceSummary;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user's own saved places (not a staff action, so not audited). */
@Service
public class SavedPlaceService {

	public record SavedInput(SavedLabel label, String customLabel, UUID placeId, Double latitude, Double longitude,
			String note) {
	}

	private final SavedPlaceRepository saved;

	private final PlaceDirectory places;

	SavedPlaceService(SavedPlaceRepository saved, PlaceDirectory places) {
		this.saved = saved;
		this.places = places;
	}

	@Transactional(readOnly = true)
	public List<SavedPlace> list(UUID userId) {
		return saved.findByUserIdOrderByCreatedAtAsc(userId);
	}

	@Transactional
	public SavedPlace create(UUID userId, SavedInput input) {
		SavedInput valid = validate(input);
		if (valid.label() != SavedLabel.CUSTOM && saved.existsByUserIdAndLabel(userId, valid.label())) {
			throw new ApiException(HttpStatus.CONFLICT, "place.saved-label-taken");
		}
		SavedPlace place = new SavedPlace(userId);
		apply(place, valid);
		return saved.saveAndFlush(place);
	}

	@Transactional
	public SavedPlace update(UUID userId, UUID id, SavedInput input, long expectedVersion) {
		SavedPlace place = get(userId, id);
		if (!Objects.equals(place.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		SavedInput valid = validate(input);
		if (valid.label() != SavedLabel.CUSTOM && saved.existsByUserIdAndLabelAndIdNot(userId, valid.label(), id)) {
			throw new ApiException(HttpStatus.CONFLICT, "place.saved-label-taken");
		}
		apply(place, valid);
		return saved.saveAndFlush(place);
	}

	@Transactional
	public void delete(UUID userId, UUID id) {
		saved.delete(get(userId, id));
	}

	/** @throws ApiException 404 also for another user's saved place */
	@Transactional(readOnly = true)
	public SavedPlace get(UUID userId, UUID id) {
		return saved.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "place.saved-not-found"));
	}

	private void apply(SavedPlace place, SavedInput in) {
		UUID placeId = null;
		if (in.placeId() != null) {
			// Store the active place (a merged id resolves to its target).
			placeId = places.get(in.placeId())
				.map(PlaceSummary::id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "place.not-found"));
		}
		place.apply(in.label(), in.label() == SavedLabel.CUSTOM ? in.customLabel() : null, placeId,
				placeId == null ? in.latitude() : null, placeId == null ? in.longitude() : null, in.note());
	}

	private static SavedInput validate(SavedInput in) {
		if (in == null || in.label() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		String custom = in.customLabel() == null || in.customLabel().isBlank() ? null : in.customLabel().strip();
		if (in.label() == SavedLabel.CUSTOM && custom == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "place.custom-label-required");
		}
		boolean hasPin = in.latitude() != null && in.longitude() != null;
		if (in.placeId() == null && !hasPin) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "place.saved-target-required");
		}
		if (hasPin && (Math.abs(in.latitude()) > 90 || Math.abs(in.longitude()) > 180)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		String note = in.note() == null || in.note().isBlank() ? null : in.note().strip();
		return new SavedInput(in.label(), custom, in.placeId(), in.latitude(), in.longitude(), note);
	}
}

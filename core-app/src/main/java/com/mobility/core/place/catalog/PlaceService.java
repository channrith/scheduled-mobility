package com.mobility.core.place.catalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.place.PlaceDirectory;
import com.mobility.core.place.PlaceType;
import com.mobility.core.place.catalog.Place.PlaceDetails;
import com.mobility.core.shared.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceService implements PlaceDirectory {

	/** Generous box around Cambodia: catches swapped latitude/longitude and typos. */
	static final double MIN_LAT = 9.5, MAX_LAT = 15.0, MIN_LNG = 102.0, MAX_LNG = 108.0;

	public record PlaceInput(String nameKm, String nameEn, Double latitude, Double longitude, PlaceType type,
			String pickupNotesKm, String pickupNotesEn, Boolean verified) {
	}

	private final PlaceRepository places;

	private final JdbcTemplate jdbc;

	private final AuditLog auditLog;

	PlaceService(PlaceRepository places, JdbcTemplate jdbc, AuditLog auditLog) {
		this.places = places;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
	}

	@Transactional
	public Place create(PlaceInput input) {
		Place place = places.saveAndFlush(new Place(validate(input)));
		auditLog.record("place.created", "place", place.getId(), Map.of("verified", place.isVerified()));
		return place;
	}

	@Transactional
	public Place update(UUID id, PlaceInput input, long expectedVersion) {
		Place place = require(id);
		if (!Objects.equals(place.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		if (!place.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "place.merged");
		}
		boolean wasVerified = place.isVerified();
		place.apply(validate(input));
		places.saveAndFlush(place);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("verified", place.isVerified());
		auditLog.record(!wasVerified && place.isVerified() ? "place.verified" : "place.updated", "place", id, details);
		return place;
	}

	/**
	 * Duplicate clean-up: {@code sourceId} stops appearing in search, its saved places and usage move to
	 * {@code targetId}, and its id keeps resolving to the target.
	 */
	@Transactional
	public Place merge(UUID sourceId, UUID targetId) {
		if (sourceId.equals(targetId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "place.merge-into-self");
		}
		Place source = require(sourceId);
		Place target = require(targetId);
		if (!source.isActive() || !target.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "place.merged");
		}
		source.mergeInto(target);
		places.saveAndFlush(source);
		places.saveAndFlush(target);
		places.repointMerged(sourceId, targetId);
		// Saved places belong to the saved-places feature of this module; one statement keeps them consistent.
		int moved = jdbc.update("UPDATE place.saved_places SET place_id = ?, version = version + 1 WHERE place_id = ?",
				targetId, sourceId);
		auditLog.record("place.merged", "place", sourceId, Map.of("intoPlaceId", targetId, "savedPlacesMoved", moved));
		return target;
	}

	/** @throws ApiException 404 */
	@Transactional(readOnly = true)
	public Place require(UUID id) {
		return places.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "place.not-found"));
	}

	/** The active place for an id, following a merge. */
	@Transactional(readOnly = true)
	public Optional<Place> resolve(UUID id) {
		Optional<Place> place = places.findById(id);
		if (place.isPresent() && !place.get().isActive()) {
			return places.findById(place.get().getMergedInto()).filter(Place::isActive);
		}
		return place;
	}

	@Transactional(readOnly = true)
	public Page<Place> list(String q, Boolean verified, PlaceType type, int page, int size) {
		String query = q == null ? "" : q.strip();
		return places.adminSearch("%" + query.toLowerCase(Locale.ROOT) + "%", "%" + query + "%", verified != null,
				verified != null && verified, type != null, type,
				PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("nameEn", "nameKm")));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PlaceSummary> get(UUID placeId) {
		return resolve(placeId).map(PlaceService::summary);
	}

	@Override
	@Transactional
	public void recordUsage(UUID placeId) {
		resolve(placeId).ifPresent(p -> places.incrementUsage(p.getId()));
	}

	static PlaceSummary summary(Place p) {
		return new PlaceSummary(p.getId(), p.getNameKm(), p.getNameEn(), p.getLatitude(), p.getLongitude(), p.getType(),
				p.getPickupNotesKm(), p.getPickupNotesEn(), p.isVerified());
	}

	private static PlaceDetails validate(PlaceInput in) {
		String km = blankToNull(in.nameKm());
		String en = blankToNull(in.nameEn());
		if (km == null && en == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "place.name-required");
		}
		if (in.latitude() == null || in.longitude() == null || in.type() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		if (in.latitude() < MIN_LAT || in.latitude() > MAX_LAT || in.longitude() < MIN_LNG || in.longitude() > MAX_LNG) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "place.outside-cambodia");
		}
		return new PlaceDetails(km, en, in.latitude(), in.longitude(), in.type(), blankToNull(in.pickupNotesKm()),
				blankToNull(in.pickupNotesEn()), in.verified() != null && in.verified());
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}
}

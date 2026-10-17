package com.mobility.core.place;

import java.util.Optional;
import java.util.UUID;

/** Landmark lookups for other modules (booking stores our place ids and records usage). */
public interface PlaceDirectory {

	record PlaceSummary(UUID id, String nameKm, String nameEn, double latitude, double longitude, PlaceType type,
			String pickupNotesKm, String pickupNotesEn, boolean verified) {
	}

	/** Follows merges: an id of a merged place returns the place it was merged into. */
	Optional<PlaceSummary> get(UUID placeId);

	/** Called when a booking uses the place; feeds search ranking. */
	void recordUsage(UUID placeId);
}

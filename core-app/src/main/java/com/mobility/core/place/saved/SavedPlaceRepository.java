package com.mobility.core.place.saved;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SavedPlaceRepository extends JpaRepository<SavedPlace, UUID> {

	List<SavedPlace> findByUserIdOrderByCreatedAtAsc(UUID userId);

	/** Owner-scoped: another user's saved place is "not found". */
	Optional<SavedPlace> findByIdAndUserId(UUID id, UUID userId);

	boolean existsByUserIdAndLabel(UUID userId, SavedLabel label);

	boolean existsByUserIdAndLabelAndIdNot(UUID userId, SavedLabel label, UUID id);
}

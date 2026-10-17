package com.mobility.core.place.catalog;

import java.util.UUID;

import com.mobility.core.place.PlaceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PlaceRepository extends JpaRepository<Place, UUID> {

	/**
	 * Admin list of active places. {@code pattern} is a lowercase LIKE pattern ("%" for all); {@code verified}
	 * and {@code type} filter only when {@code filterVerified}/{@code filterType} are true.
	 */
	@Query("""
			select p from Place p
			where p.status = com.mobility.core.place.catalog.PlaceStatus.ACTIVE
			  and (lower(coalesce(p.nameEn, '')) like :pattern or coalesce(p.nameKm, '') like :rawPattern)
			  and (:filterVerified = false or p.verified = :verified)
			  and (:filterType = false or p.type = :type)""")
	Page<Place> adminSearch(String pattern, String rawPattern, boolean filterVerified, boolean verified,
			boolean filterType, PlaceType type, Pageable pageable);

	@Modifying
	@Query("update Place p set p.usageCount = p.usageCount + 1 where p.id = :id")
	int incrementUsage(UUID id);

	/** Places merged into {@code from} now point at {@code to} (keeps merge chains one level deep). */
	@Modifying
	@Query("update Place p set p.mergedInto = :to where p.mergedInto = :from")
	int repointMerged(UUID from, UUID to);
}

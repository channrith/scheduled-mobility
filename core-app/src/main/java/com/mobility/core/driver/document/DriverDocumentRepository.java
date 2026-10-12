package com.mobility.core.driver.document;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DriverDocumentRepository extends JpaRepository<DriverDocument, UUID> {

	/** Current documents: everything that is not superseded. */
	@Query("select d from DriverDocument d where d.driverId = :driverId and d.status <> 'SUPERSEDED' order by d.type")
	List<DriverDocument> findCurrent(UUID driverId);

	@Query("""
			select d from DriverDocument d where d.driverId = :driverId and d.type = :type
			and (d.vehicleId = :vehicleId or (:vehicleId is null and d.vehicleId is null))
			and d.status <> 'SUPERSEDED'""")
	Optional<DriverDocument> findCurrent(UUID driverId, DocumentType type, UUID vehicleId);

	Optional<DriverDocument> findByIdAndDriverId(UUID id, UUID driverId);

	@Query("select d from DriverDocument d where (:status is null or d.status = :status) and (:type is null or d.type = :type)")
	Page<DriverDocument> findByFilters(DocumentStatus status, DocumentType type, Pageable pageable);

	@Query("""
			select distinct d.driverId from DriverDocument d where d.driverId in :driverIds and d.status = 'APPROVED'
			and d.expiresOn is not null and d.expiresOn <= :cutoff""")
	List<UUID> findDriversWithApprovedExpiringBy(Collection<UUID> driverIds, LocalDate cutoff);
}

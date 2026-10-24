package com.mobility.core.dispatch.assignment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TripAssignmentRepository extends JpaRepository<TripAssignment, UUID> {

	List<TripAssignment> findByTripIdIn(Collection<UUID> tripIds);

	/** Trips where the driver is primary or backup. */
	@org.springframework.data.jpa.repository.Query("""
			select a from TripAssignment a where a.primaryDriverId = :driverId or a.backupDriverId = :driverId""")
	List<TripAssignment> forDriver(@org.springframework.data.repository.query.Param("driverId") UUID driverId);

	/** Assignments whose primary or backup has not confirmed by its deadline. */
	@org.springframework.data.jpa.repository.Query("""
			select a from TripAssignment a
			where (a.primaryDriverId is not null and a.primaryConfirmedAt is null and a.primaryDeadline <= :now)
			   or (a.backupDriverId is not null and a.backupConfirmedAt is null and a.backupDeadline <= :now)""")
	List<TripAssignment> unconfirmedBy(@org.springframework.data.repository.query.Param("now") java.time.Instant now);
}

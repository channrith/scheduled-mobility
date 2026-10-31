package com.mobility.core.booking.trip;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<Trip, UUID> {

	List<Trip> findByRideIdOrderByOccurrence(UUID rideId);

	List<Trip> findByRideIdInOrderByPickupAt(Collection<UUID> rideIds);

	@Query("""
			select t from Trip t where t.driverId = :driverId and t.status in :statuses and t.pickupAt >= :from
			order by t.pickupAt""")
	List<Trip> forDriver(@Param("driverId") UUID driverId, @Param("statuses") Collection<TripStatus> statuses,
			@Param("from") Instant from);

	/** Trips of rides the user booked or rides in. */
	@Query("""
			select t from Trip t, com.mobility.core.booking.ride.Ride r
			where t.rideId = r.id and (r.bookedBy = :userId or r.riderUserId = :userId)
			  and t.status in :statuses and t.pickupAt >= :from
			order by t.pickupAt""")
	Page<Trip> forUser(@Param("userId") UUID userId, @Param("statuses") Collection<TripStatus> statuses,
			@Param("from") Instant from, Pageable pageable);

	@Query("""
			select t from Trip t where t.status in :statuses and t.pickupAt >= :from and t.pickupAt < :to""")
	Page<Trip> forOps(@Param("statuses") Collection<TripStatus> statuses, @Param("from") Instant from, @Param("to") Instant to,
			Pageable pageable);
}

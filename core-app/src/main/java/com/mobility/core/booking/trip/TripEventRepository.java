package com.mobility.core.booking.trip;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TripEventRepository extends JpaRepository<TripEvent, UUID> {

	List<TripEvent> findByTripIdInOrderBySeq(Collection<UUID> tripIds);
}

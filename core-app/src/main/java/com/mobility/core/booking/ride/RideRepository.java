package com.mobility.core.booking.ride;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<Ride, UUID> {

	boolean existsByQuoteId(UUID quoteId);
}

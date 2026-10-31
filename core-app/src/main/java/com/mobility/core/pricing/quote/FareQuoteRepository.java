package com.mobility.core.pricing.quote;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FareQuoteRepository extends JpaRepository<FareQuote, UUID> {
}

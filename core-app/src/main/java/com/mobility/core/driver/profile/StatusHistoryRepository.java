package com.mobility.core.driver.profile;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface StatusHistoryRepository extends JpaRepository<StatusHistoryEntry, UUID> {

	List<StatusHistoryEntry> findByDriverIdOrderByOccurredAtAsc(UUID driverId);
}

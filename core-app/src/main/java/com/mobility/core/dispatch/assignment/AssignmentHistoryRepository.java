package com.mobility.core.dispatch.assignment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentHistoryRepository extends JpaRepository<AssignmentHistoryEntry, UUID> {

	List<AssignmentHistoryEntry> findByTripIdOrderBySeq(UUID tripId);
}

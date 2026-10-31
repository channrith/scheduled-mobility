package com.mobility.core.dispatch.issue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, UUID> {

	@Query("""
			select e from ExceptionCase e where e.tripId = :tripId and e.type = :type
			  and e.status in (com.mobility.core.dispatch.issue.ExceptionStatus.OPEN, com.mobility.core.dispatch.issue.ExceptionStatus.ACKNOWLEDGED)""")
	Optional<ExceptionCase> findOpen(@Param("tripId") UUID tripId, @Param("type") ExceptionType type);

	@Query("""
			select e from ExceptionCase e where e.tripId = :tripId and e.type in :types
			  and e.status in (com.mobility.core.dispatch.issue.ExceptionStatus.OPEN, com.mobility.core.dispatch.issue.ExceptionStatus.ACKNOWLEDGED)""")
	List<ExceptionCase> findOpen(@Param("tripId") UUID tripId, @Param("types") Collection<ExceptionType> types);

	/** Severity first (CRITICAL … LOW), then the soonest pickup. */
	@Query("""
			select e from ExceptionCase e where e.status in :statuses
			order by case e.severity when com.mobility.core.dispatch.issue.Severity.CRITICAL then 0
			                         when com.mobility.core.dispatch.issue.Severity.HIGH then 1
			                         when com.mobility.core.dispatch.issue.Severity.MEDIUM then 2 else 3 end,
			         e.pickupAt, e.openedAt""")
	List<ExceptionCase> queue(@Param("statuses") Collection<ExceptionStatus> statuses);
}

package com.mobility.core.dispatch.shift;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShiftBlockRepository extends JpaRepository<ShiftBlock, UUID> {

	/** Active blocks overlapping [from, to). */
	@Query("""
			select s from ShiftBlock s where s.status = 'ACTIVE' and s.startsAt < :to and s.endsAt > :from
			order by s.driverId, s.startsAt""")
	List<ShiftBlock> overlapping(@Param("from") Instant from, @Param("to") Instant to);

	@Query("""
			select s from ShiftBlock s where s.status = 'ACTIVE' and s.driverId in :drivers and s.startsAt < :to and s.endsAt > :from
			order by s.startsAt""")
	List<ShiftBlock> overlapping(@Param("drivers") Collection<UUID> drivers, @Param("from") Instant from, @Param("to") Instant to);

	@Query("select s from ShiftBlock s where s.driverId = :driverId and s.status = 'ACTIVE' and s.endsAt > :from order by s.startsAt")
	List<ShiftBlock> upcoming(@Param("driverId") UUID driverId, @Param("from") Instant from);
}

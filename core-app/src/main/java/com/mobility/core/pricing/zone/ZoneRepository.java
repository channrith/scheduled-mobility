package com.mobility.core.pricing.zone;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ZoneRepository extends JpaRepository<Zone, UUID> {

	List<Zone> findByCityInOrderByTypeAscNameEnAsc(List<String> cities);
}

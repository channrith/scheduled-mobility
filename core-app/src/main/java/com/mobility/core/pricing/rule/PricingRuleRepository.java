package com.mobility.core.pricing.rule;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.VehicleClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

	/** Versions of a line, newest-starting first (ties: newest created first). */
	@Query("""
			select r from PricingRule r
			where r.city = :city and r.vehicleClass = :vehicleClass
			  and ((:corporateId is null and r.corporateId is null) or r.corporateId = :corporateId)
			order by r.validFrom desc, r.seq desc""")
	List<PricingRule> line(@Param("city") String city, @Param("vehicleClass") VehicleClass vehicleClass,
			@Param("corporateId") UUID corporateId);

	/** City lines ({@code corporateId} null) or one company's overrides. */
	@Query("""
			select r from PricingRule r
			where r.city in :cities and r.vehicleClass in :classes
			  and ((:corporateId is null and r.corporateId is null) or r.corporateId = :corporateId)""")
	List<PricingRule> lines(@Param("cities") Collection<String> cities, @Param("classes") Collection<VehicleClass> classes,
			@Param("corporateId") UUID corporateId);
}

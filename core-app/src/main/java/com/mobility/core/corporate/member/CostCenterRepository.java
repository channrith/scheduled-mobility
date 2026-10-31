package com.mobility.core.corporate.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CostCenterRepository extends JpaRepository<CostCenter, UUID> {

	List<CostCenter> findByAccountIdOrderByCode(UUID accountId);

	/** Every lookup is scoped to the account, so another company's id is simply not found. */
	Optional<CostCenter> findByIdAndAccountId(UUID id, UUID accountId);

	boolean existsByAccountIdAndCode(UUID accountId, String code);
}

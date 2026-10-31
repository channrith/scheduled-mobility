package com.mobility.core.corporate;

import java.util.Optional;
import java.util.UUID;

/** Read-only member and cost-centre lookups for other modules (booking: who rides, and where it is charged). */
public interface CorporateMembers {

	/** @param costCenterId the member's own cost centre; null when none */
	record MemberSummary(UUID memberId, UUID corporateId, UUID userId, MemberRole role, boolean active, UUID costCenterId) {
	}

	record CostCenterSummary(UUID costCenterId, UUID corporateId, String code, String name, boolean active) {
	}

	/** Only a member of that company; another company's id is empty. */
	Optional<MemberSummary> member(UUID corporateId, UUID memberId);

	Optional<MemberSummary> memberByUser(UUID corporateId, UUID userId);

	/** Only a cost centre of that company; another company's id is empty. */
	Optional<CostCenterSummary> costCenter(UUID corporateId, UUID costCenterId);
}

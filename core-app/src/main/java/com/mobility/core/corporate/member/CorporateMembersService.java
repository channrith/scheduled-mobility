package com.mobility.core.corporate.member;

import java.util.Optional;
import java.util.UUID;

import com.mobility.core.corporate.CorporateMembers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CorporateMembersService implements CorporateMembers {

	private final CorporateMemberRepository members;

	private final CostCenterRepository costCenters;

	CorporateMembersService(CorporateMemberRepository members, CostCenterRepository costCenters) {
		this.members = members;
		this.costCenters = costCenters;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MemberSummary> member(UUID corporateId, UUID memberId) {
		return members.findByIdAndAccountId(memberId, corporateId).map(CorporateMembersService::summary);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MemberSummary> memberByUser(UUID corporateId, UUID userId) {
		return members.findByAccountIdAndUserId(corporateId, userId).map(CorporateMembersService::summary);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CostCenterSummary> costCenter(UUID corporateId, UUID costCenterId) {
		return costCenters.findByIdAndAccountId(costCenterId, corporateId)
			.map(c -> new CostCenterSummary(c.getId(), c.getAccountId(), c.getCode(), c.getName(), c.isActive()));
	}

	private static MemberSummary summary(CorporateMember m) {
		return new MemberSummary(m.getId(), m.getAccountId(), m.getUserId(), m.getRole(), m.isActive(), m.getCostCenterId());
	}
}

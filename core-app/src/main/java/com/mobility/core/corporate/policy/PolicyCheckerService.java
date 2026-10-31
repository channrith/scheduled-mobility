package com.mobility.core.corporate.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mobility.core.corporate.AccountStatus;
import com.mobility.core.corporate.PolicyChecker;
import com.mobility.core.corporate.PolicyViolation;
import com.mobility.core.corporate.RideRequest;
import com.mobility.core.corporate.account.CorporateAccount;
import com.mobility.core.corporate.account.CorporateAccountRepository;
import com.mobility.core.corporate.member.CorporateMember;
import com.mobility.core.corporate.member.CorporateMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PolicyCheckerService implements PolicyChecker {

	private final CorporateAccountRepository accounts;

	private final CorporateMemberRepository members;

	PolicyCheckerService(CorporateAccountRepository accounts, CorporateMemberRepository members) {
		this.accounts = accounts;
		this.members = members;
	}

	@Override
	@Transactional(readOnly = true)
	public PolicyDecision check(UUID corporateId, UUID riderUserId, RideRequest ride) {
		List<PolicyViolation> violations = new ArrayList<>();
		CorporateAccount account = accounts.findById(corporateId).orElse(null);
		if (account == null) {
			return PolicyDecision.of(List.of(PolicyViolation.NOT_A_MEMBER));
		}
		if (account.getStatus() != AccountStatus.ACTIVE) {
			violations.add(PolicyViolation.ACCOUNT_SUSPENDED);
		}
		CorporateMember member = members.findByAccountIdAndUserId(corporateId, riderUserId).orElse(null);
		if (member == null) {
			violations.add(PolicyViolation.NOT_A_MEMBER);
		}
		else if (!member.isActive()) {
			violations.add(PolicyViolation.MEMBER_REMOVED);
		}
		else {
			violations.addAll(member.getPolicy().evaluate(ride));
		}
		return PolicyDecision.of(violations);
	}
}

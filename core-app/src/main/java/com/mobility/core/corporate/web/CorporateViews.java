package com.mobility.core.corporate.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.corporate.AccountStatus;
import com.mobility.core.corporate.MemberPolicy;
import com.mobility.core.corporate.MemberRole;
import com.mobility.core.corporate.account.AccountStatusHistory;
import com.mobility.core.corporate.account.CorporateAccount;
import com.mobility.core.corporate.member.CorporateMember;
import com.mobility.core.corporate.member.CostCenter;
import com.mobility.core.corporate.member.MemberStatus;
import com.mobility.core.identity.UserDirectory;
import com.mobility.core.identity.UserDirectory.UserSummary;
import org.springframework.stereotype.Component;

@Component
class CorporateViews {

	record AccountSuspensionView(String reason, Instant suspendedAt, UUID suspendedBy) {
	}

	/** @param version send back when editing (optimistic locking) */
	record AccountView(UUID id, String legalName, String displayName, String taxId, int paymentTermsDays,
			long creditLimitMinor, String currency, BigDecimal serviceFeePct, AccountStatus status,
			AccountSuspensionView suspension, Long version, Instant createdAt) {
	}

	record AccountHistoryView(AccountStatus from, AccountStatus to, String reason, UUID actorUserId, Instant occurredAt) {
	}

	record AccountDetail(AccountView account, long activeMembers, List<AccountHistoryView> history) {
	}

	record CostCenterView(UUID id, String code, String name, boolean active, Long version) {
	}

	record CostCenterBrief(UUID id, String code, String name) {
	}

	record MemberView(UUID id, UUID userId, String fullName, String phone, MemberRole role, CostCenterBrief costCenter,
			String employeeRef, MemberPolicy policy, MemberStatus status, Instant createdAt, Long version) {
	}

	private final UserDirectory users;

	CorporateViews(UserDirectory users) {
		this.users = users;
	}

	static AccountView account(CorporateAccount a, boolean forStaff) {
		AccountSuspensionView suspension = a.getSuspendedAt() == null ? null
				: new AccountSuspensionView(a.getSuspensionReason(), a.getSuspendedAt(), forStaff ? a.getSuspendedBy() : null);
		return new AccountView(a.getId(), a.getLegalName(), a.getDisplayName(), a.getTaxId(), a.getPaymentTermsDays(),
				a.getCreditLimitMinor(), a.getCurrency(), a.getServiceFeePct(), a.getStatus(), suspension, a.getVersion(),
				a.getCreatedAt());
	}

	static List<AccountHistoryView> history(List<AccountStatusHistory> entries) {
		return entries.stream()
			.map(h -> new AccountHistoryView(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getActorUserId(), h.getOccurredAt()))
			.toList();
	}

	static CostCenterView costCenter(CostCenter c) {
		return new CostCenterView(c.getId(), c.getCode(), c.getName(), c.isActive(), c.getVersion());
	}

	/** One lookup of names and phones for a list of members. */
	List<MemberView> members(List<CorporateMember> members, List<CostCenter> costCenters) {
		Map<UUID, UserSummary> people = users.find(members.stream().map(CorporateMember::getUserId).toList());
		Map<UUID, CostCenter> centers = costCenters.stream().collect(Collectors.toMap(CostCenter::getId, Function.identity()));
		return members.stream().map(m -> {
			UserSummary person = people.get(m.getUserId());
			CostCenter center = m.getCostCenterId() == null ? null : centers.get(m.getCostCenterId());
			return new MemberView(m.getId(), m.getUserId(), person == null ? null : person.fullName(),
					person == null ? null : person.phoneE164(), m.getRole(),
					center == null ? null : new CostCenterBrief(center.getId(), center.getCode(), center.getName()),
					m.getEmployeeRef(), m.getPolicy(), m.getStatus(), m.getCreatedAt(), m.getVersion());
		}).toList();
	}
}

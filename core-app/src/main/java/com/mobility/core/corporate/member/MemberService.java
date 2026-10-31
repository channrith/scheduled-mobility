package com.mobility.core.corporate.member;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.corporate.MemberPolicy;
import com.mobility.core.corporate.MemberRole;
import com.mobility.core.corporate.account.CorporateAccountService;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.identity.UserAccounts.UserAccount;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Members of a corporate account. Admins and bookers hold the matching identity role scoped to the account
 * (granted and revoked here, in the same transaction); riders keep a normal passenger login.
 */
@Service
public class MemberService {

	public record MemberInput(MemberRole role, UUID costCenterId, String employeeRef, MemberPolicy policy) {
	}

	private final CorporateMemberRepository members;

	private final CostCenterService costCenters;

	private final CorporateAccountService accounts;

	private final UserAccounts userAccounts;

	private final AuditLog auditLog;

	private final Clock clock;

	MemberService(CorporateMemberRepository members, CostCenterService costCenters, CorporateAccountService accounts,
			UserAccounts userAccounts, AuditLog auditLog, Clock clock) {
		this.members = members;
		this.costCenters = costCenters;
		this.accounts = accounts;
		this.userAccounts = userAccounts;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CorporateMember> list(UUID corporateId, boolean includeRemoved) {
		accounts.get(corporateId);
		return includeRemoved ? members.findByAccountIdOrderByCreatedAtAsc(corporateId)
				: members.findByAccountIdAndStatusOrderByCreatedAtAsc(corporateId, MemberStatus.ACTIVE);
	}

	/** @throws ApiException 404 also when the member belongs to another company */
	@Transactional(readOnly = true)
	public CorporateMember get(UUID corporateId, UUID memberId) {
		return members.findByIdAndAccountId(memberId, corporateId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "corporate.member-not-found"));
	}

	/**
	 * Adds a member by phone, reusing an existing user. A previously removed member of this company is restored
	 * with the new settings.
	 */
	@Transactional
	public CorporateMember add(UUID corporateId, String phone, String fullName, MemberInput input) {
		accounts.requireActive(corporateId);
		MemberInput valid = validate(corporateId, input);
		Role identityRole = valid.role().identityRole();
		UserAccount user = identityRole != null
				? userAccounts.ensureUserWithScopedRole(phone, fullName, identityRole, corporateId)
				: userAccounts.ensureUserWithRole(phone, fullName, Role.PASSENGER);
		CorporateMember member = members.findByAccountIdAndUserId(corporateId, user.userId()).orElse(null);
		if (member != null && member.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.member-exists");
		}
		if (member == null) {
			member = new CorporateMember(corporateId, user.userId());
		}
		else {
			member.restore();
		}
		member.apply(valid.role(), valid.costCenterId(), valid.employeeRef(), valid.policy());
		members.saveAndFlush(member);
		auditLog.record("corporate.member.added", "corporate_member", member.getId(),
				Map.of("corporateId", corporateId, "role", valid.role()));
		return member;
	}

	/** Platform ADMIN: make this phone a company admin; an existing member is promoted. */
	@Transactional
	public CorporateMember addAdmin(UUID corporateId, String phone, String fullName) {
		accounts.requireActive(corporateId);
		UserAccount user = userAccounts.ensureUserWithScopedRole(phone, fullName, Role.CORPORATE_ADMIN, corporateId);
		CorporateMember existing = members.findByAccountIdAndUserId(corporateId, user.userId()).orElse(null);
		if (existing != null && existing.isActive()) {
			if (existing.getRole() != MemberRole.CORPORATE_ADMIN) {
				changeIdentityRole(existing, existing.getRole(), MemberRole.CORPORATE_ADMIN, corporateId);
				existing.apply(MemberRole.CORPORATE_ADMIN, existing.getCostCenterId(), existing.getEmployeeRef(),
						existing.getPolicy());
				members.saveAndFlush(existing);
				auditLog.record("corporate.member.updated", "corporate_member", existing.getId(),
						Map.of("corporateId", corporateId, "role", MemberRole.CORPORATE_ADMIN));
			}
			return existing;
		}
		return add(corporateId, phone, fullName, new MemberInput(MemberRole.CORPORATE_ADMIN, null, null, null));
	}

	@Transactional
	public CorporateMember update(UUID corporateId, UUID memberId, MemberInput input, long expectedVersion) {
		accounts.requireActive(corporateId);
		CorporateMember member = get(corporateId, memberId);
		if (!Objects.equals(member.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		if (!member.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.member-removed");
		}
		MemberInput valid = validate(corporateId, input);
		if (member.getRole() == MemberRole.CORPORATE_ADMIN && valid.role() != MemberRole.CORPORATE_ADMIN) {
			requireAnotherAdmin(corporateId);
		}
		if (member.getRole() != valid.role()) {
			changeIdentityRole(member, member.getRole(), valid.role(), corporateId);
		}
		member.apply(valid.role(), valid.costCenterId(), valid.employeeRef(), valid.policy());
		members.saveAndFlush(member);
		auditLog.record("corporate.member.updated", "corporate_member", memberId,
				Map.of("corporateId", corporateId, "role", valid.role()));
		return member;
	}

	@Transactional
	public void remove(UUID corporateId, UUID memberId) {
		accounts.requireActive(corporateId);
		CorporateMember member = get(corporateId, memberId);
		if (!member.isActive()) {
			return;
		}
		if (member.getRole() == MemberRole.CORPORATE_ADMIN) {
			requireAnotherAdmin(corporateId);
		}
		if (member.getRole().identityRole() != null) {
			userAccounts.revokeScopedRole(member.getUserId(), member.getRole().identityRole(), corporateId);
		}
		member.remove(clock.instant());
		members.saveAndFlush(member);
		auditLog.record("corporate.member.removed", "corporate_member", memberId, Map.of("corporateId", corporateId));
	}

	private void changeIdentityRole(CorporateMember member, MemberRole from, MemberRole to, UUID corporateId) {
		if (from.identityRole() != null) {
			userAccounts.revokeScopedRole(member.getUserId(), from.identityRole(), corporateId);
		}
		if (to.identityRole() != null) {
			userAccounts.grantRole(member.getUserId(), to.identityRole(), corporateId);
		}
		else {
			userAccounts.grantRole(member.getUserId(), Role.PASSENGER, null);
		}
	}

	/** A company always keeps at least one active admin. */
	private void requireAnotherAdmin(UUID corporateId) {
		if (members.countByAccountIdAndRoleAndStatus(corporateId, MemberRole.CORPORATE_ADMIN, MemberStatus.ACTIVE) <= 1) {
			throw new ApiException(HttpStatus.CONFLICT, "corporate.last-admin");
		}
	}

	private MemberInput validate(UUID corporateId, MemberInput input) {
		if (input == null || input.role() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		if (input.costCenterId() != null) {
			// Scoped lookup: a cost centre of another company is "not found" here too.
			CostCenter costCenter = costCenters.get(corporateId, input.costCenterId());
			if (!costCenter.isActive()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "corporate.cost-center-inactive");
			}
		}
		MemberPolicy policy = input.policy() == null ? MemberPolicy.UNRESTRICTED : input.policy().validated();
		String ref = input.employeeRef() == null || input.employeeRef().isBlank() ? null : input.employeeRef().strip();
		return new MemberInput(input.role(), input.costCenterId(), ref, policy);
	}
}

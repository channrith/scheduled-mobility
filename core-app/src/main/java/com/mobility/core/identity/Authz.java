package com.mobility.core.identity;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Authorization checks for {@code @PreAuthorize}, e.g.
 * {@code @PreAuthorize("@authz.canAccessCorporate(#corporateId)")}. Use plain
 * {@code hasRole('DISPATCHER')} / {@code hasAnyRole(...)} for role-only checks.
 */
@Component("authz")
public class Authz {

	private final CurrentUserProvider currentUser;

	Authz(CurrentUserProvider currentUser) {
		this.currentUser = currentUser;
	}

	/** Staff ({@link Role#STAFF}) act across all corporate clients. */
	public boolean isStaff() {
		return currentUser.current().map(CurrentUser::isStaff).orElse(false);
	}

	/** Staff, or a corporate admin/booker of exactly this corporate client. */
	public boolean canAccessCorporate(UUID corporateId) {
		if (corporateId == null) {
			return false;
		}
		return currentUser.current()
			.map(u -> u.isStaff() || u.corporateIds().contains(corporateId))
			.orElse(false);
	}

	/** Managing a corporate account (users, cost centres, billing): platform ADMIN or its CORPORATE_ADMIN. */
	public boolean canManageCorporate(UUID corporateId) {
		if (corporateId == null) {
			return false;
		}
		return currentUser.current()
			.map(u -> u.hasRole(Role.ADMIN) || u.hasCorporateRole(corporateId, Role.CORPORATE_ADMIN))
			.orElse(false);
	}

	public boolean isSelf(UUID userId) {
		return userId != null && currentUser.current().map(u -> u.userId().equals(userId)).orElse(false);
	}
}

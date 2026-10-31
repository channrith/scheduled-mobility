package com.mobility.core.corporate;

import com.mobility.core.identity.Role;

/**
 * A member's role in a corporate account. Admins and bookers hold the matching identity role scoped to the
 * account; riders are employees who ride on the account with their normal passenger login.
 */
public enum MemberRole {

	CORPORATE_ADMIN(Role.CORPORATE_ADMIN), CORPORATE_BOOKER(Role.CORPORATE_BOOKER), RIDER(null);

	private final Role identityRole;

	MemberRole(Role identityRole) {
		this.identityRole = identityRole;
	}

	/** The scoped identity role this member holds, or null for riders. */
	public Role identityRole() {
		return identityRole;
	}
}

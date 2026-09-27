package com.mobility.core.identity;

import java.util.EnumSet;
import java.util.Set;

public enum Role {

	PASSENGER, DRIVER, CORPORATE_ADMIN, CORPORATE_BOOKER, DISPATCHER, SUPPORT, SAFETY_OFFICER, ADMIN;

	/** Roles scoped to a single corporate client; their grants always carry a corporate_id. */
	public static final Set<Role> CORPORATE = EnumSet.of(CORPORATE_ADMIN, CORPORATE_BOOKER);

	/** Internal staff roles; they may act across all corporate clients. */
	public static final Set<Role> STAFF = EnumSet.of(ADMIN, DISPATCHER, SUPPORT, SAFETY_OFFICER);

	public boolean isCorporate() {
		return CORPORATE.contains(this);
	}

	public boolean isStaff() {
		return STAFF.contains(this);
	}
}

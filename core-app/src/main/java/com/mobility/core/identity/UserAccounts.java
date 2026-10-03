package com.mobility.core.identity;

import java.util.UUID;

/** Staff-side account management for other modules (e.g. driver onboarding creates the driver's login). */
public interface UserAccounts {

	record UserAccount(UUID userId, String phoneE164) {
	}

	/**
	 * Finds the user by phone (any common Cambodian format) or creates one, and grants {@code role}.
	 * Must run in the caller's transaction; the role grant is audited.
	 *
	 * @throws com.mobility.core.shared.web.ApiException 400 {@code phone.invalid}
	 */
	UserAccount ensureUserWithRole(String phone, String fullName, Role role);
}

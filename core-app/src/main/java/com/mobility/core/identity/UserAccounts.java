package com.mobility.core.identity;

import java.util.UUID;

import com.mobility.core.shared.i18n.Language;

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

	/**
	 * Like {@link #ensureUserWithRole}, for roles scoped to one corporate client ({@link Role#CORPORATE}):
	 * the grant carries {@code scopeId} (the corporate account id). Audited.
	 */
	UserAccount ensureUserWithScopedRole(String phone, String fullName, Role role, UUID scopeId);

	/**
	 * Removes a scoped role (e.g. a member leaving a company). No-op if not held. Access tokens already issued
	 * keep the role until they expire (≤ 15 min); refreshed tokens no longer carry it. Audited.
	 */
	void revokeScopedRole(UUID userId, Role role, UUID scopeId);

	/**
	 * Grants a role to an existing user; {@code scopeId} is required for corporate roles and must be null for
	 * others. No-op if already held. Audited.
	 */
	void grantRole(UUID userId, Role role, UUID scopeId);

	/**
	 * Staff edit of a user's profile; {@code null} leaves a field unchanged. Must run in the caller's
	 * transaction; audited with the changed field names.
	 */
	void updateProfile(UUID userId, String fullName, Language preferredLanguage);
}

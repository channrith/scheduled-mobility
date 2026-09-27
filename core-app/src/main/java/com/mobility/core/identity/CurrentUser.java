package com.mobility.core.identity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.shared.i18n.Language;

/**
 * The authenticated caller, as asserted by their access token (up to 15 minutes old).
 *
 * @param corporateRoles corporate_id → corporate roles held for that client
 */
public record CurrentUser(UUID userId, Set<Role> roles, Map<UUID, Set<Role>> corporateRoles, Language language) {

	public CurrentUser {
		roles = Set.copyOf(roles);
		corporateRoles = Map.copyOf(corporateRoles);
	}

	public boolean hasRole(Role role) {
		return roles.contains(role);
	}

	public boolean isStaff() {
		return roles.stream().anyMatch(Role::isStaff);
	}

	public Set<UUID> corporateIds() {
		return corporateRoles.keySet();
	}

	public boolean hasCorporateRole(UUID corporateId, Role role) {
		return corporateRoles.getOrDefault(corporateId, Set.of()).contains(role);
	}
}

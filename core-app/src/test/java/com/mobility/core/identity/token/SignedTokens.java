package com.mobility.core.identity.token;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mobility.core.identity.Role;

/** Mints real signed access tokens for arbitrary role sets, without creating users. */
public final class SignedTokens {

	private SignedTokens() {
	}

	public static String withRoles(AccessTokenIssuer issuer, Role... roles) {
		return issuer.issue(UUID.randomUUID(), names(roles), Map.of(), "en").value();
	}

	public static String corporate(AccessTokenIssuer issuer, UUID corporateId, Role... roles) {
		List<String> names = names(roles);
		return issuer.issue(UUID.randomUUID(), names, Map.of(corporateId.toString(), names), "en").value();
	}

	private static List<String> names(Role... roles) {
		return Arrays.stream(roles).map(Role::name).sorted().collect(Collectors.toList());
	}
}

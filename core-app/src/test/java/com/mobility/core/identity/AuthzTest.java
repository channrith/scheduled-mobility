package com.mobility.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthzTest {

	static final UUID CORP_A = UUID.randomUUID();

	static final UUID CORP_B = UUID.randomUUID();

	final Authz authz = new Authz(new CurrentUserProvider());

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER" })
	void staffCanAccessAnyCorporate(Role staff) {
		authenticate(UUID.randomUUID(), List.of(staff.name()), Map.of());

		assertThat(authz.isStaff()).isTrue();
		assertThat(authz.canAccessCorporate(CORP_A)).isTrue();
		assertThat(authz.canAccessCorporate(CORP_B)).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "CORPORATE_ADMIN", "CORPORATE_BOOKER" })
	void corporateUsersOnlyAccessTheirOwnCorporate(Role role) {
		authenticate(UUID.randomUUID(), List.of(role.name()), Map.of(CORP_A.toString(), List.of(role.name())));

		assertThat(authz.isStaff()).isFalse();
		assertThat(authz.canAccessCorporate(CORP_A)).isTrue();
		assertThat(authz.canAccessCorporate(CORP_B)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "PASSENGER", "DRIVER" })
	void passengersAndDriversCannotAccessCorporates(Role role) {
		authenticate(UUID.randomUUID(), List.of(role.name()), Map.of());

		assertThat(authz.isStaff()).isFalse();
		assertThat(authz.canAccessCorporate(CORP_A)).isFalse();
	}

	@Test
	void onlyPlatformAdminOrThatCorporatesAdminCanManageIt() {
		authenticate(UUID.randomUUID(), List.of("CORPORATE_ADMIN", "CORPORATE_BOOKER"),
				Map.of(CORP_A.toString(), List.of("CORPORATE_ADMIN"), CORP_B.toString(), List.of("CORPORATE_BOOKER")));
		assertThat(authz.canManageCorporate(CORP_A)).isTrue();
		assertThat(authz.canManageCorporate(CORP_B)).isFalse();

		authenticate(UUID.randomUUID(), List.of("ADMIN"), Map.of());
		assertThat(authz.canManageCorporate(CORP_B)).isTrue();

		authenticate(UUID.randomUUID(), List.of("DISPATCHER"), Map.of());
		assertThat(authz.canManageCorporate(CORP_B)).isFalse();
	}

	@Test
	void isSelfMatchesTokenSubject() {
		UUID me = UUID.randomUUID();
		authenticate(me, List.of("PASSENGER"), Map.of());

		assertThat(authz.isSelf(me)).isTrue();
		assertThat(authz.isSelf(UUID.randomUUID())).isFalse();
	}

	@Test
	void everythingIsDeniedWhenUnauthenticatedOrScopeIsNull() {
		assertThat(authz.isStaff()).isFalse();
		assertThat(authz.canAccessCorporate(CORP_A)).isFalse();
		assertThat(authz.isSelf(UUID.randomUUID())).isFalse();

		authenticate(UUID.randomUUID(), List.of("ADMIN"), Map.of());
		assertThat(authz.canAccessCorporate(null)).isFalse();
	}

	@Test
	void unknownRoleNamesAreIgnored() {
		authenticate(UUID.randomUUID(), List.of("SUPER_HACKER", "PASSENGER"), Map.of());

		assertThat(new CurrentUserProvider().require().roles()).containsExactly(Role.PASSENGER);
	}

	private static void authenticate(UUID userId, List<String> roles, Map<String, Object> corp) {
		Jwt jwt = Jwt.withTokenValue("t")
			.header("alg", "RS256")
			.subject(userId.toString())
			.claim("roles", roles)
			.claim("corp", corp)
			.claim("lang", "km")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}
}

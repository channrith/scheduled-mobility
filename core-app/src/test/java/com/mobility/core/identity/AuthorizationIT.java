package com.mobility.core.identity;

import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class AuthorizationIT {

	static final UUID CORP_A = UUID.randomUUID();

	static final UUID CORP_B = UUID.randomUUID();

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Test
	void roleCheckAllowsDispatcherAndDeniesOthersWithProblem() {
		get("/dispatch", SignedTokens.withRoles(issuer, Role.DISPATCHER)).expectStatus().isOk();

		get("/dispatch", SignedTokens.withRoles(issuer, Role.PASSENGER)).expectStatus()
			.isForbidden()
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.code").isEqualTo("auth.forbidden")
			.jsonPath("$.detail").isEqualTo("You are not allowed to perform this action.");
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "CORPORATE_ADMIN", "CORPORATE_BOOKER" })
	void corporateUserCanOnlyReachOwnCorporate(Role role) {
		String token = SignedTokens.corporate(issuer, CORP_A, role);

		get("/corporates/" + CORP_A, token).expectStatus().isOk();
		get("/corporates/" + CORP_B, token).expectStatus().isForbidden();
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "ADMIN", "DISPATCHER", "SUPPORT", "SAFETY_OFFICER" })
	void staffCanReachAnyCorporate(Role staff) {
		get("/corporates/" + CORP_B, SignedTokens.withRoles(issuer, staff)).expectStatus().isOk();
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "PASSENGER", "DRIVER" })
	void passengersAndDriversCannotReachCorporates(Role role) {
		get("/corporates/" + CORP_A, SignedTokens.withRoles(issuer, role)).expectStatus().isForbidden();
	}

	@Test
	void onlyCorporateAdminOrPlatformAdminCanManageCorporate() {
		get("/corporates/" + CORP_A + "/settings", SignedTokens.corporate(issuer, CORP_A, Role.CORPORATE_ADMIN))
			.expectStatus().isOk();
		get("/corporates/" + CORP_A + "/settings", SignedTokens.corporate(issuer, CORP_A, Role.CORPORATE_BOOKER))
			.expectStatus().isForbidden();
		get("/corporates/" + CORP_B + "/settings", SignedTokens.corporate(issuer, CORP_A, Role.CORPORATE_ADMIN))
			.expectStatus().isForbidden();
		get("/corporates/" + CORP_B + "/settings", SignedTokens.withRoles(issuer, Role.ADMIN)).expectStatus().isOk();
		get("/corporates/" + CORP_B + "/settings", SignedTokens.withRoles(issuer, Role.DISPATCHER))
			.expectStatus().isForbidden();
	}

	@Test
	void unauthenticatedCallIsRejectedBeforeMethodSecurity() {
		client.get().uri("/api/v1/test/authz/corporates/" + CORP_A).exchange().expectStatus().isUnauthorized();
	}

	private RestTestClient.ResponseSpec get(String path, String token) {
		return client.get()
			.uri("/api/v1/test/authz" + path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.exchange();
	}
}

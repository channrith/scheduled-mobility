package com.mobility.core.driver.profile;

import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class DriverSelfIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	DriverApi api;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
	}

	@Test
	void driverSeesOwnProfileWithoutStaffIdentities() {
		Registered driver = api.register(java.util.Map.of("nationalId", "N" + System.nanoTime() % 1_000_000_000L));

		api.get("/api/v1/drivers/me", driver.token())
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.id").isEqualTo(driver.driverId().toString())
			.jsonPath("$.status").isEqualTo("PENDING")
			.jsonPath("$.nationalIdMasked").value(v -> org.assertj.core.api.Assertions.assertThat((String) v).startsWith("•"))
			.jsonPath("$.history[0].to").isEqualTo("PENDING")
			.jsonPath("$.history[0].actorUserId").doesNotExist();
	}

	@Test
	void submitWithoutDocumentsListsWhatIsMissing() {
		Registered driver = api.register();

		api.post("/api/v1/drivers/me/submit", driver.token(), null)
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.code").isEqualTo("driver.documents-missing")
			.jsonPath("$.missing.length()").isEqualTo(3);
	}

	@Test
	void driverRoleWithoutDriverRecordIs404() {
		api.get("/api/v1/drivers/me", SignedTokens.forUser(issuer, UUID.randomUUID(), Role.DRIVER))
			.expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("driver.not-found");
	}

	@Test
	void nonDriversAreForbidden() {
		api.get("/api/v1/drivers/me", api.tokenFor(Role.PASSENGER)).expectStatus().isForbidden();
		api.get("/api/v1/drivers/me", api.tokenFor(Role.ADMIN)).expectStatus().isForbidden();
	}
}

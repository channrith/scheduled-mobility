package com.mobility.core.identity.user;

import java.util.UUID;

import com.mobility.core.AuthTestClient;
import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.identity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class MeIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AuthTestClient auth;

	@Autowired
	UserRepository users;

	@Test
	void returnsCurrentProfileIncludingRolesGrantedAfterLogin() {
		String phone = SamplePhones.next();
		String token = auth.login(phone).accessToken();
		UUID corporateId = UUID.randomUUID();
		User user = users.findByPhoneE164(phone).orElseThrow();
		user.grant(Role.CORPORATE_BOOKER, corporateId);
		users.save(user);

		client.get()
			.uri("/api/v1/me")
			.headers(h -> h.setBearerAuth(token))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.id").isEqualTo(user.getId().toString())
			.jsonPath("$.phone").isEqualTo(phone)
			.jsonPath("$.preferredLang").isEqualTo("km")
			.jsonPath("$.status").isEqualTo("ACTIVE")
			.jsonPath("$.roles.length()").isEqualTo(2)
			.jsonPath("$.roles[0].role").isEqualTo("PASSENGER")
			.jsonPath("$.roles[1].role").isEqualTo("CORPORATE_BOOKER")
			.jsonPath("$.roles[1].scopeId").isEqualTo(corporateId.toString());
	}
}

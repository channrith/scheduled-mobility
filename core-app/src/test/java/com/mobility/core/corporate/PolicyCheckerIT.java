package com.mobility.core.corporate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.corporate.CorporateApi.Company;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.time.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PolicyCheckerIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	PolicyChecker checker;

	static RideRequest ride(VehicleClass vehicleClass, String local, long fare) {
		return new RideRequest(vehicleClass, LocalDateTime.parse(local).atZone(BusinessTime.ZONE).toInstant(), fare, "USD");
	}

	@Test
	void checksTheStoredPolicyOfAnActiveMember() {
		CorporateApi api = new CorporateApi(client, issuer);
		Company company = api.company();
		JsonNode rider = api.addMember(company, "RIDER", Map.of("policy", Map.of("allowedVehicleClasses", List.of("TUKTUK_REMORK"),
				"allowedHours", List.of(Map.of("days", List.of("MONDAY"), "from", "22:00", "to", "06:00")),
				"maxFare", Map.of("amountMinor", 800, "currency", "USD"))));
		UUID userId = UUID.fromString(rider.get("userId").asString());

		assertThat(checker.check(company.id(), userId, ride(VehicleClass.TUKTUK_REMORK, "2026-10-13T05:30", 800)).allowed())
			.isTrue();
		assertThat(checker.check(company.id(), userId, ride(VehicleClass.CAR, "2026-10-13T07:00", 900)).violations())
			.containsExactly(PolicyViolation.VEHICLE_CLASS_NOT_ALLOWED, PolicyViolation.OUTSIDE_ALLOWED_HOURS,
					PolicyViolation.FARE_ABOVE_LIMIT);
	}

	@Test
	void accountAndMembershipMustBeValid() {
		CorporateApi api = new CorporateApi(client, issuer);
		Company company = api.company();
		JsonNode rider = api.addMember(company, "RIDER", Map.of());
		UUID userId = UUID.fromString(rider.get("userId").asString());
		RideRequest any = ride(VehicleClass.CAR, "2026-10-13T09:00", 100);

		assertThat(checker.check(company.id(), UUID.randomUUID(), any).violations()).containsExactly(PolicyViolation.NOT_A_MEMBER);
		assertThat(checker.check(UUID.randomUUID(), userId, any).violations()).containsExactly(PolicyViolation.NOT_A_MEMBER);

		api.delete("/api/v1/corporates/" + company.id() + "/members/" + rider.get("id").asString(), company.adminToken())
			.expectStatus().isNoContent();
		assertThat(checker.check(company.id(), userId, any).violations()).containsExactly(PolicyViolation.MEMBER_REMOVED);

		api.post("/api/v1/admin/corporates/" + company.id() + "/suspend", api.adminToken, Map.of("reason", CorporateApi.REASON))
			.expectStatus().isOk();
		assertThat(checker.check(company.id(), userId, any).violations()).contains(PolicyViolation.ACCOUNT_SUSPENDED);
	}
}

package com.mobility.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Boots with the staging profile on top of the test settings. A separate Spring context (and containers)
 * from {@link IntegrationTest}, so keep this class small.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles({ "test", "staging" })
@Import({ TestcontainersConfiguration.class, IntegrationTestSupport.class })
class StagingProfileIT {

	@Autowired
	RestTestClient client;

	@Test
	void startsHealthy() {
		client.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody()
			.jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	void treatsRequestsAsHttpsWhenCaddySaysSo() {
		// Spring Security sends HSTS only on requests it considers secure.
		client.get()
			.uri("/actuator/health")
			.header("X-Forwarded-Proto", "https")
			.header("X-Forwarded-For", "203.0.113.7")
			.exchange()
			.expectHeader()
			.exists("Strict-Transport-Security");
		client.get().uri("/actuator/health").exchange().expectHeader().doesNotExist("Strict-Transport-Security");
	}

	@Test
	void apiDocsAreOnInStaging() {
		client.get().uri("/v3/api-docs/all").exchange().expectStatus().isOk();
	}
}

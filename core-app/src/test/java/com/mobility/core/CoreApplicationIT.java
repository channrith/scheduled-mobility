package com.mobility.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class CoreApplicationIT {

	@Autowired
	RestTestClient client;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void healthEndpointReportsUpIncludingAllBackends() {
		client.get()
			.uri("/actuator/health")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("UP")
			.jsonPath("$.components.db.status").isEqualTo("UP")
			.jsonPath("$.components.redis.status").isEqualTo("UP")
			.jsonPath("$.components.rabbit.status").isEqualTo("UP");
	}

	@Test
	void flywayCreatesOneSchemaPerModule() {
		var schemas = jdbc.queryForList("SELECT schema_name FROM information_schema.schemata", String.class);

		assertThat(schemas).contains("identity", "driver", "corporate", "place", "pricing", "booking", "dispatch",
				"payment", "notification", "safety", "support", "audit");
	}

	@Test
	void flywayEnablesPostgisAndTrigramExtensions() {
		var extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);

		assertThat(extensions).contains("postgis", "pg_trgm");
	}
}

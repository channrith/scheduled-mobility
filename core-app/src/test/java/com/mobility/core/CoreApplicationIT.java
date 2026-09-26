package com.mobility.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "management.endpoint.health.show-components=always")
@Import(TestcontainersConfiguration.class)
class CoreApplicationIT {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper jsonMapper;

	@Test
	void healthEndpointReportsUpIncludingAllBackends() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
					HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode health = jsonMapper.readTree(response.body());
		assertThat(health.path("status").asString()).isEqualTo("UP");
		for (String component : new String[] { "db", "redis", "rabbit" }) {
			assertThat(health.path("components").path(component).path("status").asString())
				.as(component).isEqualTo("UP");
		}
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

package com.mobility.core.shared.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.mobility.core.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code docs/openapi.json} in sync with the code, so API changes show up in code review and the
 * web console can generate its client types from a committed file.
 * <p>
 * After an intended API change, regenerate it: {@code ./mvnw verify -Dopenapi.update=true}.
 */
@IntegrationTest
class OpenApiSnapshotIT {

	static final Path SNAPSHOT = Path.of("..", "docs", "openapi.json");

	private static final JsonMapper PRETTY = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	@Autowired
	RestTestClient client;

	@Test
	void committedSpecMatchesTheCode() throws Exception {
		String json = client.get().uri("/v3/api-docs/all").exchange().expectStatus().isOk()
			.expectBody(String.class).returnResult().getResponseBody();
		// Re-serialize with sorted keys and indentation for a stable, diff-friendly file.
		String current = PRETTY.writeValueAsString(PRETTY.readValue(json, new TypeReference<Map<String, Object>>() {
		})) + "\n";

		if (Boolean.getBoolean("openapi.update")) {
			Files.createDirectories(SNAPSHOT.getParent());
			Files.writeString(SNAPSHOT, current);
		}
		assertThat(SNAPSHOT).as("docs/openapi.json is missing; run ./mvnw verify -Dopenapi.update=true").exists();
		assertThat(Files.readString(SNAPSHOT))
			.as("The API changed. Review the diff, then run ./mvnw verify -Dopenapi.update=true and commit docs/openapi.json")
			.isEqualTo(current);
	}
}

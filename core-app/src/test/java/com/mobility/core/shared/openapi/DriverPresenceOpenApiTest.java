package com.mobility.core.shared.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

class DriverPresenceOpenApiTest {

	@Test
	void presenceContractSurvivesCommonConventionsInEitherOrder() throws Exception {
		for (boolean conventionsFirst : List.of(true, false)) {
			var api = new OpenApiConfiguration().mobilityOpenApi();
			var conventions = new OpenApiConventions();
			if (conventionsFirst) {
				conventions.customise(api);
			}
			new DriverPresenceOpenApi().customise(api);
			conventions.customise(api);
			var json = Json31.mapper().readTree(Json31.mapper().writeValueAsString(api));
			var operation = json.at("/paths/~1api~1v1~1drivers~1me~1presence/get");
			assertThat(operation.at("/security/0/bearer-jwt").isArray()).isTrue();
			assertThat(operation.at("/x-required-roles/0").asText()).isEqualTo("DRIVER");
			assertThat(operation.has("parameters")).isFalse();
			assertThat(operation.at("/responses/200/headers/Cache-Control/schema/example").asText()).isEqualTo("no-store");
			assertThat(operation.at("/responses/200/content/application~1json/examples/offline/value/lastSeenAt").isNull()).isTrue();
			assertThat(operation.get("responses").properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("200", "401", "403", "502", "503");
			var schema = json.at("/components/schemas/DriverPresenceResponse");
			assertThat(schema.at("/properties/lastSeenAt/type").toString()).contains("string", "null");
			assertThat(schema.get("type").asText()).isEqualTo("object");
			assertThat(schema.at("/properties/online/type").asText()).isEqualTo("boolean");
			assertThat(schema.at("/properties/timeoutSeconds/type").asText()).isEqualTo("integer");
			assertThat(schema.get("required")).hasSize(3);
		}
	}
}

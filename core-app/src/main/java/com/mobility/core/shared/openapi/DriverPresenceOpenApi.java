package com.mobility.core.shared.openapi;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/** Documents the gateway's location-service endpoint without adding a core-app HTTP handler. */
@Component
class DriverPresenceOpenApi implements OpenApiCustomizer {

	static final String PATH = "/api/v1/drivers/me/presence";

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void customise(OpenAPI openApi) {
		Schema lastSeenAt = new Schema<>().format("date-time")
			.description("Server receipt time of the last accepted GPS update; null when offline.");
		lastSeenAt.setTypes(new LinkedHashSet<>(List.of("string", "null")));
		Schema response = new Schema<>().types(Set.of("object"))
			.required(List.of("online", "lastSeenAt", "timeoutSeconds"))
			.addProperty("online", new Schema<>().types(Set.of("boolean"))
				.description("Whether the driver's Redis presence key exists with a valid timestamp."))
			.addProperty("lastSeenAt", lastSeenAt)
			.addProperty("timeoutSeconds", new Schema<>().types(Set.of("integer")).format("int64")
				.minimum(BigDecimal.ONE).example(60)
				.description("Configured presence timeout in seconds (default 60)."));
		openApi.getComponents().addSchemas("DriverPresenceResponse", response);
		var offline = new LinkedHashMap<String, Object>();
		offline.put("online", false);
		offline.put("lastSeenAt", null);
		offline.put("timeoutSeconds", 60);

		var success = new ApiResponse().description("Current presence; reading never refreshes the timeout")
			.addHeaderObject("Cache-Control", new Header().schema(new Schema<>().types(Set.of("string")).example("no-store")))
			.content(new Content().addMediaType("application/json", new MediaType()
				.schema(new Schema<>().$ref("#/components/schemas/DriverPresenceResponse"))
				.addExamples("online", new Example().value(Map.of("online", true,
					"lastSeenAt", "2026-10-31T03:00:00Z", "timeoutSeconds", 60)))
				.addExamples("offline", new Example().value(offline))));
		openApi.path(PATH, new PathItem().get(new Operation()
			.operationId("getDriverPresence").tags(List.of("Driver presence"))
			.summary("Get my driver presence")
			.description("""
					Served by location-service through the API gateway (local: http://localhost:8090).
					Use Swagger through the gateway for Try it out. Requires a registered driver and a DRIVER token;
					all onboarding statuses are allowed. Presence is independent of availability and approval.
					Accepted GPS updates refresh presence; duplicate or old updates and reads do not.
					A missing or expired presence key returns offline with lastSeenAt=null.

					**Roles:** DRIVER""")
			.security(List.of(new SecurityRequirement().addList(OpenApiConfiguration.BEARER)))
			.responses(new ApiResponses().addApiResponse("200", success)
				.addApiResponse("401", new ApiResponse().description("Missing, invalid or expired token, or invalid token subject"))
				.addApiResponse("403", new ApiResponse().description("DRIVER role required, or core denied driver access"))
				.addApiResponse("502", new ApiResponse().description("Core returned an incomplete driver profile"))
				.addApiResponse("503", new ApiResponse().description(
					"Driver verification unavailable, Redis unavailable, or malformed presence timestamp")))
			.extensions(Map.of(OpenApiConventions.ROLES_EXTENSION, List.of("DRIVER")))));
	}
}

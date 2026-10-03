package com.mobility.core.shared.openapi;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document and groups. Served at /v3/api-docs and /swagger-ui.html only when
 * {@code springdoc.api-docs.enabled} is true (local profile, or API_DOCS_ENABLED=true).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	static final String BEARER = "bearer-jwt";

	static final String PROBLEM = "Problem";

	@Bean
	OpenAPI mobilityOpenApi() {
		return new OpenAPI()
			.info(new Info().title("Mobility API")
				.version("v1")
				.description("""
						Scheduled & contracted mobility platform for Phnom Penh.

						* **Authentication:** phone OTP (`/api/v1/auth/otp/request` → `/otp/verify`) returns a 15-minute \
						JWT access token. Send it as `Authorization: Bearer <token>`. Refresh with `/api/v1/auth/refresh`.
						* **Retries:** mutating endpoints accept an `Idempotency-Key` header; a retry with the same key \
						and body replays the original response (`Idempotent-Replayed: true`).
						* **Errors:** RFC 7807 `application/problem+json` with a stable machine-readable `code`; `title` \
						and `detail` are localized from `Accept-Language` (`km` default, `en`).
						* **Time:** timestamps are UTC (ISO-8601); business dates are Asia/Phnom_Penh."""))
			// Fixed relative server so the spec does not depend on the host/port it was generated on.
			.servers(List.of(new Server().url("/").description("This server")))
			.components(new Components()
				.addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
					.description("Access token from /api/v1/auth/otp/verify or /api/v1/auth/refresh"))
				.addSchemas(PROBLEM, problemSchema()));
	}

	@Bean
	GroupedOpenApi allApi() {
		return GroupedOpenApi.builder().group("all").displayName("All endpoints").pathsToMatch("/api/**").build();
	}

	@Bean
	GroupedOpenApi authApi() {
		return GroupedOpenApi.builder().group("auth").displayName("Authentication").pathsToMatch("/api/v1/auth/**").build();
	}

	@Bean
	GroupedOpenApi driverAppApi() {
		return GroupedOpenApi.builder()
			.group("driver-app")
			.displayName("Driver app")
			.pathsToMatch("/api/v1/drivers/me", "/api/v1/drivers/me/**", "/api/v1/me")
			.build();
	}

	@Bean
	GroupedOpenApi adminApi() {
		return GroupedOpenApi.builder().group("admin").displayName("Admin console").pathsToMatch("/api/v1/admin/**").build();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Schema problemSchema() {
		return new Schema<Map<String, Object>>().type("object")
			.description("RFC 7807 problem details. Extra members (e.g. `missing`, `errors`) depend on `code`.")
			.addProperty("type", new Schema<String>().type("string").example("about:blank"))
			.addProperty("title", new Schema<String>().type("string").example("Conflict"))
			.addProperty("status", new Schema<Integer>().type("integer").example(409))
			.addProperty("detail", new Schema<String>().type("string").example("Some required documents have not been uploaded."))
			.addProperty("instance", new Schema<String>().type("string"))
			.addProperty("code", new Schema<String>().type("string")
				.description("Stable error code for clients, e.g. `driver.documents-missing`")
				.example("driver.documents-missing"))
			.additionalProperties(true);
	}
}

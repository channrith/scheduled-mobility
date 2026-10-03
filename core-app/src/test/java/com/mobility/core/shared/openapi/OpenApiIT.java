package com.mobility.core.shared.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.mobility.core.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OpenApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	JsonNode spec;

	@BeforeEach
	void load() {
		spec = fetch("/v3/api-docs/all");
	}

	@Test
	void documentsEveryApiEndpoint() {
		Set<String> mapped = handlerMapping.getHandlerMethods()
			.keySet()
			.stream()
			.flatMap(info -> info.getPatternValues().stream())
			.filter(p -> p.startsWith("/api/") && !p.startsWith("/api/v1/test/"))
			.collect(Collectors.toSet());

		assertThat(spec.get("paths").propertyNames()).containsExactlyInAnyOrderElementsOf(mapped);
	}

	@Test
	void protectedEndpointsDeclareBearerSecurityAndRoles() {
		JsonNode suspend = operation("/api/v1/admin/drivers/{driverId}/suspend", "post");

		assertThat(suspend.get("security").toString()).contains("bearer-jwt");
		assertThat(strings(suspend.get("x-required-roles"))).containsExactly("ADMIN", "SAFETY_OFFICER");
		assertThat(suspend.get("description").asString()).contains("**Roles:** ADMIN, SAFETY_OFFICER");
		assertThat(suspend.get("responses").propertyNames()).contains("401", "403", "404", "409");
		assertThat(spec.at("/components/securitySchemes/bearer-jwt/scheme").asString()).isEqualTo("bearer");

		// Class-level @PreAuthorize is picked up too.
		assertThat(strings(operation("/api/v1/drivers/me", "get").get("x-required-roles"))).containsExactly("DRIVER");
	}

	@Test
	void authEndpointsArePublicAndDocumentRateLimits() {
		JsonNode otpRequest = operation("/api/v1/auth/otp/request", "post");

		assertThat(otpRequest.has("security")).isFalse();
		assertThat(otpRequest.get("responses").propertyNames()).contains("429").doesNotContain("401", "409");
		assertThat(otpRequest.at("/responses/429/description").asString()).contains("otp.resend-cooldown");
	}

	@Test
	void mutatingEndpointsDocumentIdempotencyAndLanguageHeaders() {
		JsonNode refresh = operation("/api/v1/auth/refresh", "post");
		JsonNode me = operation("/api/v1/me", "get");

		assertThat(headers(refresh)).contains("Idempotency-Key", "Accept-Language");
		assertThat(refresh.get("responses").propertyNames()).contains("422");
		assertThat(headers(me)).contains("Accept-Language").doesNotContain("Idempotency-Key");
	}

	@Test
	void errorsReferenceTheProblemSchema() {
		JsonNode notFound = operation("/api/v1/admin/drivers/{driverId}", "get").at("/responses/404/content");

		assertThat(notFound.at("/application~1problem+json/schema/$ref").asString())
			.isEqualTo("#/components/schemas/Problem");
		assertThat(spec.at("/components/schemas/Problem/properties").propertyNames()).contains("code", "title", "detail");
	}

	@Test
	void documentUploadIsAMultipartFormWithAllFields() {
		JsonNode schema = resolve(operation("/api/v1/drivers/me/documents", "post")
			.at("/requestBody/content/multipart~1form-data/schema"));

		assertThat(schema.get("properties").propertyNames()).contains("type", "vehicleId", "expiresOn", "file");
		assertThat(schema.at("/properties/file/format").asString()).isEqualTo("binary");
	}

	@Test
	void documentDownloadDeclaresFileTypes() {
		JsonNode content = operation("/api/v1/admin/drivers/{driverId}/documents/{documentId}/content", "get")
			.at("/responses/200/content");

		assertThat(content.propertyNames()).containsExactlyInAnyOrder("application/pdf", "image/jpeg", "image/png");
	}

	@Test
	void groupsSplitTheApiByAudience() {
		assertThat(fetch("/v3/api-docs/auth").get("paths").propertyNames()).allMatch(p -> p.startsWith("/api/v1/auth/"));
		assertThat(fetch("/v3/api-docs/admin").get("paths").propertyNames()).allMatch(p -> p.startsWith("/api/v1/admin/"));
		assertThat(fetch("/v3/api-docs/driver-app").get("paths").propertyNames())
			.contains("/api/v1/drivers/me", "/api/v1/drivers/me/documents", "/api/v1/me");
	}

	@Test
	void swaggerUiIsServed() {
		client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
	}

	private JsonNode operation(String path, String method) {
		JsonNode op = spec.get("paths").get(path).get(method);
		assertThat(op).as("%s %s", method, path).isNotNull();
		return op;
	}

	private JsonNode resolve(JsonNode schema) {
		return schema.has("$ref") ? spec.at(schema.get("$ref").asString().substring(1)) : schema;
	}

	private JsonNode fetch(String uri) {
		return client.get().uri(uri).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
	}

	private static List<String> headers(JsonNode operation) {
		return operation.get("parameters").valueStream().filter(p -> "header".equals(p.get("in").asString()))
			.map(p -> p.get("name").asString()).toList();
	}

	private static List<String> strings(JsonNode array) {
		return array.valueStream().map(JsonNode::asString).toList();
	}
}

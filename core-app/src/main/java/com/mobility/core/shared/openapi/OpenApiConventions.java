package com.mobility.core.shared.openapi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents the API-wide conventions on every operation, derived from the code so they cannot drift:
 * bearer security, required roles (from {@code @PreAuthorize}), {@code Accept-Language},
 * {@code Idempotency-Key} and problem+json error responses.
 */
@Component
class OpenApiConventions implements GlobalOperationCustomizer, GlobalOpenApiCustomizer {

	static final String ROLES_EXTENSION = "x-required-roles";

	private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

	private static final Set<PathItem.HttpMethod> MUTATING = Set.of(PathItem.HttpMethod.POST, PathItem.HttpMethod.PUT,
			PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE);

	/** Per handler: required roles from @PreAuthorize (method, else class). */
	@Override
	public Operation customize(Operation operation, HandlerMethod handlerMethod) {
		PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
		if (preAuthorize == null) {
			preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
		}
		if (preAuthorize != null) {
			String expression = preAuthorize.value();
			Set<String> roles = new LinkedHashSet<>();
			Matcher m = ROLE.matcher(expression);
			while (m.find()) {
				roles.add(m.group(1));
			}
			String rule = roles.isEmpty() ? "`" + expression + "`" : String.join(", ", roles);
			operation.addExtension(ROLES_EXTENSION, roles.isEmpty() ? List.of(expression) : List.copyOf(roles));
			operation.setDescription(appendLine(operation.getDescription(), "**Roles:** " + rule));
			responses(operation).addApiResponse("403", problem("Authenticated, but not allowed (role or scope)"));
		}
		for (ProblemResponse problem : AnnotatedElementUtils.findMergedRepeatableAnnotations(handlerMethod.getMethod(),
				ProblemResponse.class)) {
			responses(operation).addApiResponse(String.valueOf(problem.status()), problem(problem.description()));
		}
		return operation;
	}

	/** Per path: security, headers and common error responses. */
	@Override
	public void customise(OpenAPI openApi) {
		if (openApi.getPaths() == null) {
			return;
		}
		openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
			boolean secured = !path.startsWith("/api/v1/auth/");
			boolean mutating = MUTATING.contains(method);
			addHeader(operation, acceptLanguage());
			if (mutating) {
				addHeader(operation, idempotencyKey());
				putIfAbsent(operation, "400", "Invalid request (`code` explains why)");
				putIfAbsent(operation, "422", "`idempotency.key-reused`: same Idempotency-Key, different body");
				if (secured) {
					putIfAbsent(operation, "409", "Conflict: invalid state transition, duplicate or concurrent update "
							+ "(`code` explains which)");
				}
			}
			if (secured) {
				operation.setSecurity(new ArrayList<>(List.of(new SecurityRequirement().addList(OpenApiConfiguration.BEARER))));
				putIfAbsent(operation, "401", "Missing, invalid or expired access token");
			}
			if (path.contains("{")) {
				putIfAbsent(operation, "404", "Not found");
			}
		}));
	}

	/** Endpoint-specific {@link ProblemResponse} descriptions win over the generic ones. */
	private static void putIfAbsent(Operation operation, String status, String description) {
		if (!responses(operation).containsKey(status)) {
			responses(operation).addApiResponse(status, problem(description));
		}
	}

	private static void addHeader(Operation operation, Parameter header) {
		List<Parameter> parameters = operation.getParameters() == null ? new ArrayList<>()
				: new ArrayList<>(operation.getParameters());
		if (parameters.stream().noneMatch(p -> header.getName().equalsIgnoreCase(p.getName()) && "header".equals(p.getIn()))) {
			parameters.add(header);
		}
		operation.setParameters(parameters);
	}

	private static Parameter acceptLanguage() {
		return new HeaderParameter().name("Accept-Language")
			.description("Language of error messages and SMS: `km` (default) or `en`")
			.required(false)
			.schema(new StringSchema()._enum(List.of("km", "en")));
	}

	private static Parameter idempotencyKey() {
		return new HeaderParameter().name("Idempotency-Key")
			.description("Optional, 1–255 chars (a UUID is ideal). Retrying with the same key and body replays the "
					+ "first response with `Idempotent-Replayed: true`; a different body returns 422.")
			.required(false)
			.schema(new StringSchema().maxLength(255));
	}

	private static ApiResponses responses(Operation operation) {
		if (operation.getResponses() == null) {
			operation.setResponses(new ApiResponses());
		}
		return operation.getResponses();
	}

	private static ApiResponse problem(String description) {
		return new ApiResponse().description(description)
			.content(new Content().addMediaType("application/problem+json",
					new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + OpenApiConfiguration.PROBLEM))));
	}

	private static String appendLine(String existing, String line) {
		return existing == null || existing.isBlank() ? line : existing + "\n\n" + line;
	}
}

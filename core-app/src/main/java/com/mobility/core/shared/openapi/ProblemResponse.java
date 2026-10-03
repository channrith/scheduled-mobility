package com.mobility.core.shared.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents an endpoint-specific problem+json error response in the OpenAPI spec, on top of the
 * conventions applied to every endpoint (400/401/403/404/409/422). Mention the {@code code} values.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ProblemResponse.List.class)
public @interface ProblemResponse {

	int status();

	String description();

	@Documented
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	@interface List {

		ProblemResponse[] value();
	}
}

package com.mobility.core.shared.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A business error rendered as RFC 7807 problem+json. {@code code} selects the localized detail
 * message {@code error.<code>} in the message bundles.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final Object[] args;

	private final HttpHeaders headers = new HttpHeaders();

	public ApiException(HttpStatus status, String code, Object... args) {
		super(code);
		this.status = status;
		this.code = code;
		this.args = args;
	}

	public ApiException withHeader(String name, String value) {
		headers.set(name, value);
		return this;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Object[] getArgs() {
		return args;
	}

	public HttpHeaders getHeaders() {
		return headers;
	}
}

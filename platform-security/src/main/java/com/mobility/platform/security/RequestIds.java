package com.mobility.platform.security;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request IDs: the API gateway gives every request an {@code X-Request-Id}; every service logs it as
 * {@code request.id}. A client's own ID is kept when it looks sane, so an app can correlate its requests.
 */
public final class RequestIds {

	public static final String HEADER = "X-Request-Id";

	/** The log field (MDC key in servlet services, a key-value pair in the gateway). */
	public static final String LOG_KEY = "request.id";

	/** Long enough to be unique, short and plain enough to be safe in logs. */
	private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,100}");

	private RequestIds() {
	}

	/** The candidate if it is acceptable, else a new UUID. */
	public static String accept(String candidate) {
		return candidate != null && ACCEPTED.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
	}
}

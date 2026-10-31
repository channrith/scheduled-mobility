package com.mobility.core.shared.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's {@code X-Request-Id} (set by the API gateway) into every log line of the request, as
 * {@code request.id}, so one request can be followed from the gateway into core-app. Direct calls without one (local
 * development, internal callers) get a new ID. The gateway returns the ID to the client; this filter does not.
 * Runs first, so security and error logging carry the ID too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Request-Id";

	static final String MDC_KEY = "request.id";

	/** As the gateway accepts them: long enough to be unique, plain enough to be safe in logs. */
	private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,100}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = request.getHeader(HEADER);
		MDC.put(MDC_KEY, id != null && ACCEPTED.matcher(id).matches() ? id : UUID.randomUUID().toString());
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}
}

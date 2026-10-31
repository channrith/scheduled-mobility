package com.mobility.location;

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
 * {@code request.id}; direct calls without one get a new ID. Same rules as core-app's filter (the two move into a
 * shared module with the JWT setup in phase 2). Runs first, so security logging carries the ID too.
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
		request.setAttribute(MDC_KEY, MDC.get(MDC_KEY));
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}
}

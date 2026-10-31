package com.mobility.platform.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's {@code X-Request-Id} (set by the API gateway) into every log line of the request as
 * {@code request.id}; direct calls without one (local development, internal callers) get a new ID. The gateway
 * returns the ID to the client; this filter does not. Runs first, so security and error logging carry it too.
 * Registered automatically in servlet services that depend on this module ({@link RequestIdAutoConfiguration}).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		MDC.put(RequestIds.LOG_KEY, RequestIds.accept(request.getHeader(RequestIds.HEADER)));
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(RequestIds.LOG_KEY);
		}
	}
}

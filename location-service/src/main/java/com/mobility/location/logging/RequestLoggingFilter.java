package com.mobility.location.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** One completion log per HTTP request; bodies, headers, query strings and raw paths are never logged. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "location.logging", name = "requests-enabled", matchIfMissing = true)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "request.id";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && UUID_PATTERN.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            try {
                Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = matched instanceof String pattern ? pattern : "<unmatched>";
                int status = failed ? 500 : response.getStatus();
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                var event = status >= 500 ? log.atWarn() : log.atInfo();
                event.addKeyValue("http.request.method", request.getMethod())
                        .addKeyValue("http.route", route)
                        .addKeyValue("http.response.status_code", status)
                        .addKeyValue("duration_ms", duration)
                        .log("HTTP request completed: method={} route={} status={} durationMs={} requestId={}",
                                request.getMethod(), route, status, duration, requestId);
            } finally {
                if (previous == null) MDC.remove(MDC_KEY);
                else MDC.put(MDC_KEY, previous);
            }
        }
    }
}

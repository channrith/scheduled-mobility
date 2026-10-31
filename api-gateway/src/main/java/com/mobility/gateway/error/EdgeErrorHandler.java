package com.mobility.gateway.error;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import com.mobility.gateway.edge.RequestIdFilter;
import com.mobility.platform.security.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Errors raised at the gateway itself (no route, backend down or slow, body too large) as problem+json
 * ({@link EdgeProblems}). Errors a backend returns pass through untouched. Runs before Spring Boot's default handler
 * (order -1).
 */
@Component
@Order(-2)
class EdgeErrorHandler implements WebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(EdgeErrorHandler.class);

	private final EdgeProblems problems;

	EdgeErrorHandler(EdgeProblems problems) {
		this.problems = problems;
	}

	/** An edge error: its HTTP status and the message code under {@code error.gateway.}. */
	record EdgeError(HttpStatus status, String code) {
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.error(ex);
		}
		EdgeError error = classify(ex);
		if (error.status().is5xxServerError()) {
			log.atWarn()
				.addKeyValue(RequestIds.LOG_KEY, RequestIdFilter.of(exchange))
				.log("{} {} failed at the gateway ({}): {}", exchange.getRequest().getMethod().name(),
						exchange.getRequest().getPath().value(), error.code(), ex.toString());
		}
		return problems.write(exchange, error.status(), "gateway." + error.code());
	}

	static EdgeError classify(Throwable ex) {
		if (causedBy(ex, ConnectException.class)) {
			return new EdgeError(HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable");
		}
		if (causedBy(ex, TimeoutException.class)) {
			return new EdgeError(HttpStatus.GATEWAY_TIMEOUT, "upstream-timeout");
		}
		if (ex instanceof ResponseStatusException rse) {
			HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
			if (status == null) {
				return new EdgeError(HttpStatus.INTERNAL_SERVER_ERROR, "error");
			}
			return new EdgeError(status, switch (status) {
				case NOT_FOUND -> "not-found";
				case METHOD_NOT_ALLOWED -> "method-not-allowed";
				case CONTENT_TOO_LARGE -> "too-large";
				case SERVICE_UNAVAILABLE -> "upstream-unavailable";
				case GATEWAY_TIMEOUT -> "upstream-timeout";
				default -> status.is4xxClientError() ? "bad-request" : "error";
			});
		}
		return new EdgeError(HttpStatus.INTERNAL_SERVER_ERROR, "error");
	}

	private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}
}

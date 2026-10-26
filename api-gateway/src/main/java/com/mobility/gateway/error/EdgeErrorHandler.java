package com.mobility.gateway.error;

import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import com.mobility.gateway.edge.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Errors raised at the gateway itself (no route, backend down or slow, body too large) as RFC 7807 problem+json,
 * localized like core-app's: Khmer by default, English for {@code Accept-Language: en}. Errors a backend returns
 * pass through untouched. Runs before Spring Boot's default handler (order -1).
 */
@Component
@Order(-2)
class EdgeErrorHandler implements WebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(EdgeErrorHandler.class);

	private static final Locale KHMER = Locale.forLanguageTag("km");

	private static final List<Locale> SUPPORTED = List.of(KHMER, Locale.ENGLISH);

	private final MessageSource messages;

	private final JsonMapper json;

	EdgeErrorHandler(MessageSource messages, JsonMapper json) {
		this.messages = messages;
		this.json = json;
	}

	/** An edge error: its HTTP status and the message code under {@code error.gateway.}. */
	record EdgeError(HttpStatus status, String code) {
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		ServerHttpResponse response = exchange.getResponse();
		if (response.isCommitted()) {
			return Mono.error(ex);
		}
		EdgeError error = classify(ex);
		if (error.status().is5xxServerError()) {
			log.atWarn()
				.addKeyValue("request.id", RequestIdFilter.of(exchange))
				.log("{} {} failed at the gateway ({}): {}", exchange.getRequest().getMethod().name(),
						exchange.getRequest().getPath().value(), error.code(), ex.toString());
		}
		Locale locale = locale(exchange);
		ProblemDetail problem = ProblemDetail.forStatus(error.status());
		problem.setTitle(messages.getMessage("error.title." + error.status().value(), null,
				error.status().getReasonPhrase(), locale));
		problem.setDetail(messages.getMessage("error.gateway." + error.code(), null, error.code(), locale));
		problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
		problem.setProperty("code", "gateway." + error.code());
		problem.setProperty("requestId", RequestIdFilter.of(exchange));
		byte[] body = json.writeValueAsBytes(problem);
		response.setStatusCode(error.status());
		response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		response.getHeaders().setContentLength(body.length);
		return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
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

	/** km or en from Accept-Language; anything else (or no header) gets Khmer, as in core-app. */
	private static Locale locale(ServerWebExchange exchange) {
		try {
			Locale match = Locale.lookup(exchange.getRequest().getHeaders().getAcceptLanguage(), SUPPORTED);
			return match == null ? KHMER : match;
		}
		catch (IllegalArgumentException malformed) {
			return KHMER;
		}
	}
}

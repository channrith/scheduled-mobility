package com.mobility.gateway.error;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import com.mobility.gateway.edge.RequestIdFilter;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the gateway's own answers as RFC 7807 problem+json, like core-app's: {@code code}, title and detail
 * localized from {@code error.title.<status>} and {@code error.<code>} (Khmer by default, English for
 * {@code Accept-Language: en}), plus {@code instance} and {@code requestId}.
 */
@Component
public class EdgeProblems {

	private static final Locale KHMER = Locale.forLanguageTag("km");

	private static final List<Locale> SUPPORTED = List.of(KHMER, Locale.ENGLISH);

	private final MessageSource messages;

	private final JsonMapper json;

	EdgeProblems(MessageSource messages, JsonMapper json) {
		this.messages = messages;
		this.json = json;
	}

	/** @param code e.g. {@code gateway.not-found} or {@code auth.unauthorized} */
	public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code) {
		Locale locale = locale(exchange);
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setTitle(messages.getMessage("error.title." + status.value(), null, status.getReasonPhrase(), locale));
		problem.setDetail(messages.getMessage("error." + code, null, code, locale));
		problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
		problem.setProperty("code", code);
		problem.setProperty("requestId", RequestIdFilter.of(exchange));
		byte[] body = json.writeValueAsBytes(problem);
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		response.getHeaders().setContentLength(body.length);
		return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
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

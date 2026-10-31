package com.mobility.gateway.edge;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses bodies larger than {@code gateway.edge.max-request-size} (by Content-Length) before they reach a backend.
 * Caddy caps public traffic too; this also covers internal callers such as the console.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSizeFilter implements WebFilter {

	private final long maxBytes;

	RequestSizeFilter(EdgeProperties properties) {
		this.maxBytes = properties.maxRequestSize().toBytes();
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (exchange.getRequest().getHeaders().getContentLength() > maxBytes) {
			return Mono.error(new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE));
		}
		return chain.filter(exchange);
	}
}

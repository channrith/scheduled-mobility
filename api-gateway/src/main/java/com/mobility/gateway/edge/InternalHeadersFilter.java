package com.mobility.gateway.edge;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Removes headers a client could use to pose as an internal caller (e.g. {@code X-User-Id}, {@code X-Internal-*}).
 * Only the gateway sets such headers, so none from outside may reach a backend.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalHeadersFilter implements WebFilter {

	private final Set<String> names;

	private final String prefix;

	InternalHeadersFilter(EdgeProperties properties) {
		this.names = properties.internalHeaders().stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		this.prefix = properties.internalHeaderPrefix().toLowerCase(Locale.ROOT);
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		List<String> spoofed = exchange.getRequest()
			.getHeaders()
			.headerNames()
			.stream()
			.filter(this::isInternal)
			.toList();
		if (spoofed.isEmpty()) {
			return chain.filter(exchange);
		}
		return chain.filter(exchange.mutate().request(r -> r.headers(h -> spoofed.forEach(h::remove))).build());
	}

	private boolean isInternal(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return names.contains(lower) || lower.startsWith(prefix);
	}
}

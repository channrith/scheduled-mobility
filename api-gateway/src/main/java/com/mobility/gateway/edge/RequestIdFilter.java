package com.mobility.gateway.edge;

import com.mobility.platform.security.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request an {@code X-Request-Id} (the client's own if it looks sane, else a new UUID), passes it to
 * the backend, returns it on the response and writes one access line with it. Runs for every request, including
 * those no route matches, so edge errors carry the ID as well.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

	public static final String HEADER = RequestIds.HEADER;

	/** Exchange attribute holding the request's ID. */
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

	private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

	private static final String HEALTH_PATH = "/actuator/health";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String id = RequestIds.accept(exchange.getRequest().getHeaders().getFirst(HEADER));
		exchange.getAttributes().put(ATTRIBUTE, id);
		exchange.getResponse().getHeaders().set(HEADER, id);
		ServerWebExchange withId = exchange.mutate().request(r -> r.headers(h -> h.set(HEADER, id))).build();
		long started = System.nanoTime();
		return chain.filter(withId).doFinally(signal -> {
			HttpStatusCode status = exchange.getResponse().getStatusCode();
			// Docker's health check calls every 15 s; only a failing one is worth a line.
			if (status != null && status.is2xxSuccessful()
					&& exchange.getRequest().getPath().value().startsWith(HEALTH_PATH)) {
				return;
			}
			log.atInfo()
				.addKeyValue(RequestIds.LOG_KEY, id)
				.addKeyValue("http.method", exchange.getRequest().getMethod().name())
				.addKeyValue("url.path", exchange.getRequest().getPath().value())
				.addKeyValue("http.status", status == null ? 0 : status.value())
				.addKeyValue("duration.ms", (System.nanoTime() - started) / 1_000_000)
				.log("{} {} -> {}", exchange.getRequest().getMethod().name(), exchange.getRequest().getPath().value(),
						status == null ? "-" : status.value());
		});
	}

	/** The request's ID, or null outside a request this filter has seen. */
	public static String of(ServerWebExchange exchange) {
		return exchange.getAttribute(ATTRIBUTE);
	}
}

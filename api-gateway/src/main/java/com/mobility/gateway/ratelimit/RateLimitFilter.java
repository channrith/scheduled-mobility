package com.mobility.gateway.ratelimit;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.mobility.gateway.edge.RequestIdFilter;
import com.mobility.gateway.error.EdgeProblems;
import com.mobility.gateway.ratelimit.RateLimitProperties.Key;
import com.mobility.gateway.ratelimit.RateLimitProperties.Policy;
import com.mobility.platform.security.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Edge rate limits (phase 3). Runs after Spring Security, so per-user policies see the token's subject and requests
 * without a valid token on protected routes have already been answered 401. The first matching policy takes a token
 * from the caller's bucket; with none left the answer is 429 problem+json {@code gateway.rate-limited} with
 * {@code Retry-After}, and the request never reaches a backend. Fails open: when Redis is down or slower than
 * {@code gateway.rate-limits.redis-timeout}, the request goes through (and a warning is logged at most once a
 * minute).
 */
@Component
@Order(0) // after Spring Security's WebFilterChainProxy (-100)
class RateLimitFilter implements WebFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private static final long WARN_EVERY_MILLIS = Duration.ofMinutes(1).toMillis();

	private record CompiledPolicy(Policy policy, List<PathPattern> paths) {

		boolean matches(ServerHttpRequest request) {
			if (policy.method() != null && !policy.method().equals(request.getMethod())) {
				return false;
			}
			PathContainer path = request.getPath().pathWithinApplication();
			return paths.stream().anyMatch(pattern -> pattern.matches(path));
		}
	}

	private final boolean enabled;

	private final Duration redisTimeout;

	private final List<CompiledPolicy> policies;

	private final TokenBuckets buckets;

	private final ClientIp clientIp;

	private final EdgeProblems problems;

	private final AtomicLong lastWarning = new AtomicLong();

	RateLimitFilter(RateLimitProperties properties, TokenBuckets buckets, ClientIp clientIp, EdgeProblems problems) {
		this.enabled = properties.enabled();
		this.redisTimeout = properties.redisTimeout();
		this.policies = properties.policies().stream()
			.map(policy -> new CompiledPolicy(policy,
					policy.paths().stream().map(PathPatternParser.defaultInstance::parse).toList()))
			.toList();
		this.buckets = buckets;
		this.clientIp = clientIp;
		this.problems = problems;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (!enabled) {
			return chain.filter(exchange);
		}
		Policy policy = policies.stream()
			.filter(candidate -> candidate.matches(exchange.getRequest()))
			.findFirst()
			.map(CompiledPolicy::policy)
			.orElse(null);
		if (policy == null) {
			return chain.filter(exchange);
		}
		return caller(exchange, policy.key())
			.flatMap(caller -> buckets.take(policy, caller)
				.timeout(redisTimeout)
				.onErrorResume(error -> {
					warnFailOpen(policy, error);
					return Mono.just(TokenBuckets.Decision.ALLOWED);
				}))
			.flatMap(decision -> decision.allowed() ? chain.filter(exchange) : refuse(exchange, policy, decision));
	}

	private Mono<String> caller(ServerWebExchange exchange, Key key) {
		String ip = "ip:" + clientIp.of(exchange.getRequest());
		if (key == Key.IP) {
			return Mono.just(ip);
		}
		return exchange.getPrincipal().map(Principal::getName).map(subject -> "user:" + subject).defaultIfEmpty(ip);
	}

	private Mono<Void> refuse(ServerWebExchange exchange, Policy policy, TokenBuckets.Decision decision) {
		long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
		log.atInfo()
			.addKeyValue(RequestIds.LOG_KEY, RequestIdFilter.of(exchange))
			.addKeyValue("rate_limit.policy", policy.id())
			.log("Rate limit '{}' reached for {} {}", policy.id(), exchange.getRequest().getMethod().name(),
					exchange.getRequest().getPath().value());
		exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
		return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "gateway.rate-limited");
	}

	private void warnFailOpen(Policy policy, Throwable error) {
		long now = System.currentTimeMillis();
		long last = lastWarning.get();
		if (now - last >= WARN_EVERY_MILLIS && lastWarning.compareAndSet(last, now)) {
			log.warn("Rate limits are not applied (Redis unavailable, requests pass); policy '{}': {}", policy.id(),
					error.toString());
		}
	}
}

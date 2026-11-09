package com.mobility.gateway.ratelimit;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Connects to Redis and loads the bucket script soon after startup, so the first requests after a restart are
 * counted (Lettuce otherwise connects on the first request, which on a cold JVM can miss the per-request timeout).
 *
 * <p>
 * Runs in the background and never blocks startup or readiness: Lettuce's first connect is synchronous, and a
 * blocking warm-up on a CPU-starved droplet kept the gateway from ever reporting ready (staging, 2026-11-09).
 * Retries for about a minute, then gives up quietly: the limits fail open and the first request connects later.
 */
@Component
class RedisWarmUp {

	private static final Logger log = LoggerFactory.getLogger(RedisWarmUp.class);

	private static final int ATTEMPTS = 6;

	private static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);

	private final boolean enabled;

	private final TokenBuckets buckets;

	RedisWarmUp(RateLimitProperties properties, TokenBuckets buckets) {
		this.enabled = properties.enabled();
		this.buckets = buckets;
	}

	@EventListener(ApplicationReadyEvent.class)
	void warmUp() {
		if (!enabled) {
			return;
		}
		Mono.defer(buckets::warmUp)
			// The first connect blocks the subscribing thread: keep it off the startup thread and the event loops.
			.subscribeOn(Schedulers.boundedElastic())
			.retryWhen(Retry.backoff(ATTEMPTS - 1, FIRST_BACKOFF).maxBackoff(Duration.ofSeconds(20)))
			.subscribe(null,
					error -> log.warn("Redis not reachable after startup; rate limits pass requests until it is: {}",
							error.toString()),
					() -> log.info("Redis connected for rate limits"));
	}
}

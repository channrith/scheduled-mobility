package com.mobility.gateway.ratelimit;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Connects to Redis once at startup, before the gateway reports ready (readiness flips to accepting traffic after
 * {@link ApplicationReadyEvent}). Without it the first request after a start opens the connection itself and can
 * time out, so it isn't counted. Fails open like the limits: if Redis is down the gateway starts anyway and the
 * first request that needs a bucket connects later.
 */
@Component
class RedisWarmUp {

	private static final Logger log = LoggerFactory.getLogger(RedisWarmUp.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(3);

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
		try {
			buckets.warmUp().block(TIMEOUT);
			log.info("Redis connected for rate limits");
		}
		catch (RuntimeException error) {
			log.warn("Redis not reachable at startup; rate limits pass requests until it is: {}", error.toString());
		}
	}
}

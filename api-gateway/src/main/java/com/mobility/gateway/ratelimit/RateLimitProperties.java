package com.mobility.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;

/**
 * Rate limits at the edge (phase 3), one token bucket per policy and key, kept in Redis.
 *
 * @param enabled false turns every limit off (tests that don't run Redis)
 * @param redisTimeout longest wait for Redis per request; past it (or if Redis is down) the request goes through
 * @param keyPrefix prefix of the bucket keys in Redis
 * @param policies checked in order; the first that matches a request decides, and a request no policy matches is
 *        not limited
 */
@ConfigurationProperties("gateway.rate-limits")
public record RateLimitProperties(@DefaultValue("true") boolean enabled, @DefaultValue("100ms") Duration redisTimeout,
		@DefaultValue("rl:") String keyPrefix, @DefaultValue List<Policy> policies) {

	/** Whose bucket a request takes a token from. */
	public enum Key {

		/** The caller's IP address (see {@link ClientIp}). */
		IP,

		/** The {@code sub} of the caller's token; the IP address when there is no token. */
		USER

	}

	/**
	 * @param id names the bucket in Redis and in logs, e.g. {@code auth}
	 * @param paths path patterns, e.g. {@code /api/v1/auth/**}
	 * @param method only this HTTP method, or any when empty
	 * @param key whose bucket
	 * @param burst bucket size: requests allowed at once after a quiet spell
	 * @param rate tokens added back per {@code period}
	 * @param period see {@code rate}
	 */
	public record Policy(String id, List<String> paths, HttpMethod method, Key key, int burst, int rate,
			Duration period) {

		public Policy {
			if (id == null || id.isBlank() || paths == null || paths.isEmpty() || key == null || burst < 1 || rate < 1
					|| period == null || period.isZero() || period.isNegative()) {
				throw new IllegalArgumentException("Rate-limit policy '" + id
						+ "' needs id, paths, key, burst >= 1, rate >= 1 and a positive period");
			}
		}

		/** Tokens added back per millisecond. */
		double tokensPerMilli() {
			return (double) rate / period.toMillis();
		}
	}
}
